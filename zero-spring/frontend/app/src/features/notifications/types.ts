import type { components } from '@/api/schema';

/**
 * Notification feature types — aliases over the generated OpenAPI schema
 * (`src/api/schema.d.ts`, FRONTEND-ARCHITECTURE.md §7) so call sites never
 * depend on the generated module shape directly.
 */

export type NotificationDto = components['schemas']['NotificationDto'];
export type NotificationPage = components['schemas']['PageNotificationDto'];

/** Severity of a notification (`INFO | SUCCESS | WARNING | ERROR`). */
export type NotificationLevel = NonNullable<NotificationDto['level']>;

/**
 * `GET /api/notifications/unread-count` body. The backend returns
 * `Map.of("count", n)`; the generated type is an open index signature, so the
 * known key is narrowed here (kept optional to stay honest with the schema).
 */
export interface UnreadCountResponse {
  count?: number;
}

/** Spring `Pageable` request params (`page` is 0-based). */
export interface NotificationListParams {
  page?: number;
  size?: number;
  /** Sort clauses in Spring syntax, e.g. `createdAt,desc`. */
  sort?: string[];
}
