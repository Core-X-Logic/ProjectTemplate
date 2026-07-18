import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  ColumnDef,
  ExpandedState,
  getCoreRowModel,
  getExpandedRowModel,
  PaginationState,
  useReactTable,
} from '@tanstack/react-table';
import { ChevronDown, ChevronRight, MoveRight } from 'lucide-react';
import { FormattedMessage, useIntl } from 'react-intl';
import { Helmet } from 'react-helmet-async';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Card,
  CardDescription,
  CardHeader,
  CardHeading,
  CardTable,
  CardTitle,
} from '@/components/ui/card';
import { DataGrid, DataGridContainer } from '@/components/ui/data-grid';
import { DataGridPagination } from '@/components/ui/data-grid-pagination';
import { DataGridTable } from '@/components/ui/data-grid-table';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { useEntityChanges } from '@/features/audit/hooks';
import type {
  EntityChangeDto,
  EntityChangeListParams,
  EntityChangeType,
  EntityPropertyChangeDto,
} from '@/features/audit/types';

const FILTER_DEBOUNCE_MS = 300;
const DEFAULT_PAGE_SIZE = 25;

const CHANGE_TYPE_VARIANT: Record<
  EntityChangeType,
  'success' | 'info' | 'destructive'
> = {
  CREATED: 'success',
  UPDATED: 'info',
  DELETED: 'destructive',
};

const CHANGE_TYPE_MESSAGE: Record<EntityChangeType, string> = {
  CREATED: 'audit.entityHistory.changeType.created',
  UPDATED: 'audit.entityHistory.changeType.updated',
  DELETED: 'audit.entityHistory.changeType.deleted',
};

function normalizeChangeType(value?: string): EntityChangeType | undefined {
  const upper = value?.toUpperCase();
  if (upper === 'CREATED' || upper === 'UPDATED' || upper === 'DELETED') {
    return upper;
  }
  return undefined;
}

const cellSkeleton = <Skeleton className="h-5 w-full max-w-[120px]" />;

/** Expanded-row detail: the property-level diff for one entity change. */
function PropertyChangesTable({
  changes,
}: {
  changes?: EntityPropertyChangeDto[];
}) {
  if (!changes || changes.length === 0) {
    return (
      <p className="px-4 py-3 text-sm text-muted-foreground">
        <FormattedMessage id="audit.entityHistory.noPropertyChanges" />
      </p>
    );
  }

  return (
    <div className="bg-muted/30 px-4 py-3">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead className="w-1/3">
              <FormattedMessage id="audit.entityHistory.property.name" />
            </TableHead>
            <TableHead>
              <FormattedMessage id="audit.entityHistory.property.original" />
            </TableHead>
            <TableHead>
              <FormattedMessage id="audit.entityHistory.property.new" />
            </TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {changes.map((change, index) => (
            <TableRow key={change.propertyName ?? index}>
              <TableCell className="font-medium text-foreground">
                {change.propertyName ?? '—'}
              </TableCell>
              <TableCell>
                <span className="text-muted-foreground line-through">
                  {change.originalValue ?? '—'}
                </span>
              </TableCell>
              <TableCell>
                <div className="flex items-center gap-2">
                  <MoveRight
                    className="size-3.5 text-muted-foreground"
                    aria-hidden
                  />
                  <span className="text-foreground">
                    {change.newValue ?? '—'}
                  </span>
                </div>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}

/**
 * Entity history — server-paged change log over `GET /api/entity-changes`.
 * Each row expands to reveal its property-level diff (original → new).
 *
 * RBAC: the route is guarded by `auditlogs.read` (routes.tsx, wired by the
 * integration step). The screen is read-only, so it carries no in-page actions.
 */
export function EntityHistoryPage() {
  const intl = useIntl();

  const [pagination, setPagination] = useState<PaginationState>({
    pageIndex: 0,
    pageSize: DEFAULT_PAGE_SIZE,
  });
  const [expanded, setExpanded] = useState<ExpandedState>({});

  const [entityTypeInput, setEntityTypeInput] = useState('');
  const [entityIdInput, setEntityIdInput] = useState('');
  const [entityTypeName, setEntityTypeName] = useState('');
  const [entityId, setEntityId] = useState('');

  const resetToFirstPage = useCallback(() => {
    setPagination((current) =>
      current.pageIndex === 0 ? current : { ...current, pageIndex: 0 },
    );
  }, []);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      setEntityTypeName(entityTypeInput);
      setEntityId(entityIdInput);
      resetToFirstPage();
    }, FILTER_DEBOUNCE_MS);
    return () => window.clearTimeout(timer);
  }, [entityTypeInput, entityIdInput, resetToFirstPage]);

  const params = useMemo<EntityChangeListParams>(
    () => ({
      page: pagination.pageIndex,
      size: pagination.pageSize,
      entityTypeName: entityTypeName.trim() || undefined,
      entityId: entityId.trim() || undefined,
    }),
    [pagination, entityTypeName, entityId],
  );

  const { data, isLoading, isError } = useEntityChanges(params);

  const rows = useMemo(() => data?.content ?? [], [data]);
  const recordCount = data?.totalElements ?? 0;

  const columns = useMemo<ColumnDef<EntityChangeDto>[]>(
    () => [
      {
        id: 'expander',
        header: () => null,
        cell: ({ row }) => (
          <Button
            variant="ghost"
            mode="icon"
            size="sm"
            aria-label={intl.formatMessage({
              id: 'audit.entityHistory.expand',
            })}
            aria-expanded={row.getIsExpanded()}
            onClick={row.getToggleExpandedHandler()}
          >
            {row.getIsExpanded() ? (
              <ChevronDown className="size-4" />
            ) : (
              <ChevronRight className="size-4" />
            )}
          </Button>
        ),
        enableSorting: false,
        size: 48,
        meta: {
          skeleton: <Skeleton className="size-7 rounded-md" />,
          expandedContent: (row: EntityChangeDto) => (
            <PropertyChangesTable changes={row.propertyChanges} />
          ),
        },
      },
      {
        id: 'entityTypeName',
        accessorKey: 'entityTypeName',
        header: () => (
          <FormattedMessage id="audit.entityHistory.column.entityType" />
        ),
        cell: ({ row }) => (
          <span className="font-medium text-foreground">
            {row.original.entityTypeName ?? '—'}
          </span>
        ),
        size: 180,
        meta: { skeleton: cellSkeleton },
      },
      {
        id: 'entityId',
        accessorKey: 'entityId',
        header: () => (
          <FormattedMessage id="audit.entityHistory.column.entityId" />
        ),
        cell: ({ row }) => (
          <span className="font-mono text-xs text-muted-foreground">
            {row.original.entityId ?? '—'}
          </span>
        ),
        size: 140,
        meta: { skeleton: cellSkeleton },
      },
      {
        id: 'changeType',
        accessorKey: 'changeType',
        header: () => (
          <FormattedMessage id="audit.entityHistory.column.changeType" />
        ),
        cell: ({ row }) => {
          const normalized = normalizeChangeType(row.original.changeType);
          if (!normalized) {
            return (
              <Badge variant="secondary" appearance="light" size="sm">
                {row.original.changeType ?? '—'}
              </Badge>
            );
          }
          return (
            <Badge
              variant={CHANGE_TYPE_VARIANT[normalized]}
              appearance="light"
              size="sm"
            >
              <FormattedMessage id={CHANGE_TYPE_MESSAGE[normalized]} />
            </Badge>
          );
        },
        size: 130,
        meta: { skeleton: cellSkeleton },
      },
      {
        id: 'changeTime',
        accessorKey: 'changeTime',
        header: () => (
          <FormattedMessage id="audit.entityHistory.column.changeTime" />
        ),
        cell: ({ row }) =>
          row.original.changeTime ? (
            <span className="whitespace-nowrap text-muted-foreground">
              {intl.formatDate(new Date(row.original.changeTime), {
                dateStyle: 'medium',
                timeStyle: 'short',
              })}
            </span>
          ) : (
            <span className="text-muted-foreground">—</span>
          ),
        size: 180,
        meta: { skeleton: cellSkeleton },
      },
      {
        id: 'userId',
        accessorKey: 'userId',
        header: () => (
          <FormattedMessage id="audit.entityHistory.column.userId" />
        ),
        cell: ({ row }) => (
          <span className="text-muted-foreground">
            {row.original.userId ?? '—'}
          </span>
        ),
        size: 100,
        meta: { skeleton: cellSkeleton },
      },
    ],
    [intl],
  );

  const table = useReactTable({
    data: rows,
    columns,
    pageCount: data?.totalPages ?? -1,
    getRowId: (row, index) => String(row.id ?? index),
    state: { pagination, expanded },
    onPaginationChange: setPagination,
    onExpandedChange: setExpanded,
    getRowCanExpand: () => true,
    manualPagination: true,
    getCoreRowModel: getCoreRowModel(),
    getExpandedRowModel: getExpandedRowModel(),
  });

  return (
    <div className="container-fluid">
      <Helmet>
        <title>{intl.formatMessage({ id: 'audit.entityHistory.title' })}</title>
      </Helmet>

      <DataGrid
        table={table}
        recordCount={recordCount}
        isLoading={isLoading}
        emptyMessage={intl.formatMessage({ id: 'audit.entityHistory.empty' })}
      >
        <Card>
          <CardHeader className="flex-wrap gap-2 py-4">
            <CardHeading>
              <CardTitle>
                <FormattedMessage id="audit.entityHistory.title" />
              </CardTitle>
              <CardDescription>
                <FormattedMessage id="audit.entityHistory.subtitle" />
              </CardDescription>
            </CardHeading>
          </CardHeader>

          {/* Filter panel */}
          <div className="flex flex-wrap items-end gap-3 border-b border-border px-5 py-4">
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="entity-filter-type">
                <FormattedMessage id="audit.entityHistory.filter.entityType" />
              </Label>
              <Input
                id="entity-filter-type"
                className="w-48"
                value={entityTypeInput}
                onChange={(event) => setEntityTypeInput(event.target.value)}
                placeholder={intl.formatMessage({
                  id: 'audit.entityHistory.filter.entityTypePlaceholder',
                })}
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="entity-filter-id">
                <FormattedMessage id="audit.entityHistory.filter.entityId" />
              </Label>
              <Input
                id="entity-filter-id"
                className="w-40"
                value={entityIdInput}
                onChange={(event) => setEntityIdInput(event.target.value)}
                placeholder={intl.formatMessage({
                  id: 'audit.entityHistory.filter.entityIdPlaceholder',
                })}
              />
            </div>
          </div>

          {isError ? (
            <p
              role="alert"
              className="px-5 py-14 text-center text-sm text-destructive"
            >
              <FormattedMessage id="audit.entityHistory.error" />
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
