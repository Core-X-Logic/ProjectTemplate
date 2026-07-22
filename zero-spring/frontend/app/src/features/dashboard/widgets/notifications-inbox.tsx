import { ArrowRight, Bell } from 'lucide-react';
import { FormattedMessage, FormattedRelativeTime, useIntl } from 'react-intl';
import { Link } from 'react-router-dom';
import { Widget } from '@/components/widgets/widget';
import {
  WidgetEmpty,
  WidgetError,
  WidgetSkeleton,
} from '@/components/widgets/widget-states';
import type { NotificationDto } from '@/features/notifications/types';
import { INBOX_SIZE, useInbox } from '../hooks';

/**
 * Operations inbox — the caller's latest notifications (auth only, no
 * permission gate: the endpoint is scoped to the current user). Unread rows
 * carry a primary dot; severity is a colored left border via the notification
 * `level`. Rows link to `/notifications` where read/mark-all actions live —
 * the widget is a viewport, not a second inbox implementation.
 */

const LEVEL_BORDER: Record<string, string> = {
  ERROR: 'border-l-destructive',
  WARNING: 'border-l-amber-500',
  SUCCESS: 'border-l-emerald-500',
  INFO: 'border-l-primary/40',
};

/** Seconds from `createdAt` to now, clamped for FormattedRelativeTime. */
function secondsAgo(createdAt?: string): number {
  if (!createdAt) {
    return 0;
  }
  return Math.min(
    0,
    Math.round((new Date(createdAt).getTime() - Date.now()) / 1000),
  );
}

function InboxRow({ notification }: { notification: NotificationDto }) {
  const seconds = secondsAgo(notification.createdAt);
  return (
    <li>
      <Link
        to="/notifications"
        className={`-mx-2 flex items-start gap-3 rounded-md border-l-2 px-2 py-2.5 outline-none transition-colors hover:bg-accent/40 focus-visible:ring-[3px] focus-visible:ring-ring/30 ${
          LEVEL_BORDER[notification.level ?? 'INFO'] ?? LEVEL_BORDER.INFO
        }`}
      >
        <span className="flex min-w-0 flex-1 flex-col gap-0.5">
          <span className="flex items-center gap-2">
            {!notification.isRead ? (
              <span
                aria-hidden="true"
                className="size-1.5 shrink-0 rounded-full bg-primary"
              />
            ) : null}
            <span
              className={`truncate text-sm ${
                notification.isRead
                  ? 'text-muted-foreground'
                  : 'font-medium text-foreground'
              }`}
            >
              {notification.title ?? '—'}
            </span>
          </span>
          {notification.body ? (
            <span className="truncate text-xs text-muted-foreground">
              {notification.body}
            </span>
          ) : null}
        </span>
        {notification.createdAt ? (
          <span className="shrink-0 whitespace-nowrap text-xs text-muted-foreground">
            <FormattedRelativeTime
              value={seconds}
              updateIntervalInSeconds={undefined}
              numeric="auto"
              style="short"
            />
          </span>
        ) : null}
      </Link>
    </li>
  );
}

export function NotificationsInboxWidget({
  className,
}: {
  className?: string;
}) {
  const intl = useIntl();
  const query = useInbox(true);

  const rows = query.data ?? [];

  return (
    <Widget
      title={<FormattedMessage id="dashboard.inbox.title" />}
      description={<FormattedMessage id="dashboard.inbox.description" />}
      icon={<Bell />}
      onRefresh={() => void query.refetch()}
      isRefreshing={query.isRefetching}
      className={className}
      footer={
        <Link
          to="/notifications"
          className="inline-flex items-center gap-1.5 rounded-md text-sm font-medium text-primary outline-none hover:underline focus-visible:ring-[3px] focus-visible:ring-ring/30"
        >
          <FormattedMessage id="dashboard.inbox.viewAll" />
          <ArrowRight aria-hidden="true" className="size-3.5" />
        </Link>
      }
    >
      {query.isLoading ? (
        <WidgetSkeleton variant="list" rows={INBOX_SIZE} />
      ) : query.isError ? (
        <WidgetError
          message={intl.formatMessage({ id: 'dashboard.inbox.error' })}
          onRetry={() => void query.refetch()}
        />
      ) : rows.length === 0 ? (
        <WidgetEmpty
          icon={<Bell />}
          title={intl.formatMessage({ id: 'dashboard.inbox.empty' })}
        />
      ) : (
        <ul className="flex flex-col divide-y divide-border">
          {rows.map((notification) => (
            <InboxRow key={notification.id} notification={notification} />
          ))}
        </ul>
      )}
    </Widget>
  );
}
