import { useState } from 'react';
import {
  Bell,
  Check,
  CheckCheck,
  ChevronLeft,
  ChevronRight,
  LoaderCircle,
} from 'lucide-react';
import { Helmet } from 'react-helmet-async';
import { useIntl } from 'react-intl';
import { cn } from '@/lib/utils';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardFooter, CardTable } from '@/components/ui/card';
import {
  DataEmpty,
  DataError,
  TableSkeleton,
} from '@/components/common/data-state';
import { PageHeader } from '@/components/common/page-header';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import {
  useMarkAllRead,
  useMarkRead,
  useNotifications,
  useUnreadCount,
} from '../hooks';
import { useNotificationsMessages } from '../messages';
import type { NotificationLevel } from '../types';

/**
 * Notifications inbox (slice B). User-scoped — any authenticated user may view
 * their own inbox, so the route carries no extra permission (the shell's
 * `<RequireAuth>` already guards authentication; backend `isAuthenticated()`
 * is the enforcing lock).
 */

const LEVEL_BADGE_VARIANT: Record<
  NotificationLevel,
  'info' | 'success' | 'warning' | 'destructive'
> = {
  INFO: 'info',
  SUCCESS: 'success',
  WARNING: 'warning',
  ERROR: 'destructive',
};

const LEVEL_MESSAGE_ID = {
  INFO: 'notifications.level.info',
  SUCCESS: 'notifications.level.success',
  WARNING: 'notifications.level.warning',
  ERROR: 'notifications.level.error',
} as const;

export function NotificationsInboxPage() {
  const intl = useIntl();
  const t = useNotificationsMessages();
  const [page, setPage] = useState(0);

  const { data, isLoading, isError, refetch } = useNotifications(page);
  const { data: unreadCount = 0 } = useUnreadCount();
  const markRead = useMarkRead();
  const markAllRead = useMarkAllRead();

  const notifications = data?.content ?? [];
  const totalPages = data?.totalPages ?? 0;
  const isFirst = data?.first ?? true;
  const isLast = data?.last ?? true;

  return (
    <div className="container-fluid">
      <Helmet>
        <title>{t('notifications.title')}</title>
      </Helmet>

      <PageHeader
        title={
          <span className="flex items-center gap-2">
            {t('notifications.title')}
            {unreadCount > 0 && (
              <Badge variant="primary" appearance="light" size="sm">
                {unreadCount}
              </Badge>
            )}
          </span>
        }
        actions={
          <Button
            variant="outline"
            disabled={markAllRead.isPending}
            onClick={() => markAllRead.mutate()}
          >
            {markAllRead.isPending ? (
              <LoaderCircle className="size-4 animate-spin" />
            ) : (
              <CheckCheck className="size-4" />
            )}
            {t('notifications.markAllRead')}
          </Button>
        }
      />

      <Card>
        <CardTable>
          {isLoading ? (
            <div className="p-5">
              <TableSkeleton rows={6} cols={4} />
            </div>
          ) : isError ? (
            <div className="p-5">
              <DataError
                message={t('notifications.loadError')}
                onRetry={() => refetch()}
              />
            </div>
          ) : notifications.length === 0 ? (
            <DataEmpty
              icon={<Bell />}
              title={t('notifications.empty')}
            />
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('notifications.column.notification')}</TableHead>
                  <TableHead className="w-28">
                    {t('notifications.column.level')}
                  </TableHead>
                  <TableHead className="w-44">
                    {t('notifications.column.date')}
                  </TableHead>
                  <TableHead className="w-40 text-end" />
                </TableRow>
              </TableHeader>
              <TableBody>
                {notifications.map((notification, index) => {
                  const level = notification.level ?? 'INFO';
                  const unread = notification.isRead === false;

                  return (
                    <TableRow
                      key={notification.id ?? index}
                      data-unread={unread ? 'true' : undefined}
                      className={cn(unread && 'bg-accent/50')}
                    >
                      <TableCell>
                        <div className="flex items-start gap-2.5">
                          {unread && (
                            <span
                              aria-hidden
                              className="mt-1.5 size-2 shrink-0 rounded-full bg-primary"
                            />
                          )}
                          <div className="flex flex-col gap-0.5">
                            <span
                              className={cn(
                                'text-sm text-mono',
                                unread ? 'font-semibold' : 'font-normal',
                              )}
                            >
                              {notification.title}
                            </span>
                            {notification.body && (
                              <span className="text-xs text-muted-foreground">
                                {notification.body}
                              </span>
                            )}
                          </div>
                        </div>
                      </TableCell>
                      <TableCell>
                        <Badge
                          variant={LEVEL_BADGE_VARIANT[level]}
                          appearance="light"
                          size="sm"
                        >
                          {t(LEVEL_MESSAGE_ID[level])}
                        </Badge>
                      </TableCell>
                      <TableCell className="text-xs text-muted-foreground">
                        {notification.createdAt
                          ? intl.formatDate(new Date(notification.createdAt), {
                              dateStyle: 'medium',
                              timeStyle: 'short',
                            })
                          : '—'}
                      </TableCell>
                      <TableCell className="text-end">
                        {unread && (
                          <Button
                            variant="ghost"
                            size="sm"
                            disabled={markRead.isPending}
                            onClick={() => {
                              if (notification.id !== undefined) {
                                markRead.mutate(notification.id);
                              }
                            }}
                          >
                            <Check className="size-4" />
                            {t('notifications.markRead')}
                          </Button>
                        )}
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          )}
        </CardTable>

        {totalPages > 1 && (
          <CardFooter className="justify-between gap-2.5">
            <span className="text-xs text-muted-foreground">
              {t('notifications.pageInfo', {
                page: page + 1,
                total: totalPages,
              })}
            </span>
            <div className="flex items-center gap-1.5">
              <Button
                variant="outline"
                mode="icon"
                size="sm"
                disabled={isFirst}
                aria-label="previous page"
                onClick={() => setPage((current) => Math.max(0, current - 1))}
              >
                <ChevronLeft className="size-4" />
              </Button>
              <Button
                variant="outline"
                mode="icon"
                size="sm"
                disabled={isLast}
                aria-label="next page"
                onClick={() => setPage((current) => current + 1)}
              >
                <ChevronRight className="size-4" />
              </Button>
            </div>
          </CardFooter>
        )}
      </Card>
    </div>
  );
}
