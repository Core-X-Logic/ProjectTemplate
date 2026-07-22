import { ArrowRight, Users } from 'lucide-react';
import { FormattedMessage, useIntl } from 'react-intl';
import { Link } from 'react-router-dom';
import { usePermission } from '@/auth/rbac';
import { Badge } from '@/components/ui/badge';
import { Widget } from '@/components/widgets/widget';
import {
  WidgetEmpty,
  WidgetError,
  WidgetSkeleton,
} from '@/components/widgets/widget-states';
import { RECENT_USERS_SIZE, useRecentUsers } from '../hooks';

/**
 * Recent users — the newest accounts (`users.read`; hidden and query-less
 * without it). Sorted server-side on the `User.createdAt` entity property
 * (`sort=createdAt,desc` — the DTO does not carry the timestamp, the ORDER BY
 * happens in the database). Rows link to `/users`, whose route guard + backend
 * enforce the same permission.
 */
export function RecentUsersWidget({ className }: { className?: string }) {
  const intl = useIntl();
  const canUsers = usePermission('users.read');
  const query = useRecentUsers(canUsers);

  if (!canUsers) {
    return null;
  }

  const users = query.data ?? [];

  return (
    <Widget
      title={<FormattedMessage id="dashboard.recentUsers.title" />}
      description={<FormattedMessage id="dashboard.recentUsers.description" />}
      icon={<Users />}
      onRefresh={() => void query.refetch()}
      isRefreshing={query.isRefetching}
      className={className}
      footer={
        <Link
          to="/users"
          className="inline-flex items-center gap-1.5 rounded-md text-sm font-medium text-primary outline-none hover:underline focus-visible:ring-[3px] focus-visible:ring-ring/30"
        >
          <FormattedMessage id="dashboard.recentUsers.viewAll" />
          <ArrowRight aria-hidden="true" className="size-3.5" />
        </Link>
      }
    >
      {query.isLoading ? (
        <WidgetSkeleton variant="list" rows={RECENT_USERS_SIZE} />
      ) : query.isError ? (
        <WidgetError
          message={intl.formatMessage({ id: 'dashboard.recentUsers.error' })}
          onRetry={() => void query.refetch()}
        />
      ) : users.length === 0 ? (
        <WidgetEmpty
          icon={<Users />}
          title={intl.formatMessage({ id: 'dashboard.recentUsers.empty' })}
        />
      ) : (
        <ul className="flex flex-col divide-y divide-border">
          {users.map((user) => (
            <li key={user.id}>
              <Link
                to="/users"
                className="-mx-2 flex items-center gap-3 rounded-md px-2 py-2.5 outline-none transition-colors hover:bg-accent/40 focus-visible:ring-[3px] focus-visible:ring-ring/30"
              >
                <span
                  aria-hidden="true"
                  className="flex size-8 shrink-0 items-center justify-center rounded-full bg-primary/10 text-xs font-semibold uppercase text-primary"
                >
                  {(user.username ?? '?').charAt(0)}
                </span>
                <span className="flex min-w-0 flex-1 flex-col">
                  <span className="truncate text-sm font-medium text-foreground">
                    {user.username ?? '—'}
                  </span>
                  <span className="truncate text-xs text-muted-foreground">
                    {user.email ?? '—'}
                  </span>
                </span>
                <Badge
                  variant={user.active ? 'success' : 'destructive'}
                  appearance="light"
                  size="sm"
                >
                  <FormattedMessage
                    id={
                      user.active
                        ? 'users.status.active'
                        : 'users.status.inactive'
                    }
                  />
                </Badge>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </Widget>
  );
}
