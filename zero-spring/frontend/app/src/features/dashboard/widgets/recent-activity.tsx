import { ScrollText } from 'lucide-react';
import { FormattedMessage, FormattedRelativeTime, useIntl } from 'react-intl';
import { usePermission } from '@/auth/rbac';
import { Badge } from '@/components/ui/badge';
import { Widget } from '@/components/widgets/widget';
import {
  WidgetEmpty,
  WidgetError,
  WidgetSkeleton,
} from '@/components/widgets/widget-states';
import { RECENT_ACTIVITY_SIZE, useRecentActivity } from '../hooks';

/**
 * Recent activity — the latest audit entries as a left-lined timeline
 * (`auditlogs.read`; hidden and query-less without it). Each entry shows the
 * acting user, HTTP verb + method, a status-colored badge, the duration and an
 * intl-relative timestamp.
 */

/** Status badge color class: 2xx → success, 4xx → warning, 5xx → destructive. */
function statusVariant(
  status?: number,
): 'success' | 'warning' | 'destructive' | 'secondary' {
  if (status === undefined) {
    return 'secondary';
  }
  if (status >= 500) {
    return 'destructive';
  }
  if (status >= 400) {
    return 'warning';
  }
  if (status >= 200 && status < 300) {
    return 'success';
  }
  return 'secondary';
}

/**
 * Best-unit relative time (no auto-updating interval — the dashboard does not
 * poll, so the label is a snapshot like every other number on the page).
 */
function RelativeTime({ value }: { value: string }) {
  const deltaSeconds = Math.round((new Date(value).getTime() - Date.now()) / 1000);
  const abs = Math.abs(deltaSeconds);
  if (abs < 60) {
    return (
      <FormattedRelativeTime value={deltaSeconds} unit="second" numeric="auto" />
    );
  }
  if (abs < 3600) {
    return (
      <FormattedRelativeTime
        value={Math.round(deltaSeconds / 60)}
        unit="minute"
        numeric="auto"
      />
    );
  }
  if (abs < 86_400) {
    return (
      <FormattedRelativeTime
        value={Math.round(deltaSeconds / 3600)}
        unit="hour"
        numeric="auto"
      />
    );
  }
  return (
    <FormattedRelativeTime
      value={Math.round(deltaSeconds / 86_400)}
      unit="day"
      numeric="auto"
    />
  );
}

export function RecentActivityWidget({ className }: { className?: string }) {
  const intl = useIntl();
  const canAudit = usePermission('auditlogs.read');
  const query = useRecentActivity(canAudit);

  if (!canAudit) {
    return null;
  }

  const logs = query.data ?? [];

  return (
    <Widget
      title={<FormattedMessage id="dashboard.recentActivity.title" />}
      description={
        <FormattedMessage id="dashboard.recentActivity.description" />
      }
      icon={<ScrollText />}
      onRefresh={() => void query.refetch()}
      isRefreshing={query.isRefetching}
      className={className}
    >
      {query.isLoading ? (
        <WidgetSkeleton variant="list" rows={RECENT_ACTIVITY_SIZE} />
      ) : query.isError ? (
        <WidgetError
          message={intl.formatMessage({
            id: 'dashboard.recentActivity.error',
          })}
          onRetry={() => void query.refetch()}
        />
      ) : logs.length === 0 ? (
        <WidgetEmpty
          icon={<ScrollText />}
          title={intl.formatMessage({ id: 'dashboard.recentActivity.empty' })}
        />
      ) : (
        <ol className="ms-1.5 flex flex-col border-s border-border ps-4">
          {logs.map((log) => (
            <li key={log.id} className="relative pb-4 last:pb-0">
              <span
                aria-hidden="true"
                className="absolute -start-[21.5px] top-1.5 size-2.5 rounded-full border-2 border-card bg-primary"
              />
              <div className="flex flex-wrap items-center gap-x-2 gap-y-0.5">
                <span className="text-sm font-medium text-foreground">
                  {log.username ?? '—'}
                </span>
                <span className="truncate text-xs text-muted-foreground">
                  {[log.httpMethod, log.methodName].filter(Boolean).join(' ')}
                </span>
                {log.httpStatusCode !== undefined ? (
                  <Badge
                    variant={statusVariant(log.httpStatusCode)}
                    appearance="light"
                    size="sm"
                  >
                    {log.httpStatusCode}
                  </Badge>
                ) : null}
              </div>
              <div className="mt-0.5 flex flex-wrap items-center gap-x-2 text-xs text-muted-foreground">
                {log.executionTime ? (
                  <RelativeTime value={log.executionTime} />
                ) : null}
                {log.executionDurationMs !== undefined ? (
                  <span className="tabular-nums">
                    <FormattedMessage
                      id="dashboard.recentActivity.duration"
                      values={{ ms: log.executionDurationMs }}
                    />
                  </span>
                ) : null}
              </div>
            </li>
          ))}
        </ol>
      )}
    </Widget>
  );
}
