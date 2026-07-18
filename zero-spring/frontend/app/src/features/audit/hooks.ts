import {
  keepPreviousData,
  useMutation,
  useQuery,
} from '@tanstack/react-query';
import { useIntl } from 'react-intl';
import { toast } from 'sonner';
import { ApiError } from '@/api/client';
import {
  exportAuditLogs,
  listAuditLogs,
  listEntityChanges,
} from '@/features/audit/api';
import type {
  AuditLogExportParams,
  AuditLogListParams,
  EntityChangeListParams,
} from '@/features/audit/types';

/**
 * TanStack Query bindings for the audit feature (FRONTEND-ARCHITECTURE.md §7).
 *
 * The audit surface is read-only, so there is nothing to invalidate: the list
 * queries keep the previous page on screen while a new one loads
 * (`keepPreviousData`) for a flicker-free server-paginated grid, and the export
 * is modelled as a mutation purely so the caller controls the download side
 * effect and gets a localized success/error toast.
 */

export const auditKeys = {
  all: ['audit'] as const,
  logs: (params: AuditLogListParams) =>
    [...auditKeys.all, 'logs', params] as const,
  entityChanges: (params: EntityChangeListParams) =>
    [...auditKeys.all, 'entity-changes', params] as const,
};

/** Server-paged audit-log list; the previous page stays while fetching. */
export function useAuditLogs(params: AuditLogListParams = {}) {
  return useQuery({
    queryKey: auditKeys.logs(params),
    queryFn: () => listAuditLogs(params),
    placeholderData: keepPreviousData,
  });
}

/** Server-paged entity-change list; the previous page stays while fetching. */
export function useEntityChanges(params: EntityChangeListParams = {}) {
  return useQuery({
    queryKey: auditKeys.entityChanges(params),
    queryFn: () => listEntityChanges(params),
    placeholderData: keepPreviousData,
  });
}

/**
 * XLSX export. The caller receives the blob and triggers the download; on
 * failure the ProblemDetail `detail` is surfaced under a localized toast.
 */
export function useExportAuditLogs() {
  const intl = useIntl();
  return useMutation<Blob, unknown, AuditLogExportParams>({
    mutationFn: (params) => exportAuditLogs(params),
    onSuccess: () => {
      toast.success(intl.formatMessage({ id: 'audit.exported' }));
    },
    onError: (error) => {
      toast.error(intl.formatMessage({ id: 'audit.error' }), {
        description: error instanceof ApiError ? error.detail : undefined,
      });
    },
  });
}
