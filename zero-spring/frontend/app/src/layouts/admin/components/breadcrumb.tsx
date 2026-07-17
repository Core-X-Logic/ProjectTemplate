import { Fragment } from 'react';
import { ChevronRight } from 'lucide-react';
import { useIntl } from 'react-intl';
import { useLocation } from 'react-router';
import { useSidebarMenu } from '@/config/menu.config';
import { MenuItem } from '@/config/types';
import { cn } from '@/lib/utils';
import { useMenu } from '@/hooks/use-menu';

export function Breadcrumb() {
  const { pathname } = useLocation();
  const intl = useIntl();
  const menu = useSidebarMenu();
  const { getBreadcrumb, isActive } = useMenu(pathname);
  const items: MenuItem[] = getBreadcrumb(menu);

  if (items.length === 0) {
    return null;
  }

  const label = (title?: string): string =>
    title ? intl.formatMessage({ id: title, defaultMessage: title }) : '';

  return (
    <div className="flex items-center gap-1.25 text-xs lg:text-sm font-medium mb-2.5 lg:mb-0">
      {items.map((item, index) => {
        const last = index === items.length - 1;
        const active = item.path ? isActive(item.path) : false;

        return (
          <Fragment key={`root-${index}`}>
            <span
              className={cn(active ? 'text-mono' : 'text-secondary-foreground')}
              key={`item-${index}`}
            >
              {label(item.title)}
            </span>
            {!last && (
              <ChevronRight
                className="size-3.5 text-muted-foreground"
                key={`separator-${index}`}
              />
            )}
          </Fragment>
        );
      })}
    </div>
  );
}
