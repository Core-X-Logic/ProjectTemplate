import { ReactNode, useId } from 'react';
import { RotateCw } from 'lucide-react';
import { useIntl } from 'react-intl';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';

/**
 * Shared dashboard widget container (the modular-widget standard).
 *
 * Every dashboard tile renders through this frame so the visual language
 * (rounded-xl card, header row, optional footer) and the accessibility contract
 * (`<section aria-labelledby>` + a real heading element) stay identical across
 * widgets. Data, states and permissions belong to the CALLER — this component
 * owns layout only.
 */
export interface WidgetProps {
  /** Widget heading — rendered as a semantic `<h2>` under the page's `<h1>`. */
  title: ReactNode;
  /** Optional one-line subtitle under the heading. */
  description?: ReactNode;
  /** Decorative leading icon (hidden from AT). */
  icon?: ReactNode;
  /** Right-aligned header slot (filters, links…). */
  actions?: ReactNode;
  /** When provided, renders the refresh icon-button in the header. */
  onRefresh?: () => void;
  /** Spins the refresh icon and disables the button while `true`. */
  isRefreshing?: boolean;
  /** Optional footer, separated by a top border. */
  footer?: ReactNode;
  children: ReactNode;
  className?: string;
}

export function Widget({
  title,
  description,
  icon,
  actions,
  onRefresh,
  isRefreshing = false,
  footer,
  children,
  className,
}: WidgetProps) {
  const intl = useIntl();
  const headingId = useId();

  return (
    <section
      aria-labelledby={headingId}
      className={cn(
        'flex flex-col rounded-xl border border-border bg-card shadow-xs',
        className,
      )}
    >
      <div className="flex items-start justify-between gap-3 px-5 pb-3 pt-4">
        <div className="flex min-w-0 items-center gap-2.5">
          {icon ? (
            <span
              aria-hidden="true"
              className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary [&_svg]:size-4"
            >
              {icon}
            </span>
          ) : null}
          <div className="flex min-w-0 flex-col">
            <h2
              id={headingId}
              className="truncate text-sm font-semibold tracking-tight text-foreground"
            >
              {title}
            </h2>
            {description ? (
              <p className="truncate text-xs text-muted-foreground">
                {description}
              </p>
            ) : null}
          </div>
        </div>
        <div className="flex shrink-0 items-center gap-1.5">
          {actions}
          {onRefresh ? (
            <Button
              variant="ghost"
              mode="icon"
              size="sm"
              onClick={onRefresh}
              disabled={isRefreshing}
              aria-label={intl.formatMessage({ id: 'common.refresh' })}
              className="focus-visible:ring-[3px] focus-visible:ring-ring/30"
            >
              <RotateCw
                aria-hidden="true"
                className={cn(isRefreshing && 'animate-spin')}
              />
            </Button>
          ) : null}
        </div>
      </div>
      <div className="min-w-0 flex-1 px-5 pb-5">{children}</div>
      {footer ? (
        <div className="border-t border-border px-5 py-3">{footer}</div>
      ) : null}
    </section>
  );
}
