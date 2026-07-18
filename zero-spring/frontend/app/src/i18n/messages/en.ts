import { auditMessagesEn } from '@/features/audit/messages';
import { impersonationEn } from '@/features/impersonation/messages';
import { notificationsEn } from '@/features/notifications/messages';
import { organizationUnitsMessages } from '@/features/organization-units/messages';
import { rolesMessages } from '@/features/roles/messages';
import { settingsEn } from '@/features/settings/messages';
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
  'auth.logout': 'Sign out',

  // Navigation
  'nav.dashboard': 'Dashboard',
  'nav.users': 'Users',
  'nav.roles': 'Roles',
  'nav.organizationUnits': 'Organization Units',
  'nav.notifications': 'Notifications',
  'nav.audit': 'Audit',
  'nav.settings': 'Settings',

  // Common actions
  'common.save': 'Save',
  'common.cancel': 'Cancel',
  'common.delete': 'Delete',
  'common.create': 'Create',
  'common.edit': 'Edit',
  'common.search': 'Search',
  'common.loading': 'Loading…',
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
  'dashboard.tenantLabel': 'Active tenant',
  'dashboard.comingSoonSlice':
    'Feature modules land in the next vertical slice.',

  // Feature catalogues (slice B + C) — namespaced, no key collisions
  ...usersMessagesEn,
  ...rolesMessages.en,
  ...organizationUnitsMessages.en,
  ...notificationsEn,
  ...auditMessagesEn,
  ...impersonationEn,
  ...settingsEn,
};

export default en;
