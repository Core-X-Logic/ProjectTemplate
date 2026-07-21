import type { ReactNode } from 'react';
import { RotateCw, TriangleAlert } from 'lucide-react';
import { FormattedMessage } from 'react-intl';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';

/**
 * Standardized async-state bodies for list/detail screens (the "four states":
 * loading, error, empty, filled). Copy is passed in by callers so each feature
 * keeps ownership of its i18n keys; these components only own the layout.
 *
 * Accessibility: decorative icons are `aria-hidden`, the error surface is a live
 * `role="alert"`, and Retry is a real focusable `<button>` (branded focus ring).
 */

export interface DataEmptyProps {
  /** Decorative icon (rendered inside a muted circle, hidden from AT). */
  icon?: ReactNode;
  /** One-line invitation. */
  title: ReactNode;
  /** Optional short supporting sentence. */
  description?: ReactNode;
  /** Optional primary action (e.g. a guarded "Create" button). */
  action?: ReactNode;
  className?: string;
}

/** Empty state: an invitation to act — icon + title + optional description/action. */
export function DataEmpty({
  icon,
  title,
  description,
  action,
  className,
}: DataEmptyProps) {
  return (
    <div
      className={cn(
        'flex flex-col items-center justify-center gap-3 px-6 py-14 text-center',
        className,
      )}
    >
      {icon ? (
        <div
          aria-hidden="true"
          className="flex size-12 items-center justify-center rounded-full bg-muted text-muted-foreground [&_svg]:size-6"
        >
          {icon}
        </div>
      ) : null}
      <div className="flex flex-col gap-1">
        <p className="text-base font-semibold text-foreground">{title}</p>
        {description ? (
          <p className="mx-auto max-w-sm text-sm text-muted-foreground">
            {description}
          </p>
        ) : null}
      </div>
      {action ? <div className="mt-1">{action}</div> : null}
    </div>
  );
}

export interface DataErrorProps {
  /** What happened, in the user's language. */
  message: ReactNode;
  /** Optional retry handler; renders a Retry button when provided. */
  onRetry?: () => void;
  /** Overrides the default `common.retry` label. */
  retryLabel?: ReactNode;
  className?: string;
}

/** Error state: what happened + an optional Retry. Announced via `role="alert"`. */
export function DataError({
  message,
  onRetry,
  retryLabel,
  className,
}: DataErrorProps) {
  return (
    <div
      role="alert"
      className={cn(
        'flex flex-col items-center justify-center gap-3 px-6 py-14 text-center',
        className,
      )}
    >
      <div
        aria-hidden="true"
        className="flex size-12 items-center justify-center rounded-full bg-destructive/10 text-destructive [&_svg]:size-6"
      >
        <TriangleAlert />
      </div>
      <p className="max-w-sm text-sm text-destructive">{message}</p>
      {onRetry ? (
        <Button variant="outline" size="sm" onClick={onRetry}>
          <RotateCw />
          {retryLabel ?? <FormattedMessage id="common.retry" />}
        </Button>
      ) : null}
    </div>
  );
}

export interface TableSkeletonProps {
  /** Placeholder body rows. */
  rows?: number;
  /** Placeholder columns per row. */
  cols?: number;
  className?: string;
}

/** Loading state for tabular bodies — skeleton rows that echo a DataGrid. */
export function TableSkeleton({
  rows = 5,
  cols = 4,
  className,
}: TableSkeletonProps) {
  return (
    <div
      className={cn(
        'divide-y divide-border overflow-hidden rounded-lg border border-border',
        className,
      )}
    >
      <span className="sr-only" role="status">
        <FormattedMessage id="common.loading" />
      </span>
      <div
        aria-hidden="true"
        className="flex items-center gap-4 bg-muted/40 px-4 py-3"
      >
        {Array.from({ length: cols }).map((_, col) => (
          <Skeleton key={col} className="h-3.5 flex-1" />
        ))}
      </div>
      {Array.from({ length: rows }).map((_, row) => (
        <div
          key={row}
          aria-hidden="true"
          className="flex items-center gap-4 px-4 py-3.5"
        >
          {Array.from({ length: cols }).map((_, col) => (
            <Skeleton key={col} className="h-4 flex-1" />
          ))}
        </div>
      ))}
    </div>
  );
}
