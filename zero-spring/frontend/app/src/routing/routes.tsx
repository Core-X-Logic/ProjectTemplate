import { Route, Routes } from 'react-router-dom';
import { LoginPage } from '@/auth/pages/login';
import { ForbiddenPage } from '@/auth/pages/forbidden';
import { RequireAuth } from '@/auth/require-auth';
import { AdminLayout } from '@/layouts/admin';
import { DashboardPage } from '@/features/dashboard/pages/dashboard';
import { UsersPage } from '@/features/users/pages/users';
import { RolesPage } from '@/features/roles/pages/roles';
import { RoleFormPage } from '@/features/roles/pages/role-form';
import { OrganizationUnitsPage } from '@/features/organization-units/pages/organization-units';
import { NotificationsPage } from '@/features/notifications/pages/notifications';
import { AuditLogsPage } from '@/features/audit/pages/audit-logs';
import { EntityHistoryPage } from '@/features/audit/pages/entity-history';
import { SettingsPage } from '@/features/settings/pages/settings';
import { NotFoundPage } from '@/routing/not-found';

/**
 * Application route tree (FRONTEND-ARCHITECTURE.md §1/§8):
 *  - public group: `/login`
 *  - protected group: `<RequireAuth>` → `<AdminLayout>` shell with feature routes,
 *    each additionally permission-guarded.
 */
export function AppRoutes() {
  return (
    <Routes>
      {/* Public */}
      <Route path="/login" element={<LoginPage />} />

      {/* Protected shell */}
      <Route element={<RequireAuth />}>
        <Route element={<AdminLayout />}>
          <Route index element={<DashboardPage />} />

          <Route
            path="users"
            element={
              <RequireAuth permission="users.read">
                <UsersPage />
              </RequireAuth>
            }
          />
          <Route
            path="roles"
            element={
              <RequireAuth permission="roles.read">
                <RolesPage />
              </RequireAuth>
            }
          />
          {/* Role form: `/roles/new` (create) and `/roles/:id` (edit) share one
              page that derives its mode from the `:id` param. Write actions are
              additionally gated by <Can> + backend enforcement. */}
          <Route
            path="roles/new"
            element={
              <RequireAuth permission="roles.read">
                <RoleFormPage />
              </RequireAuth>
            }
          />
          <Route
            path="roles/:id"
            element={
              <RequireAuth permission="roles.read">
                <RoleFormPage />
              </RequireAuth>
            }
          />
          <Route
            path="organization-units"
            element={
              <RequireAuth permission="organizationunits.manage">
                <OrganizationUnitsPage />
              </RequireAuth>
            }
          />
          <Route path="notifications" element={<NotificationsPage />} />
          {/* Audit — two read-only screens sharing the `auditlogs.read`
              permission. `/audit` is the request log; `/audit/entity-history`
              is the property-level change log. */}
          <Route
            path="audit"
            element={
              <RequireAuth permission="auditlogs.read">
                <AuditLogsPage />
              </RequireAuth>
            }
          />
          <Route
            path="audit/entity-history"
            element={
              <RequireAuth permission="auditlogs.read">
                <EntityHistoryPage />
              </RequireAuth>
            }
          />
          {/* Settings is reachable by tenant OR host operators — the page then
              shows only the scope tabs the user may manage. Any-of guard mirrors
              the backend `@PreAuthorize` on the two settings scopes. */}
          <Route
            path="settings"
            element={
              <RequireAuth
                anyPermission={['settings.tenant.manage', 'settings.host.manage']}
              >
                <SettingsPage />
              </RequireAuth>
            }
          />

          <Route path="forbidden" element={<ForbiddenPage />} />
        </Route>
      </Route>

      {/* Fallback */}
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  );
}
