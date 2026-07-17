import { RolesListPage } from '@/features/roles/pages/roles-list';

/**
 * Roles feature route element (slice B) — guarded by `roles.read` in
 * routes.tsx. Kept as a stable named export so the shared route table does not
 * change; the actual screen lives in `roles-list.tsx`.
 *
 * NOTE for routing (shared file, not touched by this slice): the form page
 * (`role-form.tsx`) expects `/roles/new` and `/roles/:id` route entries.
 */
export function RolesPage() {
  return <RolesListPage />;
}
