import { apiFetch } from '@/api/client';
import type {
  NotificationListParams,
  NotificationPage,
  UnreadCountResponse,
} from './types';

/**
 * Typed endpoint wrappers for `/api/notifications*` (FRONTEND-ARCHITECTURE.md §7).
 *
 * Every endpoint is user-scoped on the backend (`isAuthenticated()` only) — no
 * extra permission is required beyond a live session.
 */

function toSearchParams(params: NotificationListParams): string {
  const search = new URLSearchParams();
  if (params.page !== undefined) {
    search.set('page', String(params.page));
  }
  if (params.size !== undefined) {
    search.set('size', String(params.size));
  }
  for (const clause of params.sort ?? []) {
    search.append('sort', clause);
  }
  const qs = search.toString();
  return qs ? `?${qs}` : '';
}

/** `GET /api/notifications` — paged inbox for the current user. */
export function listNotifications(
  params: NotificationListParams = {},
): Promise<NotificationPage> {
  return apiFetch<NotificationPage>(
    `/api/notifications${toSearchParams(params)}`,
  );
}

/** `GET /api/notifications/unread-count` — `{ count }` for the bell badge. */
export function getUnreadCount(): Promise<UnreadCountResponse> {
  return apiFetch<UnreadCountResponse>('/api/notifications/unread-count');
}

/** `PUT /api/notifications/{id}/read` — mark a single notification as read. */
export function markRead(id: number): Promise<void> {
  return apiFetch<void>(`/api/notifications/${id}/read`, { method: 'PUT' });
}

/** `PUT /api/notifications/read-all` — mark every unread notification as read. */
export function markAllRead(): Promise<void> {
  return apiFetch<void>('/api/notifications/read-all', { method: 'PUT' });
}
