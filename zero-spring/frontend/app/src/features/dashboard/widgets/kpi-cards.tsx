import { ReactNode, useId } from 'react';
import type { UseQueryResult } from '@tanstack/react-query';
import {
  Bell,
  Building,
  RotateCw,
  ShieldCheck,
  TriangleAlert,
  Users,
} from 'lucide-react';
import { FormattedMessage, FormattedNumber, useIntl } from 'react-intl';
import { useAuth } from '@/providers/auth-provider';
import { usePermission } from '@/auth/rbac';
import { Button } from '@/components/ui/button';
import { WidgetSkeleton } from '@/components/widgets/widget-states';
import {
  useRoleCount,
  useTenantCount,
  useUnreadNotifications,
  useUserCount,
} from '../hooks';

/**
 * KPI band — up to four count tiles (Users / Roles / Tenants / Unread).
 *
 * Permission model per tile (frontend = UX, backend enforces the same key):
 *  - Users    → `users.read`
 *  - Roles    → `roles.read`
 *  - Tenants  → `tenants.manage` (a `Side.HOST` permission) AND host context
 *  - Unread   → authentication only
 *
 * A tile whose permission is absent is NOT rendered and its query is NEVER
 * sent (`enabled: false`); the grid re-flows without gaps. If no tile is
 * visible the whole band collapses to nothing.
 */

interface KpiCardProps {
  labelId: string;
  icon: ReactNode;
  query: UseQueryResult<number>;
}

function KpiCard({ labelId, icon, query }: KpiCardProps) {
  const intl = useIntl();
  const headingId = useId();

  return (
    <section
      aria-labelledby={headingId}
      className="col-span-12 flex items-center gap-4 rounded-xl border border-border bg-card p-5 shadow-xs sm:col-span-6 xl:col-span-3"
    >
      <span
        aria-hidden="true"
        className="flex size-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary [&_svg]:size-5"
      >
        {icon}
      </span>
      <div className="flex min-w-0 flex-1 flex-col gap-0.5">
        {query.isLoading ? (
          <WidgetSkeleton variant="kpi" />
        ) : query.isError ? (
          <div role="alert" className="flex items-center gap-2">
            <TriangleAlert
              aria-hidden="true"
              className="size-4 shrink-0 text-destructive"
            />
            <span className="text-sm text-destructive">
              <FormattedMessage id="dashboard.kpi.error" />
            </span>
            <Button
              variant="ghost"
              mode="icon"
              size="sm"
              onClick={() => void query.refetch()}
              aria-label={intl.formatMessage({ id: 'common.retry' })}
            >
              <RotateCw aria-hidden="true" />
            </Button>
          </div>
        ) : (
          <span className="text-2xl font-semibold tabular-nums tracking-tight text-foreground">
            <FormattedNumber value={query.data ?? 0} />
          </span>
        )}
        <h2
          id={headingId}
          className="truncate text-xs font-medium text-muted-foreground"
        >
          <FormattedMessage id={labelId} />
        </h2>
      </div>
    </section>
  );
}

export function KpiCards() {
  const { user } = useAuth();
  const canUsers = usePermission('users.read');
  const canRoles = usePermission('roles.read');
  const canTenants = usePermission('tenants.manage');
  // Host context = no `tenant` claim on the session (`MeDto.tenantId == null`).
  const isHost = user?.tenantId == null;
  const showTenants = isHost && canTenants;

  const users = useUserCount(canUsers);
  const roles = useRoleCount(canRoles);
  const tenants = useTenantCount(showTenants);
  // Unread needs authentication only, which RequireAuth guarantees on this
  // page — so the band always keeps at least this tile.
  const unread = useUnreadNotifications(true);

  return (
    <div className="col-span-12 grid grid-cols-12 gap-4">
      {canUsers ? (
        <KpiCard labelId="dashboard.kpi.users" icon={<Users />} query={users} />
      ) : null}
      {canRoles ? (
        <KpiCard
          labelId="dashboard.kpi.roles"
          icon={<ShieldCheck />}
          query={roles}
        />
      ) : null}
      {showTenants ? (
        <KpiCard
          labelId="dashboard.kpi.tenants"
          icon={<Building />}
          query={tenants}
        />
      ) : null}
      <KpiCard labelId="dashboard.kpi.unread" icon={<Bell />} query={unread} />
    </div>
  );
}
