import { FormattedMessage, useIntl } from 'react-intl';
import { Helmet } from 'react-helmet-async';
import { useAuth } from '@/providers/auth-provider';
import { useTenant } from '@/providers/tenant-provider';
import { usePermission } from '@/auth/rbac';
import { PageHeader } from '@/components/common/page-header';
import { ActivityTrendWidget } from '../widgets/activity-trend';
import { KpiCards } from '../widgets/kpi-cards';
import { QuickActionsWidget } from '../widgets/quick-actions';
import { RecentActivityWidget } from '../widgets/recent-activity';
import { RecentUsersWidget } from '../widgets/recent-users';
import { SubscriptionWidget } from '../widgets/subscription-widget';

/**
 * Dashboard — a modular widget grid (the ASP.NET Zero dashboard-widget idea,
 * done the modern way). Every widget owns its OWN query, states (loading /
 * error / empty / filled) and permission, so one failing widget can never take
 * the page down, and a permission the user lacks removes both the tile and its
 * network call.
 *
 * Layout: a 12-column grid that RE-FLOWS as widgets disappear —
 *  - KPI band (up to four permission-gated tiles),
 *  - trend (8) + side slot (4): subscription in tenant context; in host
 *    context recent-users moves up beside the trend and the bottom row
 *    re-flows,
 *  - recent-users (7) + recent-activity (5) when both fit below,
 *  - quick actions (12) — the original quick-access grid, preserved as a
 *    widget with the same i18n keys and the same permission filtering.
 */
export function DashboardPage() {
  const intl = useIntl();
  const { user } = useAuth();
  const { tenant } = useTenant();

  const canUsers = usePermission('users.read');
  const canAudit = usePermission('auditlogs.read');
  // Tenant context = the session carries a `tenant` claim; host = it doesn't.
  const isTenant = user?.tenantId != null;

  const displayName = user?.username ?? user?.email ?? '';
  const tenantLabel = tenant ?? user?.tenantId ?? '—';

  const showTrend = canAudit;
  const showSubscription = isTenant;
  // The 4-column slot beside the trend: subscription (tenant) — otherwise
  // recent-users moves up so the host layout leaves no hole.
  const sideIsRecentUsers = !showSubscription && showTrend && canUsers;
  const hasSideWidget = showSubscription || sideIsRecentUsers;
  // Bottom row: recent-users (unless it moved up) + recent-activity.
  const showRecentUsersBelow = canUsers && !sideIsRecentUsers;

  return (
    <div className="container-fluid">
      <Helmet>
        <title>{intl.formatMessage({ id: 'nav.dashboard' })}</title>
      </Helmet>

      <PageHeader
        title={
          <FormattedMessage
            id="dashboard.welcome"
            values={{ name: displayName }}
          />
        }
        description={<FormattedMessage id="dashboard.subtitle" />}
        actions={
          <div className="flex items-center gap-2 text-sm">
            <span className="text-muted-foreground">
              <FormattedMessage id="dashboard.tenantLabel" />
            </span>
            <span className="font-medium text-foreground">{tenantLabel}</span>
          </div>
        }
      />

      <div className="grid grid-cols-12 gap-4">
        <KpiCards />

        {showTrend ? (
          <ActivityTrendWidget
            className={
              hasSideWidget ? 'col-span-12 lg:col-span-8' : 'col-span-12'
            }
          />
        ) : null}
        {showSubscription ? (
          <SubscriptionWidget
            className={showTrend ? 'col-span-12 lg:col-span-4' : 'col-span-12'}
          />
        ) : null}
        {sideIsRecentUsers ? (
          <RecentUsersWidget className="col-span-12 lg:col-span-4" />
        ) : null}

        {showRecentUsersBelow ? (
          <RecentUsersWidget
            className={canAudit ? 'col-span-12 lg:col-span-7' : 'col-span-12'}
          />
        ) : null}
        {canAudit ? (
          <RecentActivityWidget
            className={
              showRecentUsersBelow ? 'col-span-12 lg:col-span-5' : 'col-span-12'
            }
          />
        ) : null}

        <QuickActionsWidget className="col-span-12" />
      </div>
    </div>
  );
}
