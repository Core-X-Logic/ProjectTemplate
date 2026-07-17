import { UsersListPage } from '@/features/users/pages/users-list';

/**
 * Users feature route element (slice B) — guarded by `users.read` in
 * routes.tsx. Kept as a stable named export so the shared route table does not
 * change; the actual screen lives in `users-list.tsx` (create/edit runs in a
 * dialog, so no extra form route is needed).
 */
export function UsersPage() {
  return <UsersListPage />;
}
