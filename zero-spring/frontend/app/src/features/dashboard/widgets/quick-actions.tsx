import { useMemo } from 'react';
import { ArrowRight, LayoutGrid } from 'lucide-react';
import { FormattedMessage, useIntl } from 'react-intl';
import { Link } from 'react-router-dom';
import { useSidebarMenu } from '@/config/menu.config';
import type { MenuItem } from '@/config/types';
import { Widget } from '@/components/widgets/widget';
import { WidgetEmpty } from '@/components/widgets/widget-states';

/**
 * Quick actions — the original dashboard quick-access grid as a compact
 * widget. The cards derive from the SAME permission-filtered sidebar menu the
 * shell renders (`useSidebarMenu`), so a card only exists when the user
 * already holds the permission for that destination (and the route guard +
 * backend enforce it on arrival). Same i18n keys as before — nothing renamed.
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

export function QuickActionsWidget({ className }: { className?: string }) {
  const intl = useIntl();
  const menu = useSidebarMenu();
  const cards = useMemo(() => toLeaves(menu), [menu]);

  return (
    <Widget
      title={<FormattedMessage id="dashboard.quickAccess" />}
      icon={<LayoutGrid />}
      className={className}
    >
      {cards.length === 0 ? (
        <WidgetEmpty
          icon={<LayoutGrid />}
          title={intl.formatMessage({ id: 'dashboard.quickAccessEmpty' })}
        />
      ) : (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-3">
          {cards.map((item) => {
            const Icon = item.icon;
            const descriptionKey = item.path
              ? CARD_DESCRIPTION_KEY[item.path]
              : undefined;

            return (
              <Link
                key={item.path}
                to={item.path ?? '/'}
                className="group flex items-start gap-3 rounded-lg border border-border bg-card p-4 shadow-xs outline-none transition-colors hover:border-primary/40 hover:bg-accent/40 focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/30"
              >
                {Icon ? (
                  <span
                    aria-hidden="true"
                    className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary [&_svg]:size-4.5"
                  >
                    <Icon />
                  </span>
                ) : null}
                <span className="flex min-w-0 flex-col gap-0.5">
                  <span className="flex items-center gap-1.5 text-sm font-medium text-foreground">
                    {item.title ? <FormattedMessage id={item.title} /> : null}
                    <ArrowRight
                      aria-hidden="true"
                      className="size-3.5 text-muted-foreground transition-transform group-hover:translate-x-0.5"
                    />
                  </span>
                  {descriptionKey ? (
                    // Compact: the one-liner is hidden below `sm`.
                    <span className="hidden text-xs text-muted-foreground sm:block">
                      <FormattedMessage id={descriptionKey} />
                    </span>
                  ) : null}
                </span>
              </Link>
            );
          })}
        </div>
      )}
    </Widget>
  );
}
