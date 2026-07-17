import { Bell } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { useUnreadCount } from '../hooks';
import { useNotificationsMessages } from '../messages';

/**
 * Header bell with the unread-count badge (polls via `useUnreadCount`, 30s).
 * Clicking navigates to the notifications inbox.
 *
 * INTEGRATION NOTE: mount inside the admin header topbar next to the theme
 * toggle (`layouts/admin/components/header.tsx`) — it is self-contained and
 * only needs the router + query provider context.
 */
export function NotificationBell() {
  const navigate = useNavigate();
  const t = useNotificationsMessages();
  const { data: unreadCount = 0 } = useUnreadCount();

  return (
    <Button
      variant="ghost"
      mode="icon"
      shape="circle"
      aria-label={t('notifications.bell.label')}
      onClick={() => navigate('/notifications')}
      className="relative size-9 shrink-0"
    >
      <Bell className="size-4.5!" />
      {unreadCount > 0 && (
        <Badge
          variant="destructive"
          size="xs"
          shape="circle"
          className="absolute -top-0.5 -end-0.5"
        >
          {unreadCount > 99 ? '99+' : unreadCount}
        </Badge>
      )}
    </Button>
  );
}
