import { useCallback } from 'react';
import { useIntl } from 'react-intl';

/**
 * Settings message catalogues (flat, dot-keyed — same shape as
 * `i18n/messages/{en,tr}.ts`, FRONTEND-ARCHITECTURE.md §6).
 *
 * INTEGRATION NOTE: spread `settingsEn` / `settingsTr` into the global
 * catalogues when wiring this feature into the shell. Until that merge lands,
 * `useSettingsMessages` falls back to these maps via `defaultMessage`, so the
 * feature renders correctly in both locales either way. Per-setting labels are
 * resolved separately through `settings.field.{name}` with the raw name as the
 * default, so unknown settings still render a sensible label.
 */

export const settingsEn = {
  'settings.title': 'Settings',
  'settings.subtitle': 'Manage tenant and host configuration.',
  'settings.tabs.tenant': 'Tenant',
  'settings.tabs.host': 'Host',
  'settings.scope.tenant.title': 'Tenant settings',
  'settings.scope.tenant.description':
    'Configuration that applies to the current tenant.',
  'settings.scope.host.title': 'Host settings',
  'settings.scope.host.description':
    'Platform-wide configuration managed by the host.',
  'settings.save': 'Save',
  'settings.saved': 'Settings saved.',
  'settings.default': 'Default: {value}',
  'settings.empty': 'No settings available.',
  'settings.error': 'The operation could not be completed.',
  'settings.loadError': 'Settings could not be loaded.',
} as const satisfies Record<string, string>;

export type SettingsMessageId = keyof typeof settingsEn;

// Typed against the English key set so the two catalogues cannot drift (1:1).
export const settingsTr: Record<SettingsMessageId, string> = {
  'settings.title': 'Ayarlar',
  'settings.subtitle': 'Kiracı ve host yapılandırmasını yönetin.',
  'settings.tabs.tenant': 'Kiracı',
  'settings.tabs.host': 'Host',
  'settings.scope.tenant.title': 'Kiracı ayarları',
  'settings.scope.tenant.description':
    'Geçerli kiracıya uygulanan yapılandırma.',
  'settings.scope.host.title': 'Host ayarları',
  'settings.scope.host.description':
    'Host tarafından yönetilen platform genelindeki yapılandırma.',
  'settings.save': 'Kaydet',
  'settings.saved': 'Ayarlar kaydedildi.',
  'settings.default': 'Varsayılan: {value}',
  'settings.empty': 'Görüntülenecek ayar yok.',
  'settings.error': 'İşlem tamamlanamadı.',
  'settings.loadError': 'Ayarlar yüklenemedi.',
};

/**
 * Locale-aware formatter for `settings.*` ids. Resolves through the global
 * `IntlProvider` catalogue first (so merged/global translations win) and falls
 * back to the feature-local map of the active locale.
 */
export function useSettingsMessages(): (
  id: SettingsMessageId,
  values?: Record<string, string | number>,
) => string {
  const intl = useIntl();
  const fallback = intl.locale.toLowerCase().startsWith('tr')
    ? settingsTr
    : settingsEn;

  return useCallback(
    (id: SettingsMessageId, values?: Record<string, string | number>) =>
      intl.formatMessage({ id, defaultMessage: fallback[id] }, values),
    [intl, fallback],
  );
}
