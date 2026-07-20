import { Route, Routes } from 'react-router-dom';
import { LoginPage } from '@/auth/pages/login';
import { ForbiddenPage } from '@/auth/pages/forbidden';
import { RequireAuth } from '@/auth/require-auth';
import { AdminLayout } from '@/layouts/admin';
import { ConfirmEmailPage } from '@/features/account/pages/confirm-email';
import { ForgotPasswordPage } from '@/features/account/pages/forgot-password';
import { ResetPasswordPage } from '@/features/account/pages/reset-password';
import { ProfilePage } from '@/features/profile/pages/profile';
import { TenantsListPage } from '@/features/tenants/pages/tenants-list';
import { DashboardPage } from '@/features/dashboard/pages/dashboard';
import { UsersPage } from '@/features/users/pages/users';
import { RolesPage } from '@/features/roles/pages/roles';
import { RoleFormPage } from '@/features/roles/pages/role-form';
import { OrganizationUnitsPage } from '@/features/organization-units/pages/organization-units';
import { NotificationsPage } from '@/features/notifications/pages/notifications';
import { AuditLogsPage } from '@/features/audit/pages/audit-logs';
import { EntityHistoryPage } from '@/features/audit/pages/entity-history';
import { SettingsPage } from '@/features/settings/pages/settings';
import { EditionsListPage } from '@/features/editions/pages/editions-list';
import { EditionFormPage } from '@/features/editions/pages/edition-form';
import { SubscriptionsListPage } from '@/features/subscriptions/pages/subscriptions-list';
import {
  PaymentResultCancelPage,
  PaymentResultSuccessPage,
} from '@/features/subscriptions/pages/payment-result';
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

      {/* Public account self-service (U-01). These paths are NOT free choices:
          `EmailTemplateService` mails links to `{baseUrl}/account/reset-password
          ?code=…` and `{baseUrl}/account/confirm-email?code=…`, so the segments
          and the `code` query parameter are a contract with the backend's mail
          templates. Renaming either side alone breaks every link already sent. */}
      <Route
        path="/account/forgot-password"
        element={<ForgotPasswordPage />}
      />
      <Route path="/account/reset-password" element={<ResetPasswordPage />} />
      <Route path="/account/confirm-email" element={<ConfirmEmailPage />} />

      {/* Protected shell */}
      <Route element={<RequireAuth />}>
        <Route element={<AdminLayout />}>
          <Route index element={<DashboardPage />} />

          {/* Own profile + password. No permission guard on purpose:
              `ProfileController` is `@PreAuthorize("isAuthenticated()")` on
              every method, so requiring a named permission here would lock
              users out of their own account details. */}
          <Route path="profile" element={<ProfilePage />} />

          {/* Tenant management — host only. `tenants.manage` is declared
              `Side.HOST`, and `TenantController` carries a class-level
              `@PreAuthorize` for the same key (triple lock with <Can>). */}
          <Route
            path="tenants"
            element={
              <RequireAuth permission="tenants.manage">
                <TenantsListPage />
              </RequireAuth>
            }
          />

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

          {/* SaaS (F5 slice A) — host-side commercial layer. Read guards the
              screen; every write is additionally `<Can>`-gated and enforced by
              the backend with `Side.HOST` + `editions|subscriptions.manage`.
              The edition form shares one page across `/editions/new` (create)
              and `/editions/:id` (edit), same as the role form. */}
          <Route
            path="editions"
            element={
              <RequireAuth permission="editions.read">
                <EditionsListPage />
              </RequireAuth>
            }
          />
          <Route
            path="editions/new"
            element={
              <RequireAuth permission="editions.read">
                <EditionFormPage />
              </RequireAuth>
            }
          />
          <Route
            path="editions/:id"
            element={
              <RequireAuth permission="editions.read">
                <EditionFormPage />
              </RequireAuth>
            }
          />
          <Route
            path="subscriptions"
            element={
              <RequireAuth permission="subscriptions.read">
                <SubscriptionsListPage />
              </RequireAuth>
            }
          />

          {/* Payment result landing pages (CONTRACT-payments-tr P2'-C). These
              are the `successUrl`/`cancelUrl` targets sent to PayTR/iyzico, so
              the paths are a contract with every checkout session already
              started. Authenticated only, deliberately NO permission: the
              provider redirect may land on any signed-in session, and a 403
              here would read as a payment failure. Both pages are purely
              informational — activation is decided server-side by
              webhook/reconciliation, never by this redirect. */}
          <Route
            path="payment/result/success"
            element={<PaymentResultSuccessPage />}
          />
          <Route
            path="payment/result/cancel"
            element={<PaymentResultCancelPage />}
          />

          <Route path="forbidden" element={<ForbiddenPage />} />
        </Route>
      </Route>

      {/* Fallback */}
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  );
}
