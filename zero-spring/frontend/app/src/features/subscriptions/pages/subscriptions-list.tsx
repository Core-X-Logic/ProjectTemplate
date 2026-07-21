import { useMemo, useState } from 'react';
import {
  ColumnDef,
  getCoreRowModel,
  PaginationState,
  useReactTable,
} from '@tanstack/react-table';
import {
  CreditCard,
  EllipsisVertical,
  Package,
  Play,
  SlidersHorizontal,
  X,
} from 'lucide-react';
import { Helmet } from 'react-helmet-async';
import { FormattedMessage, useIntl } from 'react-intl';
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
import { Can } from '@/auth/rbac';
import { AssignEditionDialog } from '../components/assign-edition-dialog';
import { CheckoutDialog } from '../components/checkout-dialog';
import { TenantFeaturesPanel } from '../components/tenant-features-panel';
import {
  useActivateSubscription,
  useCancelSubscription,
  useSubscriptions,
} from '../hooks';
import {
  toSubscriptionStatus,
  type SubscriptionDto,
  type SubscriptionListParams,
  type SubscriptionStatus,
} from '../types';

/**
 * Subscriptions list (F5 slice A) — server-paged data-grid over
 * `GET /api/subscriptions`, one row per tenant.
 *
 * RBAC: the page sits behind `<RequireAuth permission="subscriptions.read">`
 * (routes.tsx); assign/activate/cancel are hidden behind
 * `<Can permission="subscriptions.manage">` and the feature-override panel
 * behind `tenantfeatures.manage` (double lock — the backend enforces
 * `Side.HOST` on every write).
 *
 * Invalid lifecycle transitions are rejected by the backend with 400; the
 * mutation hooks surface that ProblemDetail `detail` in an error toast rather
 * than the UI trying to model the state machine a second time.
 */

/** Badge colour per lifecycle state; unknown states fall back to neutral. */
const STATUS_VARIANT: Record<
  SubscriptionStatus,
  'success' | 'info' | 'warning' | 'destructive' | 'secondary'
> = {
  TRIALING: 'info',
  ACTIVE: 'success',
  GRACE: 'warning',
  EXPIRED: 'destructive',
  CANCELLED: 'secondary',
  PENDING_PAYMENT: 'warning',
};

function StatusBadge({ status }: { status?: string }) {
  const known = toSubscriptionStatus(status);

  return (
    <Badge
      variant={known ? STATUS_VARIANT[known] : 'secondary'}
      appearance="light"
    >
      <FormattedMessage
        id={
          known ? `subscriptions.status.${known}` : 'subscriptions.status.unknown'
        }
      />
    </Badge>
  );
}

interface SubscriptionRowActionsProps {
  subscription: SubscriptionDto;
  onAssign: (subscription: SubscriptionDto) => void;
  onCheckout: (subscription: SubscriptionDto) => void;
  onActivate: (subscription: SubscriptionDto) => void;
  onCancel: (subscription: SubscriptionDto) => void;
  onFeatures: (subscription: SubscriptionDto) => void;
}

function SubscriptionRowActions({
  subscription,
  onAssign,
  onCheckout,
  onActivate,
  onCancel,
  onFeatures,
}: SubscriptionRowActionsProps) {
  const intl = useIntl();

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          variant="ghost"
          mode="icon"
          size="sm"
          aria-label={intl.formatMessage({ id: 'subscriptions.actions.menu' })}
        >
          <EllipsisVertical />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" side="bottom">
        <Can permission="subscriptions.manage">
          <DropdownMenuItem onSelect={() => onAssign(subscription)}>
            <Package />
            <FormattedMessage id="subscriptions.actions.assign" />
          </DropdownMenuItem>
          {/* Checkout (P2'-C) shares the assign permission: it is the paid
              variant of the same host-side operation. */}
          <DropdownMenuItem onSelect={() => onCheckout(subscription)}>
            <CreditCard />
            <FormattedMessage id="subscriptions.actions.checkout" />
          </DropdownMenuItem>
          <DropdownMenuItem onSelect={() => onActivate(subscription)}>
            <Play />
            <FormattedMessage id="subscriptions.actions.activate" />
          </DropdownMenuItem>
        </Can>
        <Can permission="tenantfeatures.manage">
          <DropdownMenuItem onSelect={() => onFeatures(subscription)}>
            <SlidersHorizontal />
            <FormattedMessage id="subscriptions.actions.features" />
          </DropdownMenuItem>
        </Can>
        <Can permission="subscriptions.manage">
          <DropdownMenuSeparator />
          <DropdownMenuItem
            variant="destructive"
            onSelect={() => onCancel(subscription)}
          >
            <X />
            <FormattedMessage id="subscriptions.actions.cancel" />
          </DropdownMenuItem>
        </Can>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

export function SubscriptionsListPage() {
  return <SubscriptionsListContent />;
}

function SubscriptionsListContent() {
  const intl = useIntl();

  const [pagination, setPagination] = useState<PaginationState>({
    pageIndex: 0,
    pageSize: 10,
  });
  // No `sort` param. The backend fixes the order
  // (`findAllByOrderByTenantIdAsc`), and `tenantName` is not even a
  // Subscription property — it is joined in from the tenancy module — so a sort
  // on it would raise PropertyReferenceException (500). Column sorting is
  // therefore disabled rather than offering headers that break or no-op.
  const params = useMemo<SubscriptionListParams>(
    () => ({
      page: pagination.pageIndex,
      size: pagination.pageSize,
    }),
    [pagination],
  );

  const { data, isLoading, isError, refetch } = useSubscriptions(params);
  const activate = useActivateSubscription();
  const cancel = useCancelSubscription();

  const [assignTarget, setAssignTarget] = useState<SubscriptionDto | null>(null);
  const [checkoutTarget, setCheckoutTarget] = useState<SubscriptionDto | null>(
    null,
  );
  const [cancelTarget, setCancelTarget] = useState<SubscriptionDto | null>(null);
  const [featuresTarget, setFeaturesTarget] = useState<SubscriptionDto | null>(
    null,
  );

  const subscriptions = useMemo(() => data?.content ?? [], [data]);
  const recordCount = data?.totalElements ?? 0;

  const handleActivate = (subscription: SubscriptionDto) => {
    if (subscription.tenantId !== undefined) {
      activate.mutate(subscription.tenantId);
    }
  };

  const confirmCancel = () => {
    if (cancelTarget?.tenantId !== undefined) {
      cancel.mutate(cancelTarget.tenantId);
    }
    setCancelTarget(null);
  };

  const columns = useMemo<ColumnDef<SubscriptionDto>[]>(
    () => [
      {
        id: 'tenantName',
        accessorKey: 'tenantName',
        header: ({ column }) => (
          <DataGridColumnHeader
            column={column}
            title={intl.formatMessage({ id: 'subscriptions.columns.tenant' })}
          />
        ),
        cell: ({ row }) => (
          <span className="font-medium text-foreground">
            {row.original.tenantName ?? row.original.tenantId ?? '—'}
          </span>
        ),
        enableSorting: false,
        size: 180,
      },
      {
        id: 'editionDisplayName',
        accessorKey: 'editionDisplayName',
        header: ({ column }) => (
          <DataGridColumnHeader
            column={column}
            title={intl.formatMessage({ id: 'subscriptions.columns.edition' })}
          />
        ),
        cell: ({ row }) => (
          <span className="text-foreground">
            {row.original.editionDisplayName ??
              row.original.editionName ??
              '—'}
          </span>
        ),
        enableSorting: false,
        size: 160,
      },
      {
        id: 'status',
        accessorKey: 'status',
        header: ({ column }) => (
          <DataGridColumnHeader
            column={column}
            title={intl.formatMessage({ id: 'subscriptions.columns.status' })}
          />
        ),
        cell: ({ row }) => <StatusBadge status={row.original.status} />,
        enableSorting: false,
        size: 140,
      },
      {
        id: 'currentPeriodEndAt',
        accessorKey: 'currentPeriodEndAt',
        header: ({ column }) => (
          <DataGridColumnHeader
            column={column}
            title={intl.formatMessage({
              id: 'subscriptions.columns.currentPeriodEndAt',
            })}
          />
        ),
        cell: ({ row }) => {
          const value = row.original.currentPeriodEndAt;
          return (
            <span className="text-muted-foreground">
              {value ? intl.formatDate(value, { dateStyle: 'medium' }) : '—'}
            </span>
          );
        },
        enableSorting: false,
        size: 140,
      },
      {
        id: 'billingPeriod',
        accessorKey: 'billingPeriod',
        header: ({ column }) => (
          <DataGridColumnHeader
            column={column}
            title={intl.formatMessage({
              id: 'subscriptions.columns.billingPeriod',
            })}
          />
        ),
        cell: ({ row }) => {
          const period = row.original.billingPeriod;
          const known = period === 'MONTHLY' || period === 'ANNUAL';
          return (
            <span className="text-muted-foreground">
              <FormattedMessage
                id={
                  known
                    ? `subscriptions.period.${period}`
                    : 'subscriptions.period.none'
                }
              />
            </span>
          );
        },
        enableSorting: false,
        size: 110,
      },
      {
        id: 'actions',
        header: '',
        cell: ({ row }) => (
          <SubscriptionRowActions
            subscription={row.original}
            onAssign={setAssignTarget}
            onCheckout={setCheckoutTarget}
            onActivate={handleActivate}
            onCancel={setCancelTarget}
            onFeatures={setFeaturesTarget}
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
    data: subscriptions,
    pageCount: data?.totalPages ?? 0,
    getRowId: (row, index) => String(row.tenantId ?? row.id ?? index),
    state: { pagination },
    onPaginationChange: setPagination,
    manualPagination: true,
    getCoreRowModel: getCoreRowModel(),
  });

  const cancelTenantLabel =
    cancelTarget?.tenantName ??
    (cancelTarget?.tenantId !== undefined ? String(cancelTarget.tenantId) : '');

  return (
    <div className="container-fluid">
      <Helmet>
        <title>{intl.formatMessage({ id: 'subscriptions.list.title' })}</title>
      </Helmet>

      <PageHeader
        title={<FormattedMessage id="subscriptions.list.title" />}
        description={<FormattedMessage id="subscriptions.list.description" />}
      />

      <Card>
        {isError ? (
          <div className="p-5">
            <DataError
              message={intl.formatMessage({ id: 'subscriptions.list.error' })}
              onRetry={() => refetch()}
            />
          </div>
        ) : isLoading ? (
          <div className="p-5">
            <TableSkeleton rows={pagination.pageSize} cols={columns.length} />
          </div>
        ) : recordCount === 0 ? (
          <DataEmpty
            icon={<CreditCard />}
            title={intl.formatMessage({ id: 'subscriptions.list.empty' })}
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

      <AssignEditionDialog
        open={assignTarget !== null}
        onOpenChange={(open) => {
          if (!open) {
            setAssignTarget(null);
          }
        }}
        subscription={assignTarget}
      />

      <CheckoutDialog
        open={checkoutTarget !== null}
        onOpenChange={(open) => {
          if (!open) {
            setCheckoutTarget(null);
          }
        }}
        subscription={checkoutTarget}
      />

      <TenantFeaturesPanel
        open={featuresTarget !== null}
        onOpenChange={(open) => {
          if (!open) {
            setFeaturesTarget(null);
          }
        }}
        tenantId={featuresTarget?.tenantId ?? null}
        tenantName={featuresTarget?.tenantName}
      />

      <AlertDialog
        open={cancelTarget !== null}
        onOpenChange={(open) => {
          if (!open) {
            setCancelTarget(null);
          }
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              <FormattedMessage id="subscriptions.cancelConfirm.title" />
            </AlertDialogTitle>
            <AlertDialogDescription>
              <FormattedMessage
                id="subscriptions.cancelConfirm.description"
                values={{ tenant: cancelTenantLabel }}
              />
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>
              <FormattedMessage id="subscriptions.cancelConfirm.cancel" />
            </AlertDialogCancel>
            <AlertDialogAction variant="destructive" onClick={confirmCancel}>
              <FormattedMessage id="subscriptions.cancelConfirm.confirm" />
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
