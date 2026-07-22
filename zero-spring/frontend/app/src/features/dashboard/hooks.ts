import { useQuery } from '@tanstack/react-query';
import { ApiError } from '@/api/client';
import { listAuditLogs } from '@/features/audit/api';
import type { AuditLogDto } from '@/features/audit/types';
import { getUnreadCount } from '@/features/notifications/api';
import { NOTIFICATIONS_UNREAD_KEY } from '@/features/notifications/hooks';
import { listRoles } from '@/features/roles/api';
import { getMySubscription } from '@/features/subscriptions/api';
import { listTenants } from '@/features/tenants/api';
import { listUsers } from '@/features/users/api';
import type { UserDto } from '@/features/users/types';

/**
 * Dashboard widget queries (TanStack Query bindings).
 *
 * There are NO dashboard-only endpoints: every widget reads through the owning
 * feature's typed `api.ts` wrapper (users/roles/tenants/notifications/
 * subscriptions/audit), so this module adds no second copy of any contract.
 *
 * Shared policy (deliberate, applied to every query):
 *  - `staleTime: 60s`, `refetchOnWindowFocus: false`, `retry: 1` — a dashboard
 *    is a glance surface, not a live console; no polling, no focus storms.
 *  - `enabled` carries the caller's PERMISSION: a query whose permission the
 *    user lacks is never sent at all (no 403 spam in the network tab). The
 *    frontend check is UX only — the backend enforces the same permission via
 *    `@PreAuthorize` on each endpoint.
 */

export const dashboardKeys = {
  all: ['dashboard'] as const,
  userCount: () => [...dashboardKeys.all, 'user-count'] as const,
  roleCount: () => [...dashboardKeys.all, 'role-count'] as const,
  tenantCount: () => [...dashboardKeys.all, 'tenant-count'] as const,
  activityTrend: () => [...dashboardKeys.all, 'activity-trend'] as const,
  recentUsers: () => [...dashboardKeys.all, 'recent-users'] as const,
  recentActivity: () => [...dashboardKeys.all, 'recent-activity'] as const,
  mySubscription: () => [...dashboardKeys.all, 'my-subscription'] as const,
};

/**
 * Deterministic 4xx responses (missing permission, missing resource) are never
 * retried; transient failures get exactly one retry. Applies to EVERY widget
 * query via the shared defaults — not per-hook.
 */
function retryOnceUnless4xx(failureCount: number, error: Error): boolean {
  if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
    return false;
  }
  return failureCount < 1;
}

const WIDGET_QUERY_DEFAULTS = {
  staleTime: 60_000,
  refetchOnWindowFocus: false,
  retry: retryOnceUnless4xx,
} as const;

/** Trend window (days, inclusive of today). */
export const TREND_DAYS = 14;
/**
 * Upper bound on rows fetched for the client-side day grouping. MUST NOT
 * exceed the server's silent cap — `spring.data.web.pageable.max-page-size: 100`
 * (`application.yml`) clamps any larger `size` without an error, which would
 * skew a desc-sorted sample toward the newest hours only. When the window holds
 * more rows than this, the widget shows a "based on the latest N" indicator
 * (`totalElements` vs. sample size).
 */
export const TREND_SAMPLE_SIZE = 100;
export const RECENT_USERS_SIZE = 5;
export const RECENT_ACTIVITY_SIZE = 8;

/** KPI: total user count via `GET /api/users?page=0&size=1` (`users.read`). */
export function useUserCount(enabled: boolean) {
  return useQuery({
    queryKey: dashboardKeys.userCount(),
    queryFn: () => listUsers({ page: 0, size: 1 }),
    select: (page) => page.totalElements ?? 0,
    enabled,
    ...WIDGET_QUERY_DEFAULTS,
  });
}

/** KPI: total role count via `GET /api/roles?page=0&size=1` (`roles.read`). */
export function useRoleCount(enabled: boolean) {
  return useQuery({
    queryKey: dashboardKeys.roleCount(),
    queryFn: () => listRoles({ page: 0, size: 1 }),
    select: (page) => page.totalElements ?? 0,
    enabled,
    ...WIDGET_QUERY_DEFAULTS,
  });
}

/**
 * KPI: tenant count (`tenants.manage`, host-only permission).
 *
 * Contract note: `GET /api/tenants` returns a PLAIN ARRAY, not a Spring `Page`
 * (`TenantController.list()` has no `Pageable`), so the count is the array
 * length — there is no `totalElements` to read.
 */
export function useTenantCount(enabled: boolean) {
  return useQuery({
    queryKey: dashboardKeys.tenantCount(),
    queryFn: () => listTenants(),
    select: (tenants) => tenants.length,
    enabled,
    ...WIDGET_QUERY_DEFAULTS,
  });
}

/**
 * KPI: unread notification count. Authentication is enough (no permission).
 *
 * SAME query key as the header bell (`NOTIFICATIONS_UNREAD_KEY`) on purpose:
 * `refetchInterval` is per-observer in TanStack Query, so this observer adds no
 * polling, while the bell's mutations ("mark read", "read all") invalidate one
 * shared key and the KPI updates with it — a separate key left the KPI stale.
 */
export function useUnreadNotifications(enabled: boolean) {
  return useQuery({
    queryKey: NOTIFICATIONS_UNREAD_KEY,
    queryFn: () => getUnreadCount(),
    select: (data) => data.count ?? 0,
    enabled,
    ...WIDGET_QUERY_DEFAULTS,
  });
}

/** Start of the local day `TREND_DAYS - 1` days ago, as ISO-8601 for `startDate`. */
function trendStartDate(now: Date): string {
  const start = new Date(
    now.getFullYear(),
    now.getMonth(),
    now.getDate() - (TREND_DAYS - 1),
  );
  return start.toISOString();
}

export interface TrendSample {
  rows: AuditLogDto[];
  /** Window total on the server — when it exceeds `rows.length`, the sample is partial. */
  totalElements: number;
}

/**
 * Trend source: the latest audit rows of the 14-day window (`auditlogs.read`).
 * Sorted by `executionTime` desc — the actual `AuditLogDto` timestamp field —
 * and grouped per-day client-side (`buildTrendSeries`). `totalElements` rides
 * along so the widget can DISCLOSE when the window holds more rows than the
 * sample (see `TREND_SAMPLE_SIZE` — the server clamps larger pages silently).
 */
export function useActivityTrend(enabled: boolean) {
  return useQuery({
    queryKey: dashboardKeys.activityTrend(),
    queryFn: () =>
      listAuditLogs({
        page: 0,
        size: TREND_SAMPLE_SIZE,
        sort: 'executionTime,desc',
        startDate: trendStartDate(new Date()),
      }),
    select: (page): TrendSample => ({
      rows: page.content ?? [],
      totalElements: page.totalElements ?? page.content?.length ?? 0,
    }),
    enabled,
    ...WIDGET_QUERY_DEFAULTS,
  });
}

/**
 * Newest users (`users.read`). `createdAt` is not on `UserDto`, but it IS a
 * `User` entity property (`AbstractAuditedEntity.createdAt`), so Spring's
 * `Pageable` sort binds it server-side — the newest rows come back first even
 * though the DTO doesn't carry the timestamp.
 */
export function useRecentUsers(enabled: boolean) {
  return useQuery({
    queryKey: dashboardKeys.recentUsers(),
    queryFn: () =>
      listUsers({ page: 0, size: RECENT_USERS_SIZE, sort: ['createdAt,desc'] }),
    select: (page): UserDto[] => page.content ?? [],
    enabled,
    ...WIDGET_QUERY_DEFAULTS,
  });
}

/** Latest audit entries for the timeline (`auditlogs.read`). */
export function useRecentActivity(enabled: boolean) {
  return useQuery({
    queryKey: dashboardKeys.recentActivity(),
    queryFn: () =>
      listAuditLogs({
        page: 0,
        size: RECENT_ACTIVITY_SIZE,
        sort: 'executionTime,desc',
      }),
    select: (page): AuditLogDto[] => page.content ?? [],
    enabled,
    ...WIDGET_QUERY_DEFAULTS,
  });
}

/**
 * The caller's own subscription (`GET /api/subscriptions/me`, auth only;
 * meaningful in tenant context — pass `enabled: isTenantContext`).
 *
 * Contract note: a tenant WITHOUT a subscription gets a 404 ProblemDetail
 * (`SubscriptionService.requireSubscription`), which the widget renders as the
 * EMPTY state, not an error. The shared defaults already skip retrying 4xx.
 */
export function useMySubscription(enabled: boolean) {
  return useQuery({
    queryKey: dashboardKeys.mySubscription(),
    queryFn: () => getMySubscription(),
    enabled,
    ...WIDGET_QUERY_DEFAULTS,
  });
}

/** `true` when the query failed with the "no subscription" 404 (empty, not error). */
export function isSubscriptionMissing(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404;
}
