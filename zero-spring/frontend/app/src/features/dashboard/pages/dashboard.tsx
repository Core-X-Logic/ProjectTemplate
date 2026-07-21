import { useMemo } from 'react';
import { ArrowRight } from 'lucide-react';
import { FormattedMessage, useIntl } from 'react-intl';
import { Helmet } from 'react-helmet-async';
import { Link } from 'react-router-dom';
import { useAuth } from '@/providers/auth-provider';
import { useTenant } from '@/providers/tenant-provider';
import { useSidebarMenu } from '@/config/menu.config';
import type { MenuItem } from '@/config/types';
import { PageHeader } from '@/components/common/page-header';
import { DataEmpty } from '@/components/common/data-state';
import { Card } from '@/components/ui/card';

/**
 * Dashboard landing (FRONTEND-ARCHITECTURE.md §8).
 *
 * A professional entry surface that summarises what the signed-in user can
 * actually do — no invented metrics, no new API calls. The quick-access grid is
 * derived from the SAME permission-filtered sidebar menu the shell renders
 * (`useSidebarMenu`), so a card only appears when the user already holds the
 * permission for that section (and the backend enforces it on arrival).
 */

/** Per-destination one-liner, keyed by the menu item's route path. */
const CARD_DESCRIPTION_KEY: Record<string, string> = {
  '/users': 'dashboard.card.users',
  '/roles': 'dashboard.card.roles',
  '/organization-units': 'dashboard.card.organizationUnits',
  '/tenants': 'dashboard.card.tenants',
  '/notifications': 'dashboard.card.notifications',
  '/audit': 'dashboard.card.audit',
  '/editions': 'dashboard.card.editions',
  '/subscriptions': 'dashboard.card.subscriptions',
  '/settings': 'dashboard.card.settings',
};

/** Flatten the (already permission-filtered) menu to its navigable leaves. */
function toLeaves(items: MenuItem[]): MenuItem[] {
  return items.flatMap((item) => {
    if (item.children && item.children.length > 0) {
      return toLeaves(item.children);
    }
    // Skip the dashboard itself ('/') — this page IS that destination.
    return item.path && item.path !== '/' ? [item] : [];
  });
}

export function DashboardPage() {
  const intl = useIntl();
  const { user } = useAuth();
  const { tenant } = useTenant();
  const menu = useSidebarMenu();

  const displayName = user?.username ?? user?.email ?? '';
  const tenantLabel = tenant ?? user?.tenantId ?? '—';

  const cards = useMemo(() => toLeaves(menu), [menu]);

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

      <section className="flex flex-col gap-4">
        <h2 className="text-sm font-semibold tracking-tight text-foreground">
          <FormattedMessage id="dashboard.quickAccess" />
        </h2>

        {cards.length === 0 ? (
          <Card>
            <DataEmpty
              title={intl.formatMessage({ id: 'dashboard.quickAccessEmpty' })}
            />
          </Card>
        ) : (
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-3">
            {cards.map((item) => {
              const Icon = item.icon;
              const descriptionKey = item.path
                ? CARD_DESCRIPTION_KEY[item.path]
                : undefined;

              return (
                <Link
                  key={item.path}
                  to={item.path ?? '/'}
                  className="group flex items-start gap-4 rounded-xl border border-border bg-card p-5 shadow-xs outline-none transition-colors hover:border-primary/40 hover:bg-accent/40 focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/30"
                >
                  {Icon ? (
                    <span
                      aria-hidden="true"
                      className="flex size-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary [&_svg]:size-5"
                    >
                      <Icon />
                    </span>
                  ) : null}
                  <div className="flex min-w-0 flex-col gap-1">
                    <span className="flex items-center gap-1.5 font-medium text-foreground">
                      {item.title ? (
                        <FormattedMessage id={item.title} />
                      ) : null}
                      <ArrowRight
                        aria-hidden="true"
                        className="size-4 text-muted-foreground transition-transform group-hover:translate-x-0.5"
                      />
                    </span>
                    {descriptionKey ? (
                      <span className="text-sm text-muted-foreground">
                        <FormattedMessage id={descriptionKey} />
                      </span>
                    ) : null}
                  </div>
                </Link>
              );
            })}
          </div>
        )}
      </section>
    </div>
  );
}
