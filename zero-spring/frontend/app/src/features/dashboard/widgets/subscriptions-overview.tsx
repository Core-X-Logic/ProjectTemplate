import { ArrowRight, CreditCard } from 'lucide-react';
import { FormattedMessage, useIntl } from 'react-intl';
import { Link } from 'react-router-dom';
import { usePermission } from '@/auth/rbac';
import { Badge } from '@/components/ui/badge';
import { Widget } from '@/components/widgets/widget';
import {
  WidgetEmpty,
  WidgetError,
  WidgetSkeleton,
} from '@/components/widgets/widget-states';
import { toSubscriptionStatus } from '@/features/subscriptions/types';
import {
  SUBSCRIPTIONS_OVERVIEW_SIZE,
  useSubscriptionsOverview,
} from '../hooks';

/**
 * Host finance overview — the newest tenant subscriptions
 * (`subscriptions.read`, host-only endpoint; hidden and query-less without
 * the permission). Status colors reuse the subscriptions feature's own
 * vocabulary (`subscriptions.status.*`) so the widget and the full page can
 * never disagree on wording.
 */

const STATUS_VARIANT: Record<
  string,
  'success' | 'destructive' | 'warning' | 'secondary'
> = {
  ACTIVE: 'success',
  TRIAL: 'warning',
  GRACE: 'warning',
  CANCELLED: 'destructive',
  EXPIRED: 'destructive',
};

export function SubscriptionsOverviewWidget({
  className,
}: {
  className?: string;
}) {
  const intl = useIntl();
  const canSubscriptions = usePermission('subscriptions.read');
  const query = useSubscriptionsOverview(canSubscriptions);

  if (!canSubscriptions) {
    return null;
  }

  const rows = query.data?.rows ?? [];

  return (
    <Widget
      title={<FormattedMessage id="dashboard.subsOverview.title" />}
      description={
        <FormattedMessage
          id="dashboard.subsOverview.description"
          values={{ total: query.data?.total ?? 0 }}
        />
      }
      icon={<CreditCard />}
      onRefresh={() => void query.refetch()}
      isRefreshing={query.isRefetching}
      className={className}
      footer={
        <Link
          to="/subscriptions"
          className="inline-flex items-center gap-1.5 rounded-md text-sm font-medium text-primary outline-none hover:underline focus-visible:ring-[3px] focus-visible:ring-ring/30"
        >
          <FormattedMessage id="dashboard.subsOverview.viewAll" />
          <ArrowRight aria-hidden="true" className="size-3.5" />
        </Link>
      }
    >
      {query.isLoading ? (
        <WidgetSkeleton variant="list" rows={SUBSCRIPTIONS_OVERVIEW_SIZE} />
      ) : query.isError ? (
        <WidgetError
          message={intl.formatMessage({ id: 'dashboard.subsOverview.error' })}
          onRetry={() => void query.refetch()}
        />
      ) : rows.length === 0 ? (
        <WidgetEmpty
          icon={<CreditCard />}
          title={intl.formatMessage({ id: 'dashboard.subsOverview.empty' })}
        />
      ) : (
        <ul className="flex flex-col divide-y divide-border">
          {rows.map((subscription) => {
            const status = toSubscriptionStatus(subscription.status);
            return (
              <li key={subscription.id}>
                <Link
                  to="/subscriptions"
                  className="-mx-2 flex items-center gap-3 rounded-md px-2 py-2.5 outline-none transition-colors hover:bg-accent/40 focus-visible:ring-[3px] focus-visible:ring-ring/30"
                >
                  <span className="flex min-w-0 flex-1 flex-col">
                    <span className="truncate text-sm font-medium text-foreground">
                      {subscription.tenantName ?? '—'}
                    </span>
                    <span className="truncate text-xs text-muted-foreground">
                      {subscription.editionDisplayName ??
                        subscription.editionName ??
                        '—'}
                    </span>
                  </span>
                  <Badge
                    variant={
                      (status && STATUS_VARIANT[status]) ?? 'secondary'
                    }
                    appearance="light"
                    size="sm"
                  >
                    <FormattedMessage
                      id={
                        status
                          ? `subscriptions.status.${status}`
                          : 'subscriptions.status.unknown'
                      }
                    />
                  </Badge>
                </Link>
              </li>
            );
          })}
        </ul>
      )}
    </Widget>
  );
}
