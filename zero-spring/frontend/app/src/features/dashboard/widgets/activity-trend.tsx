import { useMemo } from 'react';
import { TrendingUp } from 'lucide-react';
import { FormattedMessage, useIntl } from 'react-intl';
import {
  Area,
  AreaChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { usePermission } from '@/auth/rbac';
import { Widget } from '@/components/widgets/widget';
import {
  WidgetEmpty,
  WidgetError,
  WidgetSkeleton,
} from '@/components/widgets/widget-states';
import type { AuditLogDto } from '@/features/audit/types';
import { TREND_DAYS, useActivityTrend } from '../hooks';

/**
 * Activity trend — a single-series area chart of daily audit-log volume over
 * the last 14 days (`auditlogs.read`; without it the widget renders nothing
 * and the query is never sent).
 *
 * The backend has no aggregation endpoint, so the widget fetches one window of
 * raw rows (bounded by `TREND_SAMPLE_SIZE`) and buckets them per LOCAL day
 * client-side. Chart language: primary-colored line, soft gradient fill,
 * horizontal-only muted grid, no legend, popover-tokened tooltip.
 */

export interface TrendPoint {
  /** Local day key, `YYYY-MM-DD`. */
  day: string;
  count: number;
}

function toDayKey(date: Date): string {
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}

/** Buckets audit rows into a contiguous `days`-long daily series ending today. */
export function buildTrendSeries(
  logs: AuditLogDto[],
  days: number,
  now: Date,
): TrendPoint[] {
  const counts = new Map<string, number>();
  const series: TrendPoint[] = [];
  for (let offset = days - 1; offset >= 0; offset--) {
    const date = new Date(
      now.getFullYear(),
      now.getMonth(),
      now.getDate() - offset,
    );
    const key = toDayKey(date);
    counts.set(key, 0);
    series.push({ day: key, count: 0 });
  }
  for (const log of logs) {
    if (!log.executionTime) {
      continue;
    }
    const key = toDayKey(new Date(log.executionTime));
    if (counts.has(key)) {
      counts.set(key, (counts.get(key) ?? 0) + 1);
    }
  }
  return series.map((point) => ({
    ...point,
    count: counts.get(point.day) ?? 0,
  }));
}

/** Parses a local `YYYY-MM-DD` key back into a Date (local midnight). */
function fromDayKey(day: string): Date {
  const [year, month, date] = day.split('-').map(Number);
  return new Date(year, month - 1, date);
}

interface TrendTooltipProps {
  active?: boolean;
  label?: string;
  payload?: Array<{ value?: number }>;
}

/** Recharts tooltip themed with the card/popover tokens (no default styles). */
function TrendTooltip({ active, label, payload }: TrendTooltipProps) {
  const intl = useIntl();
  if (!active || !payload || payload.length === 0 || !label) {
    return null;
  }
  return (
    <div className="rounded-lg border border-border bg-popover px-3 py-2 text-xs shadow-md">
      <p className="font-medium text-popover-foreground">
        {intl.formatDate(fromDayKey(label), {
          month: 'short',
          day: 'numeric',
        })}
      </p>
      <p className="text-muted-foreground">
        <FormattedMessage
          id="dashboard.trend.tooltip"
          values={{ count: payload[0]?.value ?? 0 }}
        />
      </p>
    </div>
  );
}

const GRADIENT_ID = 'dashboard-trend-fill';

export function ActivityTrendWidget({ className }: { className?: string }) {
  const intl = useIntl();
  const canAudit = usePermission('auditlogs.read');
  const query = useActivityTrend(canAudit);

  const rows = query.data?.rows;
  const series = useMemo(
    () => buildTrendSeries(rows ?? [], TREND_DAYS, new Date()),
    [rows],
  );
  const total = useMemo(
    () => series.reduce((sum, point) => sum + point.count, 0),
    [series],
  );
  // The server clamps page size (TREND_SAMPLE_SIZE); when the 14-day window
  // holds more rows than the sample, say so instead of charting silently.
  const sampled =
    query.data != null && query.data.totalElements > query.data.rows.length;

  if (!canAudit) {
    return null;
  }

  return (
    <Widget
      title={<FormattedMessage id="dashboard.trend.title" />}
      description={<FormattedMessage id="dashboard.trend.description" />}
      icon={<TrendingUp />}
      onRefresh={() => void query.refetch()}
      isRefreshing={query.isRefetching}
      className={className}
      footer={
        sampled ? (
          <p className="text-xs text-muted-foreground">
            <FormattedMessage
              id="dashboard.trend.sampled"
              values={{
                sample: query.data?.rows.length ?? 0,
                total: query.data?.totalElements ?? 0,
              }}
            />
          </p>
        ) : undefined
      }
    >
      {query.isLoading ? (
        <WidgetSkeleton variant="chart" />
      ) : query.isError ? (
        <WidgetError
          message={intl.formatMessage({ id: 'dashboard.trend.error' })}
          onRetry={() => void query.refetch()}
        />
      ) : total === 0 ? (
        <WidgetEmpty
          icon={<TrendingUp />}
          title={intl.formatMessage({ id: 'dashboard.trend.empty' })}
        />
      ) : (
        <>
          <p className="sr-only">
            <FormattedMessage
              id="dashboard.trend.summary"
              values={{ total }}
            />
          </p>
          <div
            role="img"
            aria-label={intl.formatMessage({ id: 'dashboard.trend.aria' })}
            className="h-56 w-full"
          >
            <ResponsiveContainer width="100%" height="100%">
              <AreaChart
                data={series}
                margin={{ top: 8, right: 8, bottom: 0, left: 0 }}
              >
                <defs>
                  <linearGradient id={GRADIENT_ID} x1="0" y1="0" x2="0" y2="1">
                    <stop
                      offset="0%"
                      stopColor="var(--primary)"
                      stopOpacity={0.15}
                    />
                    <stop
                      offset="100%"
                      stopColor="var(--primary)"
                      stopOpacity={0}
                    />
                  </linearGradient>
                </defs>
                <CartesianGrid
                  vertical={false}
                  stroke="var(--border)"
                  strokeDasharray="3 3"
                />
                <XAxis
                  dataKey="day"
                  tickLine={false}
                  axisLine={false}
                  interval="preserveStartEnd"
                  tick={{ fontSize: 11, fill: 'var(--muted-foreground)' }}
                  tickFormatter={(day: string) =>
                    intl.formatDate(fromDayKey(day), {
                      month: 'short',
                      day: 'numeric',
                    })
                  }
                />
                <YAxis
                  width={32}
                  allowDecimals={false}
                  tickLine={false}
                  axisLine={false}
                  tick={{ fontSize: 11, fill: 'var(--muted-foreground)' }}
                />
                <Tooltip
                  content={<TrendTooltip />}
                  cursor={{ stroke: 'var(--border)' }}
                />
                <Area
                  type="monotone"
                  dataKey="count"
                  stroke="var(--primary)"
                  strokeWidth={2}
                  fill={`url(#${GRADIENT_ID})`}
                  activeDot={{ r: 3 }}
                />
              </AreaChart>
            </ResponsiveContainer>
          </div>
        </>
      )}
    </Widget>
  );
}
