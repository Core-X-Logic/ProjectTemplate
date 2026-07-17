import { useCallback } from 'react';
import { useIntl } from 'react-intl';

/**
 * Notification message catalogues (flat, dot-keyed — same shape as
 * `i18n/messages/{en,tr}.ts`, FRONTEND-ARCHITECTURE.md §6).
 *
 * INTEGRATION NOTE: spread `notificationsEn` / `notificationsTr` into the
 * global catalogues when wiring this feature into the shell. Until that merge
 * lands, `useNotificationsMessages` falls back to these maps via
 * `defaultMessage`, so the feature renders correctly in both locales either way.
 */

export const notificationsEn = {
  'notifications.title': 'Notifications',
  'notifications.markAllRead': 'Mark all as read',
  'notifications.markRead': 'Mark as read',
  'notifications.empty': 'No notifications yet.',
  'notifications.loadError': 'Notifications could not be loaded.',
  'notifications.bell.label': 'Notifications',
  'notifications.column.notification': 'Notification',
  'notifications.column.level': 'Level',
  'notifications.column.date': 'Date',
  'notifications.pageInfo': 'Page {page} of {total}',
  'notifications.level.info': 'Info',
  'notifications.level.success': 'Success',
  'notifications.level.warning': 'Warning',
  'notifications.level.error': 'Error',
  'notifications.toast.markedRead': 'Notification marked as read.',
  'notifications.toast.markedAllRead': 'All notifications marked as read.',
  'notifications.toast.error': 'Operation failed. Please try again.',
} as const satisfies Record<string, string>;

export type NotificationsMessageId = keyof typeof notificationsEn;

// Typed against the English key set so the two catalogues cannot drift (1:1).
export const notificationsTr: Record<NotificationsMessageId, string> = {
  'notifications.title': 'Bildirimler',
  'notifications.markAllRead': 'Tümünü okundu işaretle',
  'notifications.markRead': 'Okundu işaretle',
  'notifications.empty': 'Henüz bildirim yok.',
  'notifications.loadError': 'Bildirimler yüklenemedi.',
  'notifications.bell.label': 'Bildirimler',
  'notifications.column.notification': 'Bildirim',
  'notifications.column.level': 'Seviye',
  'notifications.column.date': 'Tarih',
  'notifications.pageInfo': 'Sayfa {page} / {total}',
  'notifications.level.info': 'Bilgi',
  'notifications.level.success': 'Başarılı',
  'notifications.level.warning': 'Uyarı',
  'notifications.level.error': 'Hata',
  'notifications.toast.markedRead': 'Bildirim okundu olarak işaretlendi.',
  'notifications.toast.markedAllRead':
    'Tüm bildirimler okundu olarak işaretlendi.',
  'notifications.toast.error': 'İşlem başarısız. Lütfen tekrar deneyin.',
};

/**
 * Locale-aware formatter for `notifications.*` ids. Resolves through the
 * global `IntlProvider` catalogue first (so merged/global translations win) and
 * falls back to the feature-local map of the active locale.
 */
export function useNotificationsMessages(): (
  id: NotificationsMessageId,
  values?: Record<string, string | number>,
) => string {
  const intl = useIntl();
  const fallback = intl.locale.toLowerCase().startsWith('tr')
    ? notificationsTr
    : notificationsEn;

  return useCallback(
    (id: NotificationsMessageId, values?: Record<string, string | number>) =>
      intl.formatMessage({ id, defaultMessage: fallback[id] }, values),
    [intl, fallback],
  );
}
