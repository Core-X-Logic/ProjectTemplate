import { apiFetch } from '@/api/client';
import type {
  AuditLogExportParams,
  AuditLogListParams,
  EntityChangeListParams,
  PageAuditLogDto,
  PageEntityChangeDto,
} from '@/features/audit/types';

/**
 * Typed endpoint wrappers for the audit feature (FRONTEND-ARCHITECTURE.md §7).
 *
 * Every shape mirrors `src/api/schema.d.ts` (`list_3`, `export_1`,
 * `entityChanges`). Transport (auth header, tenant header, `Accept-Language`,
 * 401 refresh, ProblemDetail parsing) is handled by `apiFetch`; these functions
 * only assemble the query string.
 */

const AUDIT_LOGS_URL = '/api/audit-logs';
const ENTITY_CHANGES_URL = '/api/entity-changes';

const XLSX_MIME =
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';

/** Append the shared audit-log filter fields (skips empty/undefined values). */
function appendAuditFilters(
  query: URLSearchParams,
  params: AuditLogExportParams,
): void {
  if (params.userName?.trim()) {
    query.set('userName', params.userName.trim());
  }
  if (params.startDate) {
    query.set('startDate', params.startDate);
  }
  if (params.endDate) {
    query.set('endDate', params.endDate);
  }
  if (params.httpStatus !== undefined) {
    query.set('httpStatus', String(params.httpStatus));
  }
  if (params.minDuration !== undefined) {
    query.set('minDuration', String(params.minDuration));
  }
}

/** `GET /api/audit-logs` — server-side pagination + sort (`auditlogs.read`). */
export function listAuditLogs(
  params: AuditLogListParams = {},
): Promise<PageAuditLogDto> {
  const query = new URLSearchParams();
  if (params.page !== undefined) {
    query.set('page', String(params.page));
  }
  if (params.size !== undefined) {
    query.set('size', String(params.size));
  }
  if (params.sort) {
    query.set('sort', params.sort);
  }
  appendAuditFilters(query, params);
  const qs = query.toString();
  return apiFetch<PageAuditLogDto>(qs ? `${AUDIT_LOGS_URL}?${qs}` : AUDIT_LOGS_URL);
}

/**
 * `GET /api/audit-logs/export` — XLSX download (`auditlogs.read`).
 *
 * Reuses `apiFetch`'s `responseType: 'blob'` mode so the binary export shares
 * the same auth/tenant/locale headers and single-flight 401 refresh as every
 * other call instead of a hand-rolled `fetch`.
 */
export function exportAuditLogs(
  params: AuditLogExportParams = {},
): Promise<Blob> {
  const query = new URLSearchParams();
  appendAuditFilters(query, params);
  const qs = query.toString();
  return apiFetch<Blob>(
    qs ? `${AUDIT_LOGS_URL}/export?${qs}` : `${AUDIT_LOGS_URL}/export`,
    { headers: { Accept: XLSX_MIME } },
    { responseType: 'blob' },
  );
}

/** `GET /api/entity-changes` — server-side paginated change log (`auditlogs.read`). */
export function listEntityChanges(
  params: EntityChangeListParams = {},
): Promise<PageEntityChangeDto> {
  const query = new URLSearchParams();
  if (params.page !== undefined) {
    query.set('page', String(params.page));
  }
  if (params.size !== undefined) {
    query.set('size', String(params.size));
  }
  if (params.entityTypeName?.trim()) {
    query.set('entityTypeName', params.entityTypeName.trim());
  }
  if (params.entityId?.trim()) {
    query.set('entityId', params.entityId.trim());
  }
  const qs = query.toString();
  return apiFetch<PageEntityChangeDto>(
    qs ? `${ENTITY_CHANGES_URL}?${qs}` : ENTITY_CHANGES_URL,
  );
}
