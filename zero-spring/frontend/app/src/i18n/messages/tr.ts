import { accountMessages } from '@/features/account/messages';
import { auditMessagesTr } from '@/features/audit/messages';
import { dashboardMessages } from '@/features/dashboard/messages';
import { editionsMessages } from '@/features/editions/messages';
import { profileMessages } from '@/features/profile/messages';
import { tenantsMessages } from '@/features/tenants/messages';
import { impersonationTr } from '@/features/impersonation/messages';
import { notificationsTr } from '@/features/notifications/messages';
import { organizationUnitsMessages } from '@/features/organization-units/messages';
import { rolesMessages } from '@/features/roles/messages';
import { settingsTr } from '@/features/settings/messages';
import { subscriptionsMessages } from '@/features/subscriptions/messages';
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
  'auth.login.forgotPassword': 'Parolanızı mı unuttunuz?',
  'auth.logout': 'Çıkış yap',

  // Auth / two-factor (login second step)
  'auth.twoFactor.title': 'İki adımlı doğrulama',
  'auth.twoFactor.subtitle':
    'Kimlik doğrulayıcı uygulamanızdaki 6 haneli kodu girin.',
  'auth.twoFactor.recoverySubtitle':
    'İki adımlı doğrulamayı kurarken kaydettiğiniz kurtarma kodlarından birini girin.',
  'auth.twoFactor.codeLabel': 'Doğrulama kodu',
  'auth.twoFactor.recoveryLabel': 'Kurtarma kodu',
  'auth.twoFactor.useRecovery': 'Bunun yerine kurtarma kodu kullanın',
  'auth.twoFactor.useAuthenticator':
    'Bunun yerine kimlik doğrulayıcı uygulamanızı kullanın',
  'auth.twoFactor.submit': 'Doğrula',
  'auth.twoFactor.submitting': 'Doğrulanıyor…',
  'auth.twoFactor.error':
    'Kod geçersiz veya süresi dolmuş. Lütfen tekrar deneyin.',
  'auth.twoFactor.backToLogin': 'Girişe dön',

  // Navigation
  'nav.dashboard': 'Panel',
  'nav.administration': 'Yönetim',
  'nav.users': 'Kullanıcılar',
  'nav.roles': 'Roller',
  'nav.organizationUnits': 'Organizasyon Birimleri',
  'nav.notifications': 'Bildirimler',
  'nav.audit': 'Denetim',
  'nav.settings': 'Ayarlar',
  'nav.saas': 'Saas',
  'nav.editions': 'Paketler',
  'nav.subscriptions': 'Abonelikler',
  'nav.tenants': 'Kiracılar',
  'nav.profile': 'Profilim',

  // Common actions
  'common.save': 'Kaydet',
  'common.cancel': 'İptal',
  'common.delete': 'Sil',
  'common.create': 'Oluştur',
  'common.edit': 'Düzenle',
  'common.search': 'Ara',
  'common.loading': 'Yükleniyor…',
  'common.retry': 'Tekrar dene',
  'common.refresh': 'Yenile',
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
  'dashboard.subtitle': 'Yönetebileceklerinize hızlı bir bakış.',
  'dashboard.tenantLabel': 'Aktif kiracı',
  'dashboard.comingSoonSlice':
    'Özellik modülleri bir sonraki dikey dilimde gelecek.',
  'dashboard.quickAccess': 'Hızlı erişim',
  'dashboard.quickAccessEmpty':
    'Henüz hiçbir modüle erişiminiz yok. Yöneticinizle iletişime geçin.',
  'dashboard.card.users':
    'Kişileri davet edin, hesapları yönetin ve erişimi sıfırlayın.',
  'dashboard.card.roles': 'Rolleri ve verdikleri izinleri tanımlayın.',
  'dashboard.card.organizationUnits':
    'Ekibinizi birim hiyerarşisi olarak düzenleyin.',
  'dashboard.card.tenants': 'Platformdaki kiracıları oluşturun ve yönetin.',
  'dashboard.card.notifications': 'En son bildirimlerinizi gözden geçirin.',
  'dashboard.card.audit': 'Tüm sunucu işlemlerini ve değişiklikleri izleyin.',
  'dashboard.card.editions':
    'Kiracıların abone olabileceği paketleri tanımlayın.',
  'dashboard.card.subscriptions':
    'Kiracı bazında paket atayın ve faturalandırmayı yönetin.',
  'dashboard.card.settings': 'Kiracı ve host tercihlerini yapılandırın.',

  // Feature catalogues (slice B + C + F5-A) — namespaced, no key collisions
  ...usersMessagesTr,
  ...rolesMessages.tr,
  ...organizationUnitsMessages.tr,
  ...notificationsTr,
  ...auditMessagesTr,
  ...impersonationTr,
  ...settingsTr,
  ...editionsMessages.tr,
  ...subscriptionsMessages.tr,
  // U-01: hesap self-servis, kendi profili, kiracı yönetimi
  ...accountMessages.tr,
  ...profileMessages.tr,
  ...tenantsMessages.tr,
  // Dashboard widget sistemi (modüler widget'lar)
  ...dashboardMessages.tr,
};

export default tr;
