import { useMemo, useState } from 'react';
import { ArrowDown, ArrowUp, KeyRound, Trash2, Wallet } from 'lucide-react';
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
import { Card, CardContent } from '@/components/ui/card';
import {
  DataEmpty,
  DataError,
  TableSkeleton,
} from '@/components/common/data-state';
import { PageHeader } from '@/components/common/page-header';
import { Can } from '@/auth/rbac';
import { ProviderCredentialsDialog } from '../components/provider-credentials-dialog';
import {
  useBillingProviders,
  useClearBillingCredentials,
  useSaveProviderOrder,
} from '../hooks';
import {
  credentialStatus,
  PROVIDER_FIELDS,
  providerLabel,
  type ProviderStatusDto,
} from '../types';

/**
 * Payment providers screen (managed billing credentials + failover).
 *
 * RBAC — quadruple lock: the sidebar entry is filtered by
 * `billing.credentials.manage`, the route sits behind
 * `<RequireAuth permission="billing.credentials.manage">` (routes.tsx), every
 * write action here is additionally `<Can>`-gated, and the backend enforces
 * `Side.HOST` + the same key on all four endpoints.
 *
 * Security posture: the GET only ever carries `configured`/`maskedHint`/
 * `source`/flags — no raw credential value exists in this page's state, so
 * nothing here can leak one. The dialog is write-only (see
 * ProviderCredentialsDialog).
 *
 * Ordering: deliberately plain up/down buttons over the full-order
 * `PUT /api/billing/providers/order` — with two known providers drag & drop
 * would be machinery without benefit. Rows without a `displayOrder` sink to
 * the end of the list and get an "unordered" badge instead of a position.
 */
export function BillingProvidersPage() {
  const intl = useIntl();
  const { data, isLoading, isError, refetch } = useBillingProviders();
  const saveOrder = useSaveProviderOrder();
  const clearCredentials = useClearBillingCredentials();

  const [credentialToEdit, setCredentialToEdit] =
    useState<ProviderStatusDto | null>(null);
  const [credentialToClear, setCredentialToClear] =
    useState<ProviderStatusDto | null>(null);

  // Render (and reorder) in failover order; `displayOrder: null` sinks to the
  // end, ties break on provider id so the list is stable even if the backend
  // ever returns duplicate positions.
  const providers = useMemo(() => {
    const orderOf = (row: ProviderStatusDto) =>
      row.displayOrder ?? Number.MAX_SAFE_INTEGER;
    return [...(data ?? [])].sort(
      (a, b) =>
        orderOf(a) - orderOf(b) ||
        (a.provider ?? '').localeCompare(b.provider ?? ''),
    );
  }, [data]);

  /** Swap `index` with `index + delta` and persist the FULL resulting order. */
  const move = (index: number, delta: -1 | 1) => {
    const target = index + delta;
    if (target < 0 || target >= providers.length) {
      return;
    }
    const order = providers.map((row) => row.provider ?? '');
    [order[index], order[target]] = [order[target], order[index]];
    // A missing provider id cannot be ordered — drop it rather than send ''.
    saveOrder.mutate(order.filter((provider) => provider !== ''));
  };

  const confirmClear = () => {
    if (credentialToClear?.provider) {
      clearCredentials.mutate(credentialToClear.provider);
    }
    setCredentialToClear(null);
  };

  return (
    <div className="container-fluid">
      <Helmet>
        <title>
          {intl.formatMessage({ id: 'billingProviders.list.title' })}
        </title>
      </Helmet>

      <PageHeader
        title={<FormattedMessage id="billingProviders.list.title" />}
        description={
          <FormattedMessage id="billingProviders.list.description" />
        }
      />

      <div className="flex flex-col gap-4">
        <p className="text-sm text-muted-foreground">
          <FormattedMessage id="billingProviders.list.securityHint" />
        </p>

        {isError ? (
          <Card>
            <div className="p-5">
              <DataError
                message={intl.formatMessage({
                  id: 'billingProviders.list.error',
                })}
                onRetry={() => refetch()}
              />
            </div>
          </Card>
        ) : isLoading ? (
          <Card>
            <div className="p-5">
              <TableSkeleton rows={2} cols={3} />
            </div>
          </Card>
        ) : providers.length === 0 ? (
          <Card>
            <DataEmpty
              icon={<Wallet />}
              title={intl.formatMessage({ id: 'billingProviders.list.empty' })}
            />
          </Card>
        ) : (
          <>
            <div>
              <h2 className="text-sm font-semibold text-foreground">
                <FormattedMessage id="billingProviders.order.title" />
              </h2>
              <p className="text-sm text-muted-foreground">
                <FormattedMessage id="billingProviders.order.description" />
              </p>
            </div>

            <ul className="flex flex-col gap-4">
              {providers.map((row, index) => (
                <li key={row.provider ?? index}>
                  <ProviderCard
                    credential={row}
                    position={row.displayOrder == null ? null : index + 1}
                    isFirst={index === 0}
                    isLast={index === providers.length - 1}
                    orderPending={saveOrder.isPending}
                    onMoveUp={() => move(index, -1)}
                    onMoveDown={() => move(index, 1)}
                    onConfigure={() => setCredentialToEdit(row)}
                    onClear={() => setCredentialToClear(row)}
                  />
                </li>
              ))}
            </ul>
          </>
        )}
      </div>

      <ProviderCredentialsDialog
        credential={credentialToEdit}
        onOpenChange={(open) => {
          if (!open) {
            setCredentialToEdit(null);
          }
        }}
      />

      <AlertDialog
        open={credentialToClear !== null}
        onOpenChange={(open) => {
          if (!open) {
            setCredentialToClear(null);
          }
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              <FormattedMessage id="billingProviders.clear.title" />
            </AlertDialogTitle>
            <AlertDialogDescription>
              <FormattedMessage
                id="billingProviders.clear.description"
                values={{
                  name: providerLabel(credentialToClear?.provider ?? ''),
                }}
              />
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>
              <FormattedMessage id="billingProviders.clear.cancel" />
            </AlertDialogCancel>
            <AlertDialogAction variant="destructive" onClick={confirmClear}>
              <FormattedMessage id="billingProviders.clear.confirm" />
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

interface ProviderCardProps {
  credential: ProviderStatusDto;
  /**
   * 1-based failover position (after sorting by `displayOrder`); `null` when
   * the row has no `displayOrder` — it renders an "unordered" badge instead.
   */
  position: number | null;
  isFirst: boolean;
  isLast: boolean;
  /** Disables both arrows while a reorder PUT is in flight (no double-swaps). */
  orderPending: boolean;
  onMoveUp: () => void;
  onMoveDown: () => void;
  onConfigure: () => void;
  onClear: () => void;
}

/**
 * One provider row: brand name + status badges + masked hint + actions.
 * Status badges come from the backend `source` discriminator (db/env/none).
 * Providers without a field descriptor (e.g. an unexpected backend id) still
 * get a status card — they just have no edit dialog to open.
 */
function ProviderCard({
  credential,
  position,
  isFirst,
  isLast,
  orderPending,
  onMoveUp,
  onMoveDown,
  onConfigure,
  onClear,
}: ProviderCardProps) {
  const intl = useIntl();
  const status = credentialStatus(credential);
  const name = providerLabel(credential.provider ?? '');
  const editable =
    (PROVIDER_FIELDS[credential.provider ?? ''] ?? []).length > 0;

  return (
    <Card>
      <CardContent className="flex flex-col gap-4 p-5">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="flex flex-col gap-1.5">
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-base font-semibold text-foreground">
                {name}
              </span>
              {status === 'stored' ? (
                <Badge variant="success" appearance="light">
                  <FormattedMessage id="billingProviders.badge.stored" />
                </Badge>
              ) : status === 'env' ? (
                <Badge variant="info" appearance="light">
                  <FormattedMessage id="billingProviders.badge.env" />
                </Badge>
              ) : (
                <Badge variant="warning" appearance="light">
                  <FormattedMessage id="billingProviders.badge.unconfigured" />
                </Badge>
              )}
              <Badge
                variant={credential.enabled ? 'primary' : 'secondary'}
                appearance="light"
              >
                <FormattedMessage
                  id={
                    credential.enabled
                      ? 'billingProviders.badge.enabled'
                      : 'billingProviders.badge.disabled'
                  }
                />
              </Badge>
              {position === null ? (
                <Badge variant="secondary" appearance="light">
                  <FormattedMessage id="billingProviders.badge.unordered" />
                </Badge>
              ) : null}
            </div>

            {position !== null ? (
              <p className="text-sm text-muted-foreground">
                <FormattedMessage
                  id="billingProviders.card.orderLabel"
                  values={{ position }}
                />
              </p>
            ) : null}

            {status === 'stored' && credential.maskedHint ? (
              <p className="text-sm text-muted-foreground">
                <FormattedMessage
                  id="billingProviders.card.maskLabel"
                  values={{ mask: credential.maskedHint }}
                />
              </p>
            ) : status === 'env' ? (
              <p className="text-sm text-muted-foreground">
                <FormattedMessage id="billingProviders.card.envHint" />
              </p>
            ) : status === 'unconfigured' ? (
              <p className="text-sm text-muted-foreground">
                <FormattedMessage id="billingProviders.card.unconfiguredHint" />
              </p>
            ) : null}
          </div>

          <Can permission="billing.credentials.manage">
            <div className="flex items-center gap-2">
              <Button
                variant="ghost"
                mode="icon"
                size="sm"
                disabled={isFirst || orderPending}
                aria-label={intl.formatMessage(
                  { id: 'billingProviders.actions.moveUp' },
                  { name },
                )}
                onClick={onMoveUp}
              >
                <ArrowUp />
              </Button>
              <Button
                variant="ghost"
                mode="icon"
                size="sm"
                disabled={isLast || orderPending}
                aria-label={intl.formatMessage(
                  { id: 'billingProviders.actions.moveDown' },
                  { name },
                )}
                onClick={onMoveDown}
              >
                <ArrowDown />
              </Button>
              {editable ? (
                <Button variant="outline" size="sm" onClick={onConfigure}>
                  <KeyRound />
                  <FormattedMessage
                    id={
                      status === 'stored'
                        ? 'billingProviders.actions.update'
                        : 'billingProviders.actions.configure'
                    }
                  />
                </Button>
              ) : null}
              {status === 'stored' ? (
                <Button
                  variant="outline"
                  size="sm"
                  className="text-destructive"
                  onClick={onClear}
                >
                  <Trash2 />
                  <FormattedMessage id="billingProviders.actions.clear" />
                </Button>
              ) : null}
            </div>
          </Can>
        </div>
      </CardContent>
    </Card>
  );
}
