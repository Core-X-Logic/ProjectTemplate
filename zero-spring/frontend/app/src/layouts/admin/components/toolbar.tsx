import { Fragment, ReactNode } from 'react';
import { ChevronRight } from 'lucide-react';
import { useIntl } from 'react-intl';
import { Link, useLocation } from 'react-router-dom';
import { useSidebarMenu } from '@/config/menu.config';
import { MenuItem } from '@/config/types';
import { cn } from '@/lib/utils';
import { useMenu } from '@/hooks/use-menu';

export interface ToolbarHeadingProps {
  title?: string | ReactNode;
  description?: string | ReactNode;
}

function Toolbar({ children }: { children?: ReactNode }) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-5 pb-7.5">
      {children}
    </div>
  );
}

function ToolbarActions({ children }: { children?: ReactNode }) {
  return <div className="flex items-center gap-2.5">{children}</div>;
}

function ToolbarBreadcrumbs() {
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
    <div className="flex [.header_&]:below-lg:hidden items-center gap-1.25 text-xs lg:text-sm font-medium mb-2.5 lg:mb-0">
      <div className="breadcrumb flex items-center gap-1">
        {items.map((item, index) => {
          const isLast = index === items.length - 1;
          const active = item.path ? isActive(item.path) : false;

          return (
            <Fragment key={index}>
              {item.path ? (
                <Link
                  to={item.path}
                  className={cn(
                    'flex items-center gap-1',
                    active
                      ? 'text-mono'
                      : 'text-muted-foreground hover:text-primary',
                  )}
                >
                  {label(item.title)}
                </Link>
              ) : (
                <span
                  className={cn(isLast ? 'text-mono' : 'text-muted-foreground')}
                >
                  {label(item.title)}
                </span>
              )}
              {!isLast && (
                <ChevronRight className="size-3.5 muted-foreground" />
              )}
            </Fragment>
          );
        })}
      </div>
    </div>
  );
}

function ToolbarHeading({ children }: { children: ReactNode }) {
  return <div className="flex flex-col justify-center gap-2">{children}</div>;
}

function ToolbarPageTitle({ children }: { children?: string }) {
  const { pathname } = useLocation();
  const intl = useIntl();
  const menu = useSidebarMenu();
  const { getCurrentItem } = useMenu(pathname);
  const item = getCurrentItem(menu);
  const title = item?.title
    ? intl.formatMessage({ id: item.title, defaultMessage: item.title })
    : 'Untitled';

  return (
    <h1 className="text-xl font-medium leading-none text-mono">
      {children ? children : title}
    </h1>
  );
}

function ToolbarDescription({ children }: { children: ReactNode }) {
  return (
    <div className="flex items-center gap-2 text-sm font-normal text-secondary-foreground">
      {children}
    </div>
  );
}

export {
  Toolbar,
  ToolbarActions,
  ToolbarBreadcrumbs,
  ToolbarHeading,
  ToolbarPageTitle,
  ToolbarDescription,
};
