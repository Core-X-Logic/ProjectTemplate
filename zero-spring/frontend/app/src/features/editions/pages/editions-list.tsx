import { useMemo, useState } from 'react';
import {
  ColumnDef,
  getCoreRowModel,
  PaginationState,
  useReactTable,
} from '@tanstack/react-table';
import { EllipsisVertical, Pencil, Plus, Trash2 } from 'lucide-react';
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
import {
  Card,
  CardDescription,
  CardHeader,
  CardHeading,
  CardTitle,
  CardToolbar,
} from '@/components/ui/card';
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
import { Can } from '@/auth/rbac';
import { useDeleteEdition, useEditions } from '../hooks';
import type { EditionDto, EditionListParams } from '../types';

/**
 * Editions list (F5 slice A) — server-paged data-grid over `GET /api/editions`.
 *
 * RBAC: the page sits behind `<RequireAuth permission="editions.read">`
 * (routes.tsx); create/edit/delete are additionally hidden behind
 * `<Can permission="editions.manage">` (double lock — the backend enforces
 * `Side.HOST` + `editions.manage`).
 *
 * Delete surfaces the backend 409 ("edition is in use" / "referenced as an
 * expiring edition") through the mutation hook's error toast, which prefers the
 * ProblemDetail `detail`.
 */
export function EditionsListPage() {
  return <EditionsListContent />;
}

/** Money cell: currency-aware, degrading to a plain number for odd codes. */
function PriceCell({
  amount,
  currency,
}: {
  amount?: number;
  currency?: string;
}) {
  const intl = useIntl();

  if (amount === undefined || amount === null) {
    return <span className="text-muted-foreground">—</span>;
  }

  let formatted: string;
  try {
    formatted = currency
      ? intl.formatNumber(amount, { style: 'currency', currency })
      : intl.formatNumber(amount);
  } catch {
    // An unknown/blank ISO code must not take the whole grid down.
    formatted = `${intl.formatNumber(amount)}${currency ? ` ${currency}` : ''}`;
  }

  return <span className="text-foreground">{formatted}</span>;
}

interface EditionRowActionsProps {
  edition: EditionDto;
  onEdit: (edition: EditionDto) => void;
  onDelete: (edition: EditionDto) => void;
}

function EditionRowActions({
  edition,
  onEdit,
  onDelete,
}: EditionRowActionsProps) {
  const intl = useIntl();

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          variant="ghost"
          mode="icon"
          size="sm"
          aria-label={intl.formatMessage({ id: 'editions.actions.menu' })}
        >
          <EllipsisVertical />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" side="bottom">
        <Can permission="editions.manage">
          <DropdownMenuItem onSelect={() => onEdit(edition)}>
            <Pencil />
            <FormattedMessage id="editions.actions.edit" />
          </DropdownMenuItem>
          <DropdownMenuSeparator />
          <DropdownMenuItem
            variant="destructive"
            onSelect={() => onDelete(edition)}
          >
            <Trash2 />
            <FormattedMessage id="editions.actions.delete" />
          </DropdownMenuItem>
        </Can>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

function EditionsListContent() {
  const intl = useIntl();
  const navigate = useNavigate();

  const [pagination, setPagination] = useState<PaginationState>({
    pageIndex: 0,
    pageSize: 10,
  });
  // No `sort` param: the backend list query fixes the order
  // (`findAllByOrderBySortOrderAscIdAsc`), so column sorting is disabled rather
  // than offering headers that would silently do nothing.
  const params = useMemo<EditionListParams>(
    () => ({
      page: pagination.pageIndex,
      size: pagination.pageSize,
    }),
    [pagination],
  );

  const { data, isLoading, isError } = useEditions(params);
  const deleteEdition = useDeleteEdition();

  const [editionToDelete, setEditionToDelete] = useState<EditionDto | null>(
    null,
  );

  const editions = useMemo(() => data?.content ?? [], [data]);
  const recordCount = data?.totalElements ?? 0;

  const handleEdit = (edition: EditionDto) => {
    if (edition.id !== undefined) {
      navigate(`/editions/${edition.id}`);
    }
  };

  const confirmDelete = () => {
    if (editionToDelete?.id !== undefined) {
      deleteEdition.mutate(editionToDelete.id);
    }
    setEditionToDelete(null);
  };

  const columns = useMemo<ColumnDef<EditionDto>[]>(
    () => [
      {
        id: 'name',
        accessorKey: 'name',
        header: ({ column }) => (
          <DataGridColumnHeader
            column={column}
            title={intl.formatMessage({ id: 'editions.columns.name' })}
          />
        ),
        cell: ({ row }) => (
          <div className="flex items-center gap-2">
            <span className="font-medium text-foreground">
              {row.original.name}
            </span>
            {row.original.free ? (
              <Badge variant="secondary" appearance="light" size="sm">
                <FormattedMessage id="editions.badge.free" />
              </Badge>
            ) : null}
          </div>
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
            title={intl.formatMessage({ id: 'editions.columns.displayName' })}
          />
        ),
        enableSorting: false,
        size: 180,
      },
      {
        id: 'monthlyPrice',
        accessorKey: 'monthlyPrice',
        header: ({ column }) => (
          <DataGridColumnHeader
            column={column}
            title={intl.formatMessage({ id: 'editions.columns.monthlyPrice' })}
          />
        ),
        cell: ({ row }) => (
          <PriceCell
            amount={row.original.monthlyPrice}
            currency={row.original.currency}
          />
        ),
        enableSorting: false,
        size: 120,
      },
      {
        id: 'annualPrice',
        accessorKey: 'annualPrice',
        header: ({ column }) => (
          <DataGridColumnHeader
            column={column}
            title={intl.formatMessage({ id: 'editions.columns.annualPrice' })}
          />
        ),
        cell: ({ row }) => (
          <PriceCell
            amount={row.original.annualPrice}
            currency={row.original.currency}
          />
        ),
        enableSorting: false,
        size: 120,
      },
      {
        id: 'trialDayCount',
        accessorKey: 'trialDayCount',
        header: ({ column }) => (
          <DataGridColumnHeader
            column={column}
            title={intl.formatMessage({ id: 'editions.columns.trialDayCount' })}
          />
        ),
        cell: ({ row }) => (
          <span className="text-muted-foreground">
            {row.original.trialDayCount ?? 0}
          </span>
        ),
        enableSorting: false,
        size: 110,
      },
      {
        id: 'graceDayCount',
        accessorKey: 'graceDayCount',
        header: ({ column }) => (
          <DataGridColumnHeader
            column={column}
            title={intl.formatMessage({ id: 'editions.columns.graceDayCount' })}
          />
        ),
        cell: ({ row }) => (
          <span className="text-muted-foreground">
            {row.original.graceDayCount ?? 0}
          </span>
        ),
        enableSorting: false,
        size: 110,
      },
      {
        id: 'active',
        accessorKey: 'active',
        header: ({ column }) => (
          <DataGridColumnHeader
            column={column}
            title={intl.formatMessage({ id: 'editions.columns.active' })}
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
                  ? 'editions.badge.active'
                  : 'editions.badge.inactive'
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
          <EditionRowActions
            edition={row.original}
            onEdit={handleEdit}
            onDelete={setEditionToDelete}
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
    data: editions,
    pageCount: data?.totalPages ?? 0,
    getRowId: (row, index) => String(row.id ?? index),
    state: { pagination },
    onPaginationChange: setPagination,
    manualPagination: true,
    getCoreRowModel: getCoreRowModel(),
  });

  return (
    <div className="container-fluid">
      <Helmet>
        <title>{intl.formatMessage({ id: 'editions.list.title' })}</title>
      </Helmet>

      <Card>
        <CardHeader className="py-5">
          <CardHeading>
            <CardTitle>
              <FormattedMessage id="editions.list.title" />
            </CardTitle>
            <CardDescription>
              <FormattedMessage id="editions.list.description" />
            </CardDescription>
          </CardHeading>
          <CardToolbar>
            <Can permission="editions.manage">
              <Button onClick={() => navigate('/editions/new')}>
                <Plus />
                <FormattedMessage id="editions.list.create" />
              </Button>
            </Can>
          </CardToolbar>
        </CardHeader>

        <div className="flex flex-col gap-4 p-5">
          {isError ? (
            <p role="alert" className="text-sm text-destructive">
              <FormattedMessage id="editions.list.error" />
            </p>
          ) : (
            <DataGrid
              table={table}
              recordCount={recordCount}
              isLoading={isLoading}
              emptyMessage={intl.formatMessage({ id: 'editions.list.empty' })}
            >
              <DataGridContainer>
                <DataGridTable />
              </DataGridContainer>
              <DataGridPagination />
            </DataGrid>
          )}
        </div>
      </Card>

      <AlertDialog
        open={editionToDelete !== null}
        onOpenChange={(open) => {
          if (!open) {
            setEditionToDelete(null);
          }
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              <FormattedMessage id="editions.delete.title" />
            </AlertDialogTitle>
            <AlertDialogDescription>
              <FormattedMessage
                id="editions.delete.description"
                values={{
                  name:
                    editionToDelete?.displayName ?? editionToDelete?.name ?? '',
                }}
              />
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>
              <FormattedMessage id="editions.delete.cancel" />
            </AlertDialogCancel>
            <AlertDialogAction variant="destructive" onClick={confirmDelete}>
              <FormattedMessage id="editions.delete.confirm" />
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
