import { accountMessages } from '@/features/account/messages';
import { auditMessagesEn } from '@/features/audit/messages';
import { dashboardMessages } from '@/features/dashboard/messages';
import { editionsMessages } from '@/features/editions/messages';
import { profileMessages } from '@/features/profile/messages';
import { tenantsMessages } from '@/features/tenants/messages';
import { impersonationEn } from '@/features/impersonation/messages';
import { notificationsEn } from '@/features/notifications/messages';
import { organizationUnitsMessages } from '@/features/organization-units/messages';
import { rolesMessages } from '@/features/roles/messages';
import { settingsEn } from '@/features/settings/messages';
import { subscriptionsMessages } from '@/features/subscriptions/messages';
import { usersMessagesEn } from '@/features/users/messages';

/**
 * English message catalogue (flat, dot-keyed).
 *
 * Keys are kept in sync 1:1 with `tr.ts`. In Faz 2 the backend exposes the same
 * key set via `/api/localization/{culture}`; this static catalogue is the SPA
 * baseline (FRONTEND-ARCHITECTURE.md §6).
 *
 * Feature catalogues (namespaced keys, no collisions) are merged in at the end
 * so the central catalogue stays the single source consumed by `I18nProvider`.
 */
const en: Record<string, string> = {
  'app.name': 'Zero Platform',

  // Auth / login
  'auth.login.title': 'Sign in',
  'auth.login.subtitle': 'Welcome back. Please enter your details.',
  'auth.login.username': 'Username or email',
  'auth.login.usernamePlaceholder': 'you@example.com',
  'auth.login.password': 'Password',
  'auth.login.passwordPlaceholder': 'Your password',
  'auth.login.tenant': 'Tenant',
  'auth.login.tenantPlaceholder': 'Leave empty for the default tenant',
  'auth.login.submit': 'Sign in',
  'auth.login.submitting': 'Signing in…',
  'auth.login.error': 'Sign in failed. Please check your credentials.',
  'auth.login.forgotPassword': 'Forgot your password?',
  'auth.logout': 'Sign out',

  // Auth / two-factor (login second step)
  'auth.twoFactor.title': 'Two-step verification',
  'auth.twoFactor.subtitle':
    'Enter the 6-digit code from your authenticator app.',
  'auth.twoFactor.recoverySubtitle':
    'Enter one of the recovery codes you saved when you set up two-step verification.',
  'auth.twoFactor.codeLabel': 'Authentication code',
  'auth.twoFactor.recoveryLabel': 'Recovery code',
  'auth.twoFactor.useRecovery': 'Use a recovery code instead',
  'auth.twoFactor.useAuthenticator': 'Use your authenticator app instead',
  'auth.twoFactor.submit': 'Verify',
  'auth.twoFactor.submitting': 'Verifying…',
  'auth.twoFactor.error':
    'That code is invalid or has expired. Please try again.',
  'auth.twoFactor.backToLogin': 'Back to sign in',

  // Navigation
  'nav.dashboard': 'Dashboard',
  'nav.administration': 'Administration',
  'nav.users': 'Users',
  'nav.roles': 'Roles',
  'nav.organizationUnits': 'Organization Units',
  'nav.notifications': 'Notifications',
  'nav.audit': 'Audit',
  'nav.settings': 'Settings',
  'nav.saas': 'Saas',
  'nav.editions': 'Editions',
  'nav.subscriptions': 'Subscriptions',
  'nav.tenants': 'Tenants',
  'nav.profile': 'My profile',

  // Common actions
  'common.save': 'Save',
  'common.cancel': 'Cancel',
  'common.delete': 'Delete',
  'common.create': 'Create',
  'common.edit': 'Edit',
  'common.search': 'Search',
  'common.loading': 'Loading…',
  'common.retry': 'Retry',
  'common.refresh': 'Refresh',
  'common.comingSoon': 'Coming soon',

  // Errors
  'error.unauthorized': 'Your session has expired. Please sign in again.',
  'error.forbidden': 'You do not have permission to perform this action.',
  'error.network': 'Network error. Please check your connection and try again.',

  // Validation
  'validation.required': 'This field is required.',

  // Forbidden page
  'forbidden.title': 'Access denied',
  'forbidden.description':
    'You do not have permission to view this page (403).',
  'forbidden.back': 'Back to dashboard',

  // Not found page
  'notFound.title': 'Page not found',
  'notFound.description': 'The page you are looking for does not exist (404).',
  'notFound.back': 'Back to dashboard',

  // Dashboard
  'dashboard.welcome': 'Welcome, {name}',
  'dashboard.subtitle': 'Here is a quick overview of what you can manage.',
  'dashboard.tenantLabel': 'Active tenant',
  'dashboard.comingSoonSlice':
    'Feature modules land in the next vertical slice.',
  'dashboard.quickAccess': 'Quick access',
  'dashboard.quickAccessEmpty':
    'You do not have access to any modules yet. Contact your administrator.',
  'dashboard.card.users': 'Invite people, manage accounts and reset access.',
  'dashboard.card.roles': 'Define roles and the permissions they grant.',
  'dashboard.card.organizationUnits':
    'Organize your team into a unit hierarchy.',
  'dashboard.card.tenants': 'Create and manage tenants on the platform.',
  'dashboard.card.notifications': 'Review your latest notifications.',
  'dashboard.card.audit': 'Trace every server-side action and change.',
  'dashboard.card.editions': 'Define the packages tenants can subscribe to.',
  'dashboard.card.subscriptions':
    'Assign editions and manage billing per tenant.',
  'dashboard.card.settings': 'Configure tenant and host preferences.',

  // Feature catalogues (slice B + C + F5-A) — namespaced, no key collisions
  ...usersMessagesEn,
  ...rolesMessages.en,
  ...organizationUnitsMessages.en,
  ...notificationsEn,
  ...auditMessagesEn,
  ...impersonationEn,
  ...settingsEn,
  ...editionsMessages.en,
  ...subscriptionsMessages.en,
  // U-01: account self-service, own profile, tenant management
  ...accountMessages.en,
  ...profileMessages.en,
  ...tenantsMessages.en,
  // Dashboard widget system (modular widgets)
  ...dashboardMessages.en,
};

export default en;
