import { useMemo, useState } from 'react';
import { Can } from '@/auth/rbac';
import {
  ColumnDef,
  getCoreRowModel,
  PaginationState,
  SortingState,
  useReactTable,
} from '@tanstack/react-table';
import { EllipsisVertical, Plus, Shield } from 'lucide-react';
import { Helmet } from 'react-helmet-async';
import { FormattedMessage, useIntl } from 'react-intl';
import { useNavigate } from 'react-router-dom';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
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
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { useCloneRole, useDeleteRole, useRoles } from '../hooks';
import type { RoleDto, RoleListParams } from '../types';

/**
 * Roles list (slice B) — server-paged data-grid over `GET /api/roles`.
 *
 * RBAC: the page itself sits behind `<RequireAuth permission="roles.read">`
 * (routes.tsx); create/edit/clone/delete actions are additionally hidden via
 * `<Can>` (double lock — backend `@PreAuthorize` enforces).
 *
 * i18n: the roles catalogue is merged into the global `i18n/messages/{en,tr}.ts`
 * and served by the app-level `I18nProvider`, so no feature-scoped provider is
 * needed here.
 */
export function RolesListPage() {
  return <RolesListContent />;
}

interface RoleRowActionsProps {
  role: RoleDto;
  onEdit: (role: RoleDto) => void;
  onClone: (role: RoleDto) => void;
  onDelete: (role: RoleDto) => void;
}

function RoleRowActions({
  role,
  onEdit,
  onClone,
  onDelete,
}: RoleRowActionsProps) {
  const intl = useIntl();

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          variant="ghost"
          size="sm"
          aria-label={intl.formatMessage({ id: 'roles.actions.menu' })}
        >
          <EllipsisVertical />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" side="bottom">
        <Can permission="roles.update">
          <DropdownMenuItem onClick={() => onEdit(role)}>
            <FormattedMessage id="roles.actions.edit" />
          </DropdownMenuItem>
        </Can>
        <Can permission="roles.create">
          <DropdownMenuItem onClick={() => onClone(role)}>
            <FormattedMessage id="roles.actions.clone" />
          </DropdownMenuItem>
        </Can>
        <Can permission="roles.delete">
          <DropdownMenuSeparator />
          <DropdownMenuItem
            disabled={role.isStatic}
            className="text-destructive focus:text-destructive"
            onClick={() => onDelete(role)}
          >
            <FormattedMessage id="roles.actions.delete" />
          </DropdownMenuItem>
        </Can>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

function RolesListContent() {
  const intl = useIntl();
  const navigate = useNavigate();

  const [pagination, setPagination] = useState<PaginationState>({
    pageIndex: 0,
    pageSize: 10,
  });
  const [sorting, setSorting] = useState<SortingState>([
    { id: 'name', desc: false },
  ]);

  const params = useMemo<RoleListParams>(
    () => ({
      page: pagination.pageIndex,
      size: pagination.pageSize,
      sort: sorting[0]
        ? `${sorting[0].id},${sorting[0].desc ? 'desc' : 'asc'}`
        : undefined,
    }),
    [pagination, sorting],
  );

  const { data, isLoading, isError, refetch } = useRoles(params);
  const deleteRole = useDeleteRole();
  const cloneRole = useCloneRole();

  const [roleToDelete, setRoleToDelete] = useState<RoleDto | null>(null);

  const roles = useMemo(() => data?.content ?? [], [data]);
  const recordCount = data?.totalElements ?? 0;

  const handleEdit = (role: RoleDto) => {
    if (role.id !== undefined) {
      navigate(`/roles/${role.id}`);
    }
  };

  const handleClone = (role: RoleDto) => {
    if (role.id !== undefined) {
      cloneRole.mutate(role.id);
    }
  };

  const confirmDelete = () => {
    if (roleToDelete?.id !== undefined) {
      deleteRole.mutate(roleToDelete.id);
    }
    setRoleToDelete(null);
  };

  const columns = useMemo<ColumnDef<RoleDto>[]>(
    () => [
      {
        id: 'name',
        accessorKey: 'name',
        header: ({ column }) => (
          <DataGridColumnHeader
            column={column}
            title={intl.formatMessage({ id: 'roles.columns.name' })}
          />
        ),
        cell: ({ row }) => (
          <span className="font-medium text-foreground">
            {row.original.name}
          </span>
        ),
        enableSorting: true,
        size: 160,
      },
      {
        id: 'displayName',
        accessorKey: 'displayName',
        header: ({ column }) => (
          <DataGridColumnHeader
            column={column}
            title={intl.formatMessage({ id: 'roles.columns.displayName' })}
          />
        ),
        enableSorting: true,
        size: 200,
      },
      {
        id: 'isStatic',
        accessorKey: 'isStatic',
        header: ({ column }) => (
          <DataGridColumnHeader
            column={column}
            title={intl.formatMessage({ id: 'roles.columns.type' })}
          />
        ),
        cell: ({ row }) =>
          row.original.isStatic ? (
            <Badge variant="info" appearance="light">
              <FormattedMessage id="roles.badge.static" />
            </Badge>
          ) : (
            <Badge variant="secondary" appearance="light">
              <FormattedMessage id="roles.badge.custom" />
            </Badge>
          ),
        enableSorting: false,
        size: 110,
      },
      {
        id: 'isDefault',
        accessorKey: 'isDefault',
        header: ({ column }) => (
          <DataGridColumnHeader
            column={column}
            title={intl.formatMessage({ id: 'roles.columns.default' })}
          />
        ),
        cell: ({ row }) =>
          row.original.isDefault ? (
            <Badge variant="success" appearance="light">
              <FormattedMessage id="roles.badge.default" />
            </Badge>
          ) : null,
        enableSorting: false,
        size: 110,
      },
      {
        id: 'memberCount',
        accessorKey: 'memberCount',
        header: ({ column }) => (
          <DataGridColumnHeader
            column={column}
            title={intl.formatMessage({ id: 'roles.columns.members' })}
          />
        ),
        cell: ({ row }) => (
          <span className="text-muted-foreground">
            {row.original.memberCount ?? 0}
          </span>
        ),
        enableSorting: false,
        size: 90,
      },
      {
        id: 'actions',
        header: '',
        cell: ({ row }) => (
          <RoleRowActions
            role={row.original}
            onEdit={handleEdit}
            onClone={handleClone}
            onDelete={setRoleToDelete}
          />
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
    data: roles,
    pageCount: data?.totalPages ?? 0,
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
        <title>{intl.formatMessage({ id: 'roles.list.title' })}</title>
      </Helmet>

      <PageHeader
        title={<FormattedMessage id="roles.list.title" />}
        description={<FormattedMessage id="roles.list.description" />}
        actions={
          <Can permission="roles.create">
            <Button onClick={() => navigate('/roles/new')}>
              <Plus />
              <FormattedMessage id="roles.list.create" />
            </Button>
          </Can>
        }
      />

      <Card>
        {isError ? (
          <div className="p-5">
            <DataError
              message={intl.formatMessage({ id: 'roles.list.error' })}
              onRetry={() => refetch()}
            />
          </div>
        ) : isLoading ? (
          <div className="p-5">
            <TableSkeleton rows={pagination.pageSize} cols={columns.length} />
          </div>
        ) : recordCount === 0 ? (
          <DataEmpty
            icon={<Shield />}
            title={intl.formatMessage({ id: 'roles.list.empty' })}
            description={intl.formatMessage({ id: 'roles.list.emptyDescription' })}
            action={
              <Can permission="roles.create">
                <Button onClick={() => navigate('/roles/new')}>
                  <Plus />
                  <FormattedMessage id="roles.list.create" />
                </Button>
              </Can>
            }
          />
        ) : (
          <div className="flex flex-col gap-4 p-5">
            <DataGrid table={table} recordCount={recordCount}>
              <DataGridContainer>
                <DataGridTable />
              </DataGridContainer>
              <DataGridPagination />
            </DataGrid>
          </div>
        )}
      </Card>

      <AlertDialog
        open={roleToDelete !== null}
        onOpenChange={(open) => {
          if (!open) {
            setRoleToDelete(null);
          }
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              <FormattedMessage id="roles.delete.title" />
            </AlertDialogTitle>
            <AlertDialogDescription>
              <FormattedMessage
                id="roles.delete.description"
                values={{
                  name: roleToDelete?.displayName ?? roleToDelete?.name ?? '',
                }}
              />
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>
              <FormattedMessage id="roles.delete.cancel" />
            </AlertDialogCancel>
            <AlertDialogAction variant="destructive" onClick={confirmDelete}>
              <FormattedMessage id="roles.delete.confirm" />
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
