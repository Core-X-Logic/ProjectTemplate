import type { components } from '@/api/schema';

/**
 * Audit feature types (FRONTEND-ARCHITECTURE.md §7).
 *
 * DTO shapes are aliased from the generated OpenAPI schema (`src/api/schema.d.ts`,
 * regenerated via `npm run gen:api`) so the feature stays in lock-step with the
 * backend contract. Never re-declare these by hand.
 */

/** A single audit-log row (`GET /api/audit-logs`). */
export type AuditLogDto = components['schemas']['AuditLogDto'];
export type PageAuditLogDto = components['schemas']['PageAuditLogDto'];

/** An entity change with its property-level diff (`GET /api/entity-changes`). */
export type EntityChangeDto = components['schemas']['EntityChangeDto'];
export type EntityPropertyChangeDto =
  components['schemas']['EntityPropertyChangeDto'];
export type PageEntityChangeDto = components['schemas']['PageEntityChangeDto'];

/**
 * Audit-log list parameters. Spring binds `page`/`size`/`sort` into a `Pageable`
 * and the remaining fields are discrete query params (see `list_3` in the schema).
 */
export interface AuditLogListParams {
  /** Zero-based page index. */
  page?: number;
  /** Page size. */
  size?: number;
  /** Spring sort expression, e.g. `executionTime,desc`. */
  sort?: string;
  /** Free-text filter over the acting username. */
  userName?: string;
  /** Inclusive lower bound (ISO-8601 date-time). */
  startDate?: string;
  /** Inclusive upper bound (ISO-8601 date-time). */
  endDate?: string;
  /** Exact HTTP status code filter (e.g. `500`). */
  httpStatus?: number;
  /** Minimum execution duration in milliseconds. */
  minDuration?: number;
}

/**
 * Export parameters — the same filter set as the list, minus pagination/sort
 * (the export streams every matching row, see `export_1` in the schema).
 */
export type AuditLogExportParams = Omit<
  AuditLogListParams,
  'page' | 'size' | 'sort'
>;

/** Entity-change list parameters (`entityChanges` in the schema). */
export interface EntityChangeListParams {
  /** Zero-based page index. */
  page?: number;
  /** Page size. */
  size?: number;
  /** Filter by the JPA entity type name (e.g. `User`). */
  entityTypeName?: string;
  /** Filter by the entity primary key (string form). */
  entityId?: string;
}

/** Normalized change kinds surfaced by the entity-history badge. */
export type EntityChangeType = 'CREATED' | 'UPDATED' | 'DELETED';
