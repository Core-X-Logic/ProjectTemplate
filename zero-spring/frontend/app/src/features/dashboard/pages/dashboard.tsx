import { lazy, Suspense, useMemo } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { Helmet } from 'react-helmet-async';
import { useSearchParams } from 'react-router-dom';
import {
  Activity,
  Bell,
  CreditCard,
  Gauge,
  ShieldCheck,
} from 'lucide-react';
import { useAuth } from '@/providers/auth-provider';
import { useTenant } from '@/providers/tenant-provider';
import { usePermission } from '@/auth/rbac';
import { PageHeader } from '@/components/common/page-header';
import {
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from '@/components/ui/tabs';
import { WidgetSkeleton } from '@/components/widgets/widget-states';
import { KpiCards } from '../widgets/kpi-cards';
import { NotificationsInboxWidget } from '../widgets/notifications-inbox';
import { QuickActionsWidget } from '../widgets/quick-actions';
import { RecentActivityWidget } from '../widgets/recent-activity';
import { RecentUsersWidget } from '../widgets/recent-users';
import { SubscriptionWidget } from '../widgets/subscription-widget';
import { SubscriptionsOverviewWidget } from '../widgets/subscriptions-overview';

/**
 * Dashboard — a tab-based management center over the modular widget system.
 *
 * Tabs (each permission/context-gated; a tab the user can't use is not
 * rendered at all):
 *  - overview    — the system pulse: KPI band + trend + quick access
 *  - operations  — the caller's inbox + newest accounts
 *  - activity    — the audit home: trend + event timeline (`auditlogs.read`)
 *  - finance     — tenant: own subscription · host: subscriptions overview
 *  - management  — admin work surface: KPIs + users + admin shortcuts
 *
 * Behavior notes:
 *  - The active tab lives in the URL (`?tab=…`), so tabs deep-link and survive
 *    refresh; an unknown/hidden value falls back to overview.
 *  - Radix unmounts inactive tab content: a tab's queries fire on FIRST visit
 *    and are served from the 60s query cache afterwards — switching tabs is
 *    instant and adds no polling.
 *  - The trend widget (recharts) is `lazy()`-split so the chart library stays
 *    out of the initial bundle; its Suspense fallback is the chart skeleton.
 */

const ActivityTrendWidget = lazy(() =>
  import('../widgets/activity-trend').then((m) => ({
    default: m.ActivityTrendWidget,
  })),
);

function LazyTrend({ className }: { className?: string }) {
  return (
    <Suspense fallback={<WidgetSkeleton variant="chart" className={className} />}>
      <ActivityTrendWidget className={className} />
    </Suspense>
  );
}

export type DashboardTab =
  | 'overview'
  | 'operations'
  | 'activity'
  | 'finance'
  | 'management';

export function DashboardPage() {
  const intl = useIntl();
  const { user } = useAuth();
  const { tenant } = useTenant();
  const [searchParams, setSearchParams] = useSearchParams();

  const canUsers = usePermission('users.read');
  const canRoles = usePermission('roles.read');
  const canTenants = usePermission('tenants.manage');
  const canAudit = usePermission('auditlogs.read');
  const canSubscriptions = usePermission('subscriptions.read');
  // Tenant context = the session carries a `tenant` claim; host = it doesn't.
  const isTenant = user?.tenantId != null;
  const isHost = !isTenant;

  const displayName = user?.username ?? user?.email ?? '';
  const tenantLabel = tenant ?? user?.tenantId ?? '—';

  // A tab is offered only when it has something the user may see.
  const visibleTabs = useMemo(() => {
    const tabs: Array<{ value: DashboardTab; icon: typeof Gauge }> = [
      { value: 'overview', icon: Gauge },
      { value: 'operations', icon: Bell },
    ];
    if (canAudit) {
      tabs.push({ value: 'activity', icon: Activity });
    }
    if (isTenant || canSubscriptions) {
      tabs.push({ value: 'finance', icon: CreditCard });
    }
    if (canUsers || canRoles || canTenants) {
      tabs.push({ value: 'management', icon: ShieldCheck });
    }
    return tabs;
  }, [canAudit, canSubscriptions, canUsers, canRoles, canTenants, isTenant]);

  const requestedTab = searchParams.get('tab');
  const activeTab: DashboardTab = visibleTabs.some(
    (tab) => tab.value === requestedTab,
  )
    ? (requestedTab as DashboardTab)
    : 'overview';

  const selectTab = (value: string) => {
    // `replace` keeps tab hopping out of the back-button history.
    setSearchParams(value === 'overview' ? {} : { tab: value }, {
      replace: true,
    });
  };

  return (
    <div className="container-fluid">
      <Helmet>
        <title>
          {`${intl.formatMessage({ id: 'nav.dashboard' })} · ${intl.formatMessage({ id: `dashboard.tab.${activeTab}` })}`}
        </title>
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

      <Tabs value={activeTab} onValueChange={selectTab} className="gap-4">
        <div className="overflow-x-auto">
          <TabsList>
            {visibleTabs.map(({ value, icon: Icon }) => (
              <TabsTrigger key={value} value={value} className="gap-1.5">
                <Icon aria-hidden="true" className="size-4" />
                <FormattedMessage id={`dashboard.tab.${value}`} />
              </TabsTrigger>
            ))}
          </TabsList>
        </div>

        {/* Overview — the system pulse. */}
        <TabsContent value="overview">
          <div className="grid grid-cols-12 gap-4">
            <KpiCards />
            {canAudit ? (
              <LazyTrend
                className={
                  isTenant || canUsers
                    ? 'col-span-12 lg:col-span-8'
                    : 'col-span-12'
                }
              />
            ) : null}
            {isTenant ? (
              <SubscriptionWidget
                className={
                  canAudit ? 'col-span-12 lg:col-span-4' : 'col-span-12'
                }
              />
            ) : canUsers && canAudit ? (
              <RecentUsersWidget className="col-span-12 lg:col-span-4" />
            ) : canUsers ? (
              <RecentUsersWidget className="col-span-12" />
            ) : null}
            <QuickActionsWidget className="col-span-12" />
          </div>
        </TabsContent>

        {/* Operations — the caller's own work surface. */}
        <TabsContent value="operations">
          <div className="grid grid-cols-12 gap-4">
            <NotificationsInboxWidget
              className={canUsers ? 'col-span-12 lg:col-span-7' : 'col-span-12'}
            />
            {canUsers ? (
              <RecentUsersWidget className="col-span-12 lg:col-span-5" />
            ) : null}
          </div>
        </TabsContent>

        {/* Activity — the audit home (tab exists only with auditlogs.read). */}
        <TabsContent value="activity">
          <div className="grid grid-cols-12 gap-4">
            <LazyTrend className="col-span-12" />
            <RecentActivityWidget className="col-span-12" />
          </div>
        </TabsContent>

        {/* Finance — tenant sees its own subscription, host the overview. */}
        <TabsContent value="finance">
          <div className="grid grid-cols-12 gap-4">
            {isTenant ? (
              <SubscriptionWidget className="col-span-12 lg:col-span-6" />
            ) : null}
            {isHost && canSubscriptions ? (
              <SubscriptionsOverviewWidget className="col-span-12 lg:col-span-8" />
            ) : null}
          </div>
        </TabsContent>

        {/* Management — the admin work surface. */}
        <TabsContent value="management">
          <div className="grid grid-cols-12 gap-4">
            <KpiCards />
            {canUsers ? (
              <RecentUsersWidget className="col-span-12 lg:col-span-7" />
            ) : null}
            <QuickActionsWidget
              className={canUsers ? 'col-span-12 lg:col-span-5' : 'col-span-12'}
            />
          </div>
        </TabsContent>
      </Tabs>
    </div>
  );
}
