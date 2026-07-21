import { useMemo, useState } from 'react';
import {
  ColumnDef,
  getCoreRowModel,
  getPaginationRowModel,
  PaginationState,
  useReactTable,
} from '@tanstack/react-table';
import { Building2, EllipsisVertical, Plus, Power, PowerOff } from 'lucide-react';
import { Helmet } from 'react-helmet-async';
import { FormattedMessage, useIntl } from 'react-intl';
import { Can } from '@/auth/rbac';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import {
  DataEmpty,
  DataError,
  TableSkeleton,
} from '@/components/common/data-state';
import { PageHeader } from '@/components/common/page-header';
import { DataGrid, DataGridContainer } from '@/components/ui/data-grid';
import { DataGridColumnHeader } from '@/components/ui/data-grid-column-header';
import { DataGridPagination } from '@/components/ui/data-grid-pagination';
import { DataGridTable } from '@/components/ui/data-grid-table';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { CreateTenantDialog } from '../components/create-tenant-dialog';
import { useActivateTenant, useDeactivateTenant, useTenants } from '../hooks';
import { TENANTS_MANAGE, type TenantDto } from '../types';

/**
 * Tenants list (U-01, flow 3) — host-only tenant management.
 *
 * RBAC (triple lock): the route sits behind
 * `<RequireAuth permission="tenants.manage">`, every write is additionally
 * wrapped in `<Can permission="tenants.manage">`, and the backend enforces the
 * same key with a class-level `@PreAuthorize` on `TenantController`. Because
 * `tenants.manage` is `Side.HOST`, a tenant-side operator can never hold it and
 * never sees this screen or its sidebar entry.
 *
 * `GET /api/tenants` is UNPAGED (returns `List<TenantDto>`), so pagination is
 * client-side here — unlike the editions grid, which is server-paged.
 *
 * The backend exposes no update and no delete for tenants, so the row menu
 * offers only activate/deactivate and states the missing edit explicitly
 * instead of leaving an operator hunting for a control that does not exist.
 */
export function TenantsListPage() {
  const intl = useIntl();

  const [pagination, setPagination] = useState<PaginationState>({
    pageIndex: 0,
    pageSize: 10,
  });
  const [createOpen, setCreateOpen] = useState(false);

  const { data, isLoading, isError, refetch } = useTenants();
  const activateTenant = useActivateTenant();
  const deactivateTenant = useDeactivateTenant();

  const tenants = useMemo(() => data ?? [], [data]);

  const toggleActive = (tenant: TenantDto) => {
    if (tenant.id === undefined) {
      return;
    }
    if (tenant.active) {
      deactivateTenant.mutate(tenant.id);
    } else {
      activateTenant.mutate(tenant.id);
    }
  };

  const columns = useMemo<ColumnDef<TenantDto>[]>(
    () => [
      {
        id: 'name',
        accessorKey: 'name',
        header: ({ column }) => (
          <DataGridColumnHeader
            column={column}
            title={intl.formatMessage({ id: 'tenants.columns.name' })}
          />
        ),
        cell: ({ row }) => (
          <span className="font-medium text-foreground">
            {row.original.name}
          </span>
        ),
        enableSorting: false,
        size: 180,
      },
      {
        id: 'displayName',
        accessorKey: 'displayName',
        header: ({ column }) => (
          <DataGridColumnHeader
            column={column}
            title={intl.formatMessage({ id: 'tenants.columns.displayName' })}
          />
        ),
        enableSorting: false,
        size: 220,
      },
      {
        id: 'createdAt',
        accessorKey: 'createdAt',
        header: ({ column }) => (
          <DataGridColumnHeader
            column={column}
            title={intl.formatMessage({ id: 'tenants.columns.createdAt' })}
          />
        ),
        cell: ({ row }) => {
          const value = row.original.createdAt;
          if (!value) {
            return <span className="text-muted-foreground">—</span>;
          }
          const parsed = new Date(value);
          if (Number.isNaN(parsed.getTime())) {
            // Never let an unexpected wire format take the whole grid down.
            return <span className="text-muted-foreground">{value}</span>;
          }
          return (
            <span className="text-muted-foreground">
              {intl.formatDate(parsed, {
                year: 'numeric',
                month: 'short',
                day: 'numeric',
              })}
            </span>
          );
        },
        enableSorting: false,
        size: 140,
      },
      {
        id: 'active',
        accessorKey: 'active',
        header: ({ column }) => (
          <DataGridColumnHeader
            column={column}
            title={intl.formatMessage({ id: 'tenants.columns.active' })}
          />
        ),
        cell: ({ row }) => (
          <Badge
            variant={row.original.active ? 'success' : 'secondary'}
            appearance="light"
          >
            <FormattedMessage
              id={
                row.original.active
                  ? 'tenants.badge.active'
                  : 'tenants.badge.inactive'
              }
            />
          </Badge>
        ),
        enableSorting: false,
        size: 110,
      },
      {
        id: 'actions',
        header: '',
        cell: ({ row }) => (
          <TenantRowActions tenant={row.original} onToggleActive={toggleActive} />
        ),
        enableSorting: false,
        size: 60,
      },
    ],
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [intl],
  );

  const table = useReactTable({
    columns,
    data: tenants,
    getRowId: (row, index) => String(row.id ?? index),
    state: { pagination },
    onPaginationChange: setPagination,
    getCoreRowModel: getCoreRowModel(),
    // Client-side paging: the endpoint has no Pageable.
    getPaginationRowModel: getPaginationRowModel(),
  });

  return (
    <div className="container-fluid">
      <Helmet>
        <title>{intl.formatMessage({ id: 'tenants.list.title' })}</title>
      </Helmet>

      <PageHeader
        title={<FormattedMessage id="tenants.list.title" />}
        description={<FormattedMessage id="tenants.list.description" />}
        actions={
          <Can permission={TENANTS_MANAGE}>
            <Button onClick={() => setCreateOpen(true)}>
              <Plus />
              <FormattedMessage id="tenants.list.create" />
            </Button>
          </Can>
        }
      />

      <Card>
        {isError ? (
          <div className="p-5">
            <DataError
              message={intl.formatMessage({ id: 'tenants.list.error' })}
              onRetry={() => refetch()}
            />
          </div>
        ) : isLoading ? (
          <div className="p-5">
            <TableSkeleton rows={pagination.pageSize} cols={columns.length} />
          </div>
        ) : tenants.length === 0 ? (
          <DataEmpty
            icon={<Building2 />}
            title={intl.formatMessage({ id: 'tenants.list.empty' })}
            action={
              <Can permission={TENANTS_MANAGE}>
                <Button onClick={() => setCreateOpen(true)}>
                  <Plus />
                  <FormattedMessage id="tenants.list.create" />
                </Button>
              </Can>
            }
          />
        ) : (
          <div className="flex flex-col gap-4 p-5">
            <DataGrid table={table} recordCount={tenants.length}>
              <DataGridContainer>
                <DataGridTable />
              </DataGridContainer>
              <DataGridPagination />
            </DataGrid>
          </div>
        )}
      </Card>

      <CreateTenantDialog open={createOpen} onOpenChange={setCreateOpen} />
    </div>
  );
}

interface TenantRowActionsProps {
  tenant: TenantDto;
  onToggleActive: (tenant: TenantDto) => void;
}

function TenantRowActions({ tenant, onToggleActive }: TenantRowActionsProps) {
  const intl = useIntl();

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          variant="ghost"
          mode="icon"
          size="sm"
          aria-label={intl.formatMessage({ id: 'tenants.actions.menu' })}
        >
          <EllipsisVertical />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" side="bottom">
        <Can permission={TENANTS_MANAGE}>
          <DropdownMenuItem onSelect={() => onToggleActive(tenant)}>
            {tenant.active ? <PowerOff /> : <Power />}
            <FormattedMessage
              id={
                tenant.active
                  ? 'tenants.actions.deactivate'
                  : 'tenants.actions.activate'
              }
            />
          </DropdownMenuItem>
          <DropdownMenuSeparator />
          {/* There is no PUT /api/tenants/{id}: renaming is impossible, and an
              operator deserves to be told that rather than left searching. */}
          <DropdownMenuLabel className="text-xs font-normal text-muted-foreground">
            <FormattedMessage id="tenants.actions.noEdit" />
          </DropdownMenuLabel>
        </Can>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
