import { notificationsTr } from '@/features/notifications/messages';
import { organizationUnitsMessages } from '@/features/organization-units/messages';
import { rolesMessages } from '@/features/roles/messages';
import { usersMessagesTr } from '@/features/users/messages';

/**
 * Turkish message catalogue (flat, dot-keyed).
 *
 * Keys are kept in sync 1:1 with `en.ts` (FRONTEND-ARCHITECTURE.md §6).
 *
 * Feature catalogues (namespaced keys, no collisions) are merged in at the end
 * so the central catalogue stays the single source consumed by `I18nProvider`.
 */
const tr: Record<string, string> = {
  'app.name': 'Zero Platform',

  // Auth / login
  'auth.login.title': 'Giriş yap',
  'auth.login.subtitle': 'Tekrar hoş geldiniz. Lütfen bilgilerinizi girin.',
  'auth.login.username': 'Kullanıcı adı veya e-posta',
  'auth.login.usernamePlaceholder': 'siz@ornek.com',
  'auth.login.password': 'Parola',
  'auth.login.passwordPlaceholder': 'Parolanız',
  'auth.login.tenant': 'Kiracı',
  'auth.login.tenantPlaceholder': 'Varsayılan kiracı için boş bırakın',
  'auth.login.submit': 'Giriş yap',
  'auth.login.submitting': 'Giriş yapılıyor…',
  'auth.login.error': 'Giriş başarısız. Lütfen bilgilerinizi kontrol edin.',
  'auth.logout': 'Çıkış yap',

  // Navigation
  'nav.dashboard': 'Panel',
  'nav.users': 'Kullanıcılar',
  'nav.roles': 'Roller',
  'nav.organizationUnits': 'Organizasyon Birimleri',
  'nav.notifications': 'Bildirimler',
  'nav.audit': 'Denetim',
  'nav.settings': 'Ayarlar',

  // Common actions
  'common.save': 'Kaydet',
  'common.cancel': 'İptal',
  'common.delete': 'Sil',
  'common.create': 'Oluştur',
  'common.edit': 'Düzenle',
  'common.search': 'Ara',
  'common.loading': 'Yükleniyor…',
  'common.comingSoon': 'Yakında',

  // Errors
  'error.unauthorized': 'Oturumunuz sona erdi. Lütfen tekrar giriş yapın.',
  'error.forbidden': 'Bu işlemi yapmak için yetkiniz yok.',
  'error.network': 'Ağ hatası. Lütfen bağlantınızı kontrol edip tekrar deneyin.',

  // Validation
  'validation.required': 'Bu alan zorunludur.',

  // Forbidden page
  'forbidden.title': 'Erişim reddedildi',
  'forbidden.description': 'Bu sayfayı görüntüleme yetkiniz yok (403).',
  'forbidden.back': 'Panele dön',

  // Not found page
  'notFound.title': 'Sayfa bulunamadı',
  'notFound.description': 'Aradığınız sayfa mevcut değil (404).',
  'notFound.back': 'Panele dön',

  // Dashboard
  'dashboard.welcome': 'Hoş geldiniz, {name}',
  'dashboard.tenantLabel': 'Aktif kiracı',
  'dashboard.comingSoonSlice':
    'Özellik modülleri bir sonraki dikey dilimde gelecek.',

  // Feature catalogues (slice B) — namespaced, no key collisions
  ...usersMessagesTr,
  ...rolesMessages.tr,
  ...organizationUnitsMessages.tr,
  ...notificationsTr,
};

export default tr;
