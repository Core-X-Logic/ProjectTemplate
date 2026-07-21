import type { ReactNode } from 'react';
import { cn } from '@/lib/utils';

export interface PageHeaderProps {
  /** The page title. Rendered as the page's single semantic `<h1>`. */
  title: ReactNode;
  /** Optional one-line description under the title. */
  description?: ReactNode;
  /** Right-aligned action slot (primary action clear); wraps below on mobile. */
  actions?: ReactNode;
  /** Optional breadcrumb slot, rendered above the title. */
  breadcrumb?: ReactNode;
  className?: string;
}

/**
 * The single page-title standard for admin screens.
 *
 * Emits a real `<h1>` (text-2xl / semibold / tight tracking) so every page has
 * exactly one top-level heading — the app-wide a11y + hierarchy anchor. The
 * heading personality comes from scale + weight + tracking, not a bundled font.
 *
 * Responsive: title and actions sit on one row from `sm` up; on narrow
 * viewports the actions wrap onto their own row below the title.
 */
export function PageHeader({
  title,
  description,
  actions,
  breadcrumb,
  className,
}: PageHeaderProps) {
  return (
    <div className={cn('mb-6 flex flex-col gap-4', className)}>
      {breadcrumb ? <div>{breadcrumb}</div> : null}
      <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-center">
        <div className="flex min-w-0 flex-col gap-1">
          <h1 className="text-2xl font-semibold tracking-tight text-foreground">
            {title}
          </h1>
          {description ? (
            <p className="text-sm text-muted-foreground">{description}</p>
          ) : null}
        </div>
        {actions ? (
          <div className="flex flex-wrap items-center gap-2.5 sm:justify-end">
            {actions}
          </div>
        ) : null}
      </div>
    </div>
  );
}
