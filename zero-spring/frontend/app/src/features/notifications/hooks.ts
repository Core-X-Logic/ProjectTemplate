import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { toast } from 'sonner';
import { ApiError } from '@/api/client';
import { getUnreadCount, listNotifications, markAllRead, markRead } from './api';
import { useNotificationsMessages } from './messages';

/**
 * React Query hooks for the notifications inbox (FRONTEND-ARCHITECTURE.md §7).
 *
 * Query keys:
 *  - `['notifications', …]`      — paged inbox lists
 *  - `['notifications-unread']`  — unread counter (30s polling for the bell)
 *
 * Every mutation invalidates both keys and surfaces a sonner toast.
 */

export const NOTIFICATIONS_KEY = ['notifications'] as const;
export const NOTIFICATIONS_UNREAD_KEY = ['notifications-unread'] as const;

/** Bell badge polling cadence — matches the query client's 30s staleTime. */
export const UNREAD_POLL_INTERVAL_MS = 30_000;

const DEFAULT_PAGE_SIZE = 10;

/** Paged inbox for the current user, newest first. */
export function useNotifications(page: number, size: number = DEFAULT_PAGE_SIZE) {
  return useQuery({
    queryKey: [...NOTIFICATIONS_KEY, { page, size }],
    queryFn: () => listNotifications({ page, size, sort: ['createdAt,desc'] }),
    placeholderData: keepPreviousData,
  });
}

/** Unread counter for the header bell — polls every 30s while mounted. */
export function useUnreadCount() {
  return useQuery({
    queryKey: NOTIFICATIONS_UNREAD_KEY,
    queryFn: getUnreadCount,
    refetchInterval: UNREAD_POLL_INTERVAL_MS,
    select: (data) => data.count ?? 0,
  });
}

/** Invalidates the inbox list and the unread counter together. */
function useInvalidateNotifications(): () => Promise<void> {
  const queryClient = useQueryClient();
  return async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: NOTIFICATIONS_KEY }),
      queryClient.invalidateQueries({ queryKey: NOTIFICATIONS_UNREAD_KEY }),
    ]);
  };
}

function errorDescription(error: unknown): string | undefined {
  return error instanceof ApiError ? error.detail : undefined;
}

/** `PUT /api/notifications/{id}/read` as a mutation (row action). */
export function useMarkRead() {
  const invalidate = useInvalidateNotifications();
  const t = useNotificationsMessages();

  return useMutation({
    mutationFn: (id: number) => markRead(id),
    onSuccess: async () => {
      await invalidate();
      toast.success(t('notifications.toast.markedRead'));
    },
    onError: (error) => {
      toast.error(t('notifications.toast.error'), {
        description: errorDescription(error),
      });
    },
  });
}

/** `PUT /api/notifications/read-all` as a mutation (toolbar action). */
export function useMarkAllRead() {
  const invalidate = useInvalidateNotifications();
  const t = useNotificationsMessages();

  return useMutation({
    mutationFn: () => markAllRead(),
    onSuccess: async () => {
      await invalidate();
      toast.success(t('notifications.toast.markedAllRead'));
    },
    onError: (error) => {
      toast.error(t('notifications.toast.error'), {
        description: errorDescription(error),
      });
    },
  });
}
