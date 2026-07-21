import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  ColumnDef,
  getCoreRowModel,
  PaginationState,
  useReactTable,
} from '@tanstack/react-table';
import {
  Download,
  EllipsisVertical,
  LockOpen,
  Pencil,
  Plus,
  Search,
  ToggleLeft,
  ToggleRight,
  Trash2,
  Users,
} from 'lucide-react';
import { FormattedMessage, useIntl } from 'react-intl';
import { Helmet } from 'react-helmet-async';
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
import { Card, CardHeader, CardTable } from '@/components/ui/card';
import {
  DataEmpty,
  DataError,
  TableSkeleton,
} from '@/components/common/data-state';
import { PageHeader } from '@/components/common/page-header';
import { DataGrid, DataGridContainer } from '@/components/ui/data-grid';
import { DataGridPagination } from '@/components/ui/data-grid-pagination';
import { DataGridTable } from '@/components/ui/data-grid-table';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Input } from '@/components/ui/input';
import { Can } from '@/auth/rbac';
import { RequireAuth } from '@/auth/require-auth';
import { ImpersonateAction } from '@/features/impersonation/components/impersonate-action';
import { UserFormDialog } from '@/features/users/pages/user-form';
import {
  useActivateUser,
  useDeactivateUser,
  useDeleteUser,
  useExportUsers,
  useUnlockUser,
  useUsers,
} from '@/features/users/hooks';
import type { UserDto } from '@/features/users/types';

const SEARCH_DEBOUNCE_MS = 300;

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

/** `true` when the account is currently locked out (unlock action visible). */
function isLocked(user: UserDto): boolean {
  return Boolean(user.lockoutEndAt && new Date(user.lockoutEndAt) > new Date());
}

function UsersListContent() {
  const intl = useIntl();

  const [pagination, setPagination] = useState<PaginationState>({
    pageIndex: 0,
    pageSize: 10,
  });
  const [searchInput, setSearchInput] = useState('');
  const [search, setSearch] = useState('');
  const [formOpen, setFormOpen] = useState(false);
  const [editingUser, setEditingUser] = useState<UserDto | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<UserDto | null>(null);

  // Debounced server-side search; a new term resets to the first page.
  useEffect(() => {
    const timer = window.setTimeout(() => {
      setSearch(searchInput);
      setPagination((current) =>
        current.pageIndex === 0 ? current : { ...current, pageIndex: 0 },
      );
    }, SEARCH_DEBOUNCE_MS);
    return () => window.clearTimeout(timer);
  }, [searchInput]);

  const { data, isLoading, isError, refetch } = useUsers(
    pagination.pageIndex,
    pagination.pageSize,
    search,
  );

  const deleteUser = useDeleteUser();
  const exportUsers = useExportUsers();
  // react-query keeps each `mutate` referentially stable across renders, so
  // pulling them out as their own values lets the `columns` memo depend on
  // exactly what it uses (no whole-mutation objects, no exhaustive-deps
  // suppression, and no needless recompute when a mutation's state flips).
  const { mutate: unlockUserMutate } = useUnlockUser();
  const { mutate: activateUserMutate } = useActivateUser();
  const { mutate: deactivateUserMutate } = useDeactivateUser();

  const users = useMemo(() => data?.content ?? [], [data]);
  const recordCount = data?.totalElements ?? 0;

  const openCreate = () => {
    setEditingUser(null);
    setFormOpen(true);
  };

  // Stable identity so it can be a real `columns` dependency (the state setters
  // it closes over are already stable) instead of forcing an exhaustive-deps
  // suppression on the memo below.
  const openEdit = useCallback((user: UserDto) => {
    setEditingUser(user);
    setFormOpen(true);
  }, []);

  const columns = useMemo<ColumnDef<UserDto>[]>(
    () => [
      {
        accessorKey: 'username',
        id: 'username',
        header: () => <FormattedMessage id="users.column.username" />,
        cell: ({ row }) => (
          <span className="font-medium text-foreground">
            {row.original.username}
          </span>
        ),
      },
      {
        accessorKey: 'email',
        id: 'email',
        header: () => <FormattedMessage id="users.column.email" />,
      },
      {
        id: 'name',
        header: () => <FormattedMessage id="users.column.name" />,
        accessorFn: (row) =>
          [row.name, row.surname].filter(Boolean).join(' ') || '—',
      },
      {
        id: 'active',
        header: () => <FormattedMessage id="users.column.active" />,
        cell: ({ row }) => (
          <Badge
            variant={row.original.active ? 'success' : 'destructive'}
            appearance="light"
          >
            <FormattedMessage
              id={
                row.original.active
                  ? 'users.status.active'
                  : 'users.status.inactive'
              }
            />
          </Badge>
        ),
      },
      {
        id: 'roles',
        header: () => <FormattedMessage id="users.column.roles" />,
        cell: ({ row }) => (
          <div className="flex flex-wrap gap-1">
            {(row.original.roles ?? []).map((role) => (
              <Badge key={role} variant="secondary">
                {role}
              </Badge>
            ))}
          </div>
        ),
      },
      {
        id: 'actions',
        header: () => (
          <span className="sr-only">
            <FormattedMessage id="users.column.actions" />
          </span>
        ),
        meta: { cellClassName: 'text-end' },
        cell: ({ row }) => {
          const user = row.original;
          return (
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button
                  variant="ghost"
                  mode="icon"
                  size="sm"
                  aria-label={intl.formatMessage({
                    id: 'users.column.actions',
                  })}
                >
                  <EllipsisVertical />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end">
                <Can permission="users.update">
                  <DropdownMenuItem onSelect={() => openEdit(user)}>
                    <Pencil />
                    <FormattedMessage id="users.action.edit" />
                  </DropdownMenuItem>
                </Can>
                <Can permission="users.update">
                  {user.active ? (
                    <DropdownMenuItem
                      onSelect={() =>
                        user.id !== undefined && deactivateUserMutate(user.id)
                      }
                    >
                      <ToggleLeft />
                      <FormattedMessage id="users.action.deactivate" />
                    </DropdownMenuItem>
                  ) : (
                    <DropdownMenuItem
                      onSelect={() =>
                        user.id !== undefined && activateUserMutate(user.id)
                      }
                    >
                      <ToggleRight />
                      <FormattedMessage id="users.action.activate" />
                    </DropdownMenuItem>
                  )}
                </Can>
                {isLocked(user) && (
                  <Can permission="users.unlock">
                    <DropdownMenuItem
                      onSelect={() =>
                        user.id !== undefined && unlockUserMutate(user.id)
                      }
                    >
                      <LockOpen />
                      <FormattedMessage id="users.action.unlock" />
                    </DropdownMenuItem>
                  </Can>
                )}
                <Can permission="users.delete">
                  <DropdownMenuItem
                    variant="destructive"
                    onSelect={() => setDeleteTarget(user)}
                  >
                    <Trash2 />
                    <FormattedMessage id="users.action.delete" />
                  </DropdownMenuItem>
                </Can>
                {/* Impersonate — self-gated by `users.impersonate` (renders
                    null otherwise) and by a valid target id. */}
                {user.id !== undefined && (
                  <ImpersonateAction
                    userId={user.id}
                    username={user.username}
                  />
                )}
              </DropdownMenuContent>
            </DropdownMenu>
          );
        },
      },
    ],
    [
      intl,
      openEdit,
      activateUserMutate,
      deactivateUserMutate,
      unlockUserMutate,
    ],
  );

  const table = useReactTable({
    data: users,
    columns,
    getCoreRowModel: getCoreRowModel(),
    manualPagination: true,
    pageCount: data?.totalPages ?? -1,
    state: { pagination },
    onPaginationChange: setPagination,
    getRowId: (row, index) => String(row.id ?? index),
  });

  return (
    <div className="container-fluid">
      <Helmet>
        <title>{intl.formatMessage({ id: 'users.title' })}</title>
      </Helmet>

      <PageHeader
        title={<FormattedMessage id="users.title" />}
        description={<FormattedMessage id="users.subtitle" />}
        actions={
          <>
            <Can permission="users.read">
              <Button
                variant="outline"
                disabled={exportUsers.isPending}
                onClick={() =>
                  exportUsers.mutate(undefined, {
                    onSuccess: (blob) => downloadBlob(blob, 'users.xlsx'),
                  })
                }
              >
                <Download />
                <FormattedMessage id="users.action.export" />
              </Button>
            </Can>
            <Can permission="users.create">
              <Button onClick={openCreate}>
                <Plus />
                <FormattedMessage id="users.action.create" />
              </Button>
            </Can>
          </>
        }
      />

      <Card>
        <CardHeader className="py-4">
          <div className="relative w-full sm:w-64">
            <Search className="size-4 text-muted-foreground absolute start-3 top-1/2 -translate-y-1/2" />
            <Input
              className="ps-9"
              value={searchInput}
              onChange={(event) => setSearchInput(event.target.value)}
              placeholder={intl.formatMessage({
                id: 'users.searchPlaceholder',
              })}
              aria-label={intl.formatMessage({ id: 'common.search' })}
            />
          </div>
        </CardHeader>

        {isError ? (
          <div className="p-5">
            <DataError
              message={intl.formatMessage({ id: 'users.loadError' })}
              onRetry={() => refetch()}
            />
          </div>
        ) : isLoading ? (
          <div className="p-5">
            <TableSkeleton rows={pagination.pageSize} cols={columns.length} />
          </div>
        ) : recordCount === 0 ? (
          <DataEmpty
            icon={<Users />}
            title={intl.formatMessage({ id: 'users.empty' })}
            description={intl.formatMessage({ id: 'users.emptyDescription' })}
            action={
              <Can permission="users.create">
                <Button onClick={openCreate}>
                  <Plus />
                  <FormattedMessage id="users.action.create" />
                </Button>
              </Can>
            }
          />
        ) : (
          <DataGrid table={table} recordCount={recordCount}>
            <CardTable>
              <DataGridContainer border={false}>
                <DataGridTable />
              </DataGridContainer>
            </CardTable>
            <div className="px-5 py-3 border-t border-border">
              <DataGridPagination />
            </div>
          </DataGrid>
        )}
      </Card>

      {formOpen && (
        <UserFormDialog
          open={formOpen}
          onOpenChange={setFormOpen}
          user={editingUser}
        />
      )}

      <AlertDialog
        open={deleteTarget !== null}
        onOpenChange={(open) => {
          if (!open) {
            setDeleteTarget(null);
          }
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              <FormattedMessage id="users.deleteConfirm.title" />
            </AlertDialogTitle>
            <AlertDialogDescription>
              <FormattedMessage
                id="users.deleteConfirm.description"
                values={{ username: deleteTarget?.username ?? '' }}
              />
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>
              <FormattedMessage id="users.deleteConfirm.cancel" />
            </AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                if (deleteTarget?.id !== undefined) {
                  deleteUser.mutate(deleteTarget.id);
                }
                setDeleteTarget(null);
              }}
            >
              <FormattedMessage id="users.deleteConfirm.confirm" />
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

/** Users list page — guarded by `users.read` (double lock with the backend). */
export function UsersListPage() {
  return (
    <RequireAuth permission="users.read">
      <UsersListContent />
    </RequireAuth>
  );
}
