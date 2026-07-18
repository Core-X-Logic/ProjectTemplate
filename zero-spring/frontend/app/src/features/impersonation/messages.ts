import { useCallback } from 'react';
import { useIntl } from 'react-intl';

/**
 * Impersonation message catalogues (flat, dot-keyed — same shape as
 * `i18n/messages/{en,tr}.ts`, FRONTEND-ARCHITECTURE.md §6).
 *
 * INTEGRATION NOTE: spread `impersonationEn` / `impersonationTr` into the global
 * catalogues when wiring this feature into the shell. Until that merge lands,
 * `useImpersonationMessages` falls back to these maps via `defaultMessage`, so
 * the banner and row action render correctly in both locales either way.
 */

export const impersonationEn = {
  'impersonation.banner.title': 'Impersonating: {username}',
  'impersonation.banner.badge': 'Impersonation',
  'impersonation.banner.back': 'Back to my account',
  'impersonation.action.label': 'Impersonate',
  'impersonation.action.cascadeBlocked':
    'You are already in an impersonation session.',
  'impersonation.success': 'You are now impersonating {username}.',
  'impersonation.backSuccess': 'Returned to your own account.',
  'impersonation.error': 'The impersonation request could not be completed.',
} as const satisfies Record<string, string>;

export type ImpersonationMessageId = keyof typeof impersonationEn;

// Typed against the English key set so the two catalogues cannot drift (1:1).
export const impersonationTr: Record<ImpersonationMessageId, string> = {
  'impersonation.banner.title': 'Taklit ediliyor: {username}',
  'impersonation.banner.badge': 'Taklit',
  'impersonation.banner.back': 'Kendi hesabıma dön',
  'impersonation.action.label': 'Taklit et',
  'impersonation.action.cascadeBlocked':
    'Zaten bir impersonation (taklit) oturumundasınız.',
  'impersonation.success': 'Artık {username} kullanıcısını taklit ediyorsunuz.',
  'impersonation.backSuccess': 'Kendi hesabınıza döndünüz.',
  'impersonation.error': 'Taklit isteği tamamlanamadı.',
};

/**
 * Locale-aware formatter for `impersonation.*` ids. Resolves through the global
 * `IntlProvider` catalogue first (so merged/global translations win) and falls
 * back to the feature-local map of the active locale.
 */
export function useImpersonationMessages(): (
  id: ImpersonationMessageId,
  values?: Record<string, string | number>,
) => string {
  const intl = useIntl();
  const fallback = intl.locale.toLowerCase().startsWith('tr')
    ? impersonationTr
    : impersonationEn;

  return useCallback(
    (id: ImpersonationMessageId, values?: Record<string, string | number>) =>
      intl.formatMessage({ id, defaultMessage: fallback[id] }, values),
    [intl, fallback],
  );
}
