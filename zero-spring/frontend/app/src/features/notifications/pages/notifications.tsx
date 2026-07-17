import { NotificationsInboxPage } from './notifications-inbox';

/**
 * Route element for `/notifications` (kept as the stable export consumed by
 * `routing/routes.tsx`). Delegates to the slice B inbox screen.
 */
export function NotificationsPage() {
  return <NotificationsInboxPage />;
}
