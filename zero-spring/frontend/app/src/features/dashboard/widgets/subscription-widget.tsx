import { ArrowRight, CreditCard } from 'lucide-react';
import { FormattedMessage, useIntl } from 'react-intl';
import { Link } from 'react-router-dom';
import { Can } from '@/auth/rbac';
import { useAuth } from '@/providers/auth-provider';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Widget } from '@/components/widgets/widget';
import {
  WidgetEmpty,
  WidgetError,
  WidgetSkeleton,
} from '@/components/widgets/widget-states';
import { toSubscriptionStatus } from '@/features/subscriptions/types';
import type { SubscriptionStatus } from '@/features/subscriptions/types';
import { isSubscriptionMissing, useMySubscription } from '../hooks';

/**
 * Subscription widget — TENANT context only (`GET /api/subscriptions/me` needs
 * only authentication; the backend resolves the tenant from the JWT claim, and
 * rejects a host caller with 400). In host context this renders nothing.
 *
 * Contract note: a tenant without a subscription gets a 404 ProblemDetail,
 * which is the EMPTY state here — with a `subscriptions.read`-gated pointer to
 * `/subscriptions` (a host-side screen; most tenant operators will simply see
 * the message).
 */

/** Same lifecycle → color mapping as the subscriptions list (kept in sync). */
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
          known
            ? `subscriptions.status.${known}`
            : 'subscriptions.status.unknown'
        }
      />
    </Badge>
  );
}

export function SubscriptionWidget({ className }: { className?: string }) {
  const intl = useIntl();
  const { user } = useAuth();
  // Tenant context = the session carries a `tenant` claim (`MeDto.tenantId`).
  const isTenant = user?.tenantId != null;
  const query = useMySubscription(isTenant);

  if (!isTenant) {
    return null;
  }

  const subscription = query.data;
  const missing = query.isError && isSubscriptionMissing(query.error);
  // A trial-only subscription has no period end yet — fall back to trial end.
  const periodEnd =
    subscription?.currentPeriodEndAt ?? subscription?.trialEndAt;

  return (
    <Widget
      title={<FormattedMessage id="dashboard.subscription.title" />}
      description={<FormattedMessage id="dashboard.subscription.description" />}
      icon={<CreditCard />}
      onRefresh={() => void query.refetch()}
      isRefreshing={query.isRefetching}
      className={className}
    >
      {query.isLoading ? (
        <WidgetSkeleton variant="kpi" />
      ) : missing ? (
        <WidgetEmpty
          icon={<CreditCard />}
          title={intl.formatMessage({ id: 'dashboard.subscription.empty' })}
          description={intl.formatMessage({
            id: 'dashboard.subscription.emptyDescription',
          })}
          action={
            <Can permission="subscriptions.read">
              <Button variant="outline" size="sm" asChild>
                <Link to="/subscriptions">
                  <FormattedMessage id="dashboard.subscription.manage" />
                  <ArrowRight aria-hidden="true" />
                </Link>
              </Button>
            </Can>
          }
        />
      ) : query.isError ? (
        <WidgetError
          message={intl.formatMessage({ id: 'dashboard.subscription.error' })}
          onRetry={() => void query.refetch()}
        />
      ) : (
        <dl className="flex flex-col gap-3">
          <div className="flex items-center justify-between gap-3">
            <dt className="text-sm text-muted-foreground">
              <FormattedMessage id="dashboard.subscription.plan" />
            </dt>
            <dd className="truncate text-sm font-semibold text-foreground">
              {subscription?.editionDisplayName ??
                subscription?.editionName ??
                '—'}
            </dd>
          </div>
          <div className="flex items-center justify-between gap-3">
            <dt className="text-sm text-muted-foreground">
              <FormattedMessage id="subscriptions.columns.status" />
            </dt>
            <dd>
              <StatusBadge status={subscription?.status} />
            </dd>
          </div>
          <div className="flex items-center justify-between gap-3">
            <dt className="text-sm text-muted-foreground">
              <FormattedMessage id="dashboard.subscription.periodEnd" />
            </dt>
            <dd className="text-sm tabular-nums text-foreground">
              {periodEnd
                ? intl.formatDate(periodEnd, { dateStyle: 'medium' })
                : '—'}
            </dd>
          </div>
        </dl>
      )}
    </Widget>
  );
}
