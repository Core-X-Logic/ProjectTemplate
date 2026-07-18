import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  ColumnDef,
  getCoreRowModel,
  PaginationState,
  SortingState,
  useReactTable,
} from '@tanstack/react-table';
import { Calendar as CalendarIcon, Download, X } from 'lucide-react';
import { FormattedMessage, useIntl } from 'react-intl';
import { Helmet } from 'react-helmet-async';
import { cn } from '@/lib/utils';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Calendar } from '@/components/ui/calendar';
import {
  Card,
  CardDescription,
  CardHeader,
  CardHeading,
  CardTable,
  CardTitle,
  CardToolbar,
} from '@/components/ui/card';
import { DataGrid, DataGridContainer } from '@/components/ui/data-grid';
import { DataGridColumnHeader } from '@/components/ui/data-grid-column-header';
import { DataGridPagination } from '@/components/ui/data-grid-pagination';
import { DataGridTable } from '@/components/ui/data-grid-table';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '@/components/ui/popover';
import { Skeleton } from '@/components/ui/skeleton';
import { Can } from '@/auth/rbac';
import { useAuditLogs, useExportAuditLogs } from '@/features/audit/hooks';
import type {
  AuditLogDto,
  AuditLogExportParams,
  AuditLogListParams,
} from '@/features/audit/types';

const FILTER_DEBOUNCE_MS = 300;
const DEFAULT_PAGE_SIZE = 25;

/** Trigger a browser download for a fetched blob (shared with the users export). */
function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}

function startOfDayIso(date: Date): string {
  const d = new Date(date);
  d.setHours(0, 0, 0, 0);
  return d.toISOString();
}

function endOfDayIso(date: Date): string {
  const d = new Date(date);
  d.setHours(23, 59, 59, 999);
  return d.toISOString();
}

/** Map an HTTP status code to a badge variant: 2xx→green, 4xx→amber, 5xx→red. */
function statusVariant(
  code?: number,
): 'success' | 'warning' | 'destructive' | 'secondary' {
  if (code === undefined) {
    return 'secondary';
  }
  if (code >= 200 && code < 300) {
    return 'success';
  }
  if (code >= 400 && code < 500) {
    return 'warning';
  }
  if (code >= 500) {
    return 'destructive';
  }
  return 'secondary';
}

const cellSkeleton = <Skeleton className="h-5 w-full max-w-[120px]" />;

/**
 * Audit-log list — server-paged, server-sorted data-grid over
 * `GET /api/audit-logs`. Sorting and pagination are pushed to the backend
 * (`manualPagination` + `manualSorting`) so the grid never sorts large result
 * sets client-side.
 *
 * RBAC: the route is guarded by `auditlogs.read` (routes.tsx, wired by the
 * integration step); the read-only Export action is additionally `<Can>`-gated
 * as a double lock with the backend `@PreAuthorize`.
 */
export function AuditLogsPage() {
  const intl = useIntl();

  const [pagination, setPagination] = useState<PaginationState>({
    pageIndex: 0,
    pageSize: DEFAULT_PAGE_SIZE,
  });
  const [sorting, setSorting] = useState<SortingState>([
    { id: 'executionTime', desc: true },
  ]);

  // Raw inputs (immediate) vs. committed filter values (debounced).
  const [userNameInput, setUserNameInput] = useState('');
  const [httpStatusInput, setHttpStatusInput] = useState('');
  const [userName, setUserName] = useState('');
  const [httpStatus, setHttpStatus] = useState<number | undefined>(undefined);
  const [startDate, setStartDate] = useState<Date | undefined>(undefined);
  const [endDate, setEndDate] = useState<Date | undefined>(undefined);

  const resetToFirstPage = useCallback(() => {
    setPagination((current) =>
      current.pageIndex === 0 ? current : { ...current, pageIndex: 0 },
    );
  }, []);

  // Debounce the free-text / numeric inputs; a new filter resets to page 1.
  useEffect(() => {
    const timer = window.setTimeout(() => {
      setUserName(userNameInput);
      const parsed = Number.parseInt(httpStatusInput, 10);
      setHttpStatus(Number.isNaN(parsed) ? undefined : parsed);
      resetToFirstPage();
    }, FILTER_DEBOUNCE_MS);
    return () => window.clearTimeout(timer);
  }, [userNameInput, httpStatusInput, resetToFirstPage]);

  const params = useMemo<AuditLogListParams>(
    () => ({
      page: pagination.pageIndex,
      size: pagination.pageSize,
      sort: sorting[0]
        ? `${sorting[0].id},${sorting[0].desc ? 'desc' : 'asc'}`
        : undefined,
      userName: userName.trim() || undefined,
      httpStatus,
      startDate: startDate ? startOfDayIso(startDate) : undefined,
      endDate: endDate ? endOfDayIso(endDate) : undefined,
    }),
    [pagination, sorting, userName, httpStatus, startDate, endDate],
  );

  const { data, isLoading, isError } = useAuditLogs(params);
  const exportAudit = useExportAuditLogs();

  const rows = useMemo(() => data?.content ?? [], [data]);
  const recordCount = data?.totalElements ?? 0;

  const exportParams: AuditLogExportParams = {
    userName: params.userName,
    httpStatus: params.httpStatus,
    startDate: params.startDate,
    endDate: params.endDate,
  };

  const hasActiveFilters =
    userNameInput !== '' ||
    httpStatusInput !== '' ||
    startDate !== undefined ||
    endDate !== undefined;

  const clearFilters = () => {
    setUserNameInput('');
    setHttpStatusInput('');
    setUserName('');
    setHttpStatus(undefined);
    setStartDate(undefined);
    setEndDate(undefined);
    resetToFirstPage();
  };

  const columns = useMemo<ColumnDef<AuditLogDto>[]>(
    () => [
      {
        id: 'executionTime',
        accessorKey: 'executionTime',
        header: ({ column }) => (
          <DataGridColumnHeader
            column={column}
            title={intl.formatMessage({ id: 'audit.column.executionTime' })}
          />
        ),
        cell: ({ row }) =>
          row.original.executionTime ? (
            <span className="text-foreground whitespace-nowrap">
              {intl.formatDate(new Date(row.original.executionTime), {
                dateStyle: 'medium',
                timeStyle: 'medium',
              })}
            </span>
          ) : (
            <span className="text-muted-foreground">—</span>
          ),
        enableSorting: true,
        size: 200,
        meta: { skeleton: cellSkeleton },
      },
      {
        id: 'username',
        accessorKey: 'username',
        header: ({ column }) => (
          <DataGridColumnHeader
            column={column}
            title={intl.formatMessage({ id: 'audit.column.username' })}
          />
        ),
        cell: ({ row }) => (
          <span className="font-medium text-foreground">
            {row.original.username ?? '—'}
          </span>
        ),
        enableSorting: true,
        size: 150,
        meta: { skeleton: cellSkeleton },
      },
      {
        id: 'serviceName',
        accessorKey: 'serviceName',
        header: ({ column }) => (
          <DataGridColumnHeader
            column={column}
            title={intl.formatMessage({ id: 'audit.column.serviceName' })}
          />
        ),
        cell: ({ row }) => (
          <span className="text-muted-foreground">
            {row.original.serviceName ?? '—'}
          </span>
        ),
        enableSorting: false,
        size: 180,
        meta: { skeleton: cellSkeleton },
      },
      {
        id: 'methodName',
        accessorKey: 'methodName',
        header: ({ column }) => (
          <DataGridColumnHeader
            column={column}
            title={intl.formatMessage({ id: 'audit.column.methodName' })}
          />
        ),
        cell: ({ row }) => (
          <span className="font-mono text-xs text-muted-foreground">
            {row.original.methodName ?? '—'}
          </span>
        ),
        enableSorting: false,
        size: 160,
        meta: { skeleton: cellSkeleton },
      },
      {
        id: 'httpMethod',
        accessorKey: 'httpMethod',
        header: ({ column }) => (
          <DataGridColumnHeader
            column={column}
            title={intl.formatMessage({ id: 'audit.column.httpMethod' })}
          />
        ),
        cell: ({ row }) =>
          row.original.httpMethod ? (
            <Badge variant="secondary" appearance="light" size="sm">
              {row.original.httpMethod}
            </Badge>
          ) : (
            <span className="text-muted-foreground">—</span>
          ),
        enableSorting: false,
        size: 90,
        meta: { skeleton: cellSkeleton },
      },
      {
        id: 'httpStatusCode',
        accessorKey: 'httpStatusCode',
        header: ({ column }) => (
          <DataGridColumnHeader
            column={column}
            title={intl.formatMessage({ id: 'audit.column.httpStatus' })}
          />
        ),
        cell: ({ row }) => {
          const code = row.original.httpStatusCode;
          return code !== undefined ? (
            <Badge
              variant={statusVariant(code)}
              appearance="light"
              size="sm"
            >
              {code}
            </Badge>
          ) : (
            <span className="text-muted-foreground">—</span>
          );
        },
        enableSorting: true,
        size: 100,
        meta: { skeleton: cellSkeleton },
      },
      {
        id: 'executionDurationMs',
        accessorKey: 'executionDurationMs',
        header: ({ column }) => (
          <DataGridColumnHeader
            column={column}
            title={intl.formatMessage({ id: 'audit.column.duration' })}
          />
        ),
        cell: ({ row }) =>
          row.original.executionDurationMs !== undefined ? (
            <span className="tabular-nums text-muted-foreground whitespace-nowrap">
              {intl.formatMessage(
                { id: 'audit.duration.ms' },
                { ms: row.original.executionDurationMs },
              )}
            </span>
          ) : (
            <span className="text-muted-foreground">—</span>
          ),
        enableSorting: true,
        size: 110,
        meta: { skeleton: cellSkeleton, cellClassName: 'text-end' },
      },
    ],
    [intl],
  );

  const table = useReactTable({
    data: rows,
    columns,
    pageCount: data?.totalPages ?? -1,
    getRowId: (row, index) => String(row.id ?? index),
    state: { pagination, sorting },
    onPaginationChange: setPagination,
    onSortingChange: setSorting,
    manualPagination: true,
    manualSorting: true,
    getCoreRowModel: getCoreRowModel(),
  });

  return (
    <div className="container-fluid">
      <Helmet>
        <title>{intl.formatMessage({ id: 'audit.logs.title' })}</title>
      </Helmet>

      <DataGrid
        table={table}
        recordCount={recordCount}
        isLoading={isLoading}
        emptyMessage={intl.formatMessage({ id: 'audit.empty' })}
      >
        <Card>
          <CardHeader className="flex-wrap gap-2 py-4">
            <CardHeading>
              <CardTitle>
                <FormattedMessage id="audit.logs.title" />
              </CardTitle>
              <CardDescription>
                <FormattedMessage id="audit.logs.subtitle" />
              </CardDescription>
            </CardHeading>
            <CardToolbar>
              <Can permission="auditlogs.read">
                <Button
                  variant="outline"
                  disabled={exportAudit.isPending}
                  onClick={() =>
                    exportAudit.mutate(exportParams, {
                      onSuccess: (blob) =>
                        downloadBlob(blob, 'audit-logs.xlsx'),
                    })
                  }
                >
                  <Download />
                  <FormattedMessage id="audit.action.export" />
                </Button>
              </Can>
            </CardToolbar>
          </CardHeader>

          {/* Filter panel */}
          <div className="flex flex-wrap items-end gap-3 border-b border-border px-5 py-4">
            <h3 className="w-full text-sm font-medium text-foreground">
              <FormattedMessage id="audit.filter.title" />
            </h3>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="audit-filter-username">
                <FormattedMessage id="audit.filter.userName" />
              </Label>
              <Input
                id="audit-filter-username"
                className="w-48"
                value={userNameInput}
                onChange={(event) => setUserNameInput(event.target.value)}
                placeholder={intl.formatMessage({
                  id: 'audit.filter.userNamePlaceholder',
                })}
              />
            </div>

            <div className="flex flex-col gap-1.5">
              <Label>
                <FormattedMessage id="audit.filter.startDate" />
              </Label>
              <Popover>
                <PopoverTrigger asChild>
                  <Button
                    variant="outline"
                    className={cn(
                      'w-44 justify-start text-start font-normal',
                      !startDate && 'text-muted-foreground',
                    )}
                  >
                    <CalendarIcon className="size-4" />
                    {startDate
                      ? intl.formatDate(startDate, { dateStyle: 'medium' })
                      : intl.formatMessage({ id: 'audit.filter.pickDate' })}
                  </Button>
                </PopoverTrigger>
                <PopoverContent className="w-auto p-0" align="start">
                  <Calendar
                    mode="single"
                    selected={startDate}
                    onSelect={(date) => {
                      setStartDate(date);
                      resetToFirstPage();
                    }}
                    autoFocus
                  />
                </PopoverContent>
              </Popover>
            </div>

            <div className="flex flex-col gap-1.5">
              <Label>
                <FormattedMessage id="audit.filter.endDate" />
              </Label>
              <Popover>
                <PopoverTrigger asChild>
                  <Button
                    variant="outline"
                    className={cn(
                      'w-44 justify-start text-start font-normal',
                      !endDate && 'text-muted-foreground',
                    )}
                  >
                    <CalendarIcon className="size-4" />
                    {endDate
                      ? intl.formatDate(endDate, { dateStyle: 'medium' })
                      : intl.formatMessage({ id: 'audit.filter.pickDate' })}
                  </Button>
                </PopoverTrigger>
                <PopoverContent className="w-auto p-0" align="start">
                  <Calendar
                    mode="single"
                    selected={endDate}
                    onSelect={(date) => {
                      setEndDate(date);
                      resetToFirstPage();
                    }}
                    autoFocus
                  />
                </PopoverContent>
              </Popover>
            </div>

            <div className="flex flex-col gap-1.5">
              <Label htmlFor="audit-filter-status">
                <FormattedMessage id="audit.filter.httpStatus" />
              </Label>
              <Input
                id="audit-filter-status"
                type="number"
                inputMode="numeric"
                className="w-28"
                value={httpStatusInput}
                onChange={(event) => setHttpStatusInput(event.target.value)}
                placeholder={intl.formatMessage({
                  id: 'audit.filter.httpStatusPlaceholder',
                })}
              />
            </div>

            {hasActiveFilters && (
              <Button
                variant="ghost"
                size="sm"
                onClick={clearFilters}
                className="mb-0.5"
              >
                <X className="size-4" />
                <FormattedMessage id="audit.filter.clear" />
              </Button>
            )}
          </div>

          {isError ? (
            <p
              role="alert"
              className="px-5 py-14 text-center text-sm text-destructive"
            >
              <FormattedMessage id="audit.error" />
            </p>
          ) : (
            <>
              <CardTable>
                <DataGridContainer border={false}>
                  <DataGridTable />
                </DataGridContainer>
              </CardTable>
              <div className="border-t border-border px-5 py-3">
                <DataGridPagination />
              </div>
            </>
          )}
        </Card>
      </DataGrid>
    </div>
  );
}
