import type { ReactNode } from 'react';
import { FormattedMessage } from 'react-intl';
import {
  DataEmpty,
  DataError,
  type DataEmptyProps,
  type DataErrorProps,
} from '@/components/common/data-state';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';

/**
 * Async-state bodies sized for a `Widget` (the "four states" standard, tuned to
 * a tile instead of a full page). Loading is a shape-appropriate skeleton;
 * empty/error reuse the app-wide `DataEmpty` / `DataError` with tighter
 * vertical padding so the widget grid does not jump.
 */

export type WidgetSkeletonVariant = 'kpi' | 'chart' | 'list';

export interface WidgetSkeletonProps {
  variant: WidgetSkeletonVariant;
  /** Placeholder rows for the `list` variant. */
  rows?: number;
  className?: string;
}

/** Loading state — skeleton silhouette matching the widget's content shape. */
export function WidgetSkeleton({
  variant,
  rows = 5,
  className,
}: WidgetSkeletonProps) {
  return (
    <div className={className}>
      <span className="sr-only" role="status">
        <FormattedMessage id="common.loading" />
      </span>
      {variant === 'kpi' ? (
        <div aria-hidden="true" className="flex flex-col gap-2">
          <Skeleton className="h-7 w-16" />
          <Skeleton className="h-3.5 w-24" />
        </div>
      ) : variant === 'chart' ? (
        <Skeleton aria-hidden="true" className="h-56 w-full" />
      ) : (
        <div aria-hidden="true" className="flex flex-col gap-3">
          {Array.from({ length: rows }).map((_, row) => (
            <div key={row} className="flex items-center gap-3">
              <Skeleton className="size-8 shrink-0 rounded-full" />
              <div className="flex flex-1 flex-col gap-1.5">
                <Skeleton className="h-3.5 w-1/3" />
                <Skeleton className="h-3 w-1/2" />
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

export interface WidgetErrorProps extends Omit<DataErrorProps, 'onRetry'> {
  /** Retry handler — wire to the widget query's `refetch`. */
  onRetry: () => void;
}

/** Error state — `DataError` (role="alert" + Retry) with widget-fit padding. */
export function WidgetError({ className, ...props }: WidgetErrorProps) {
  return <DataError {...props} className={cn('py-8', className)} />;
}

export type WidgetEmptyProps = DataEmptyProps & { children?: ReactNode };

/** Empty state — `DataEmpty` with widget-fit padding. */
export function WidgetEmpty({ className, ...props }: WidgetEmptyProps) {
  return <DataEmpty {...props} className={cn('py-8', className)} />;
}
