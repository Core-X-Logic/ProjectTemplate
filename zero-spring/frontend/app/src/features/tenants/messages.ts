/**
 * Tenants feature message catalogue (flat, dot-keyed; en + tr, keys 1:1).
 *
 * Parity is enforced at COMPILE TIME: `tenantsEn` is the key source of truth
 * (`as const satisfies`), and `tenantsTr` is typed `Record<TenantsMessageId,
 * string>` — a missing or extra Turkish key fails `tsc -b`.
 */

export const tenantsEn = {
  // List page
  'tenants.list.title': 'Tenants',
  'tenants.list.description':
    'Every organisation on the platform. Host operators only.',
  'tenants.list.create': 'New tenant',
  'tenants.list.empty': 'No tenants yet. Create the first one to get started.',
  'tenants.list.error': 'Tenants could not be loaded.',

  // Data-grid columns
  'tenants.columns.name': 'Name',
  'tenants.columns.displayName': 'Display name',
  'tenants.columns.createdAt': 'Created',
  'tenants.columns.active': 'Status',

  // Badges
  'tenants.badge.active': 'Active',
  'tenants.badge.inactive': 'Inactive',

  // Row actions
  'tenants.actions.menu': 'Open tenant actions',
  'tenants.actions.activate': 'Activate',
  'tenants.actions.deactivate': 'Deactivate',
  // Shown in place of edit/delete so the absence reads as a decision, not a bug.
  'tenants.actions.noEdit':
    'A tenant’s name cannot be changed after creation.',

  // Create dialog
  'tenants.create.title': 'Create tenant',
  'tenants.create.description':
    'The tenant is activated immediately, receives the default subscription, and gets a bootstrap admin user (username: admin).',
  'tenants.create.name': 'Name',
  'tenants.create.namePlaceholder': 'e.g. acme',
  'tenants.create.nameHint':
    'Lowercase letters, digits and hyphens, 2-30 characters. Used at sign-in and cannot be changed later.',
  'tenants.create.namePattern':
    'Use 2-30 characters: lowercase letters, digits or hyphens.',
  'tenants.create.displayName': 'Display name',
  'tenants.create.displayNamePlaceholder': 'e.g. Acme Inc.',
  'tenants.create.adminEmail': 'Admin email',
  'tenants.create.adminEmailPlaceholder': 'e.g. admin@acme.com',
  'tenants.create.adminEmailHint':
    'The tenant’s bootstrap admin user is created with this email.',
  'tenants.create.adminEmailInvalid': 'Enter a valid email address.',
  'tenants.create.adminPassword': 'Admin password',
  'tenants.create.adminPasswordHint':
    'Optional. Leave empty to generate a strong password automatically — it will be shown once after creation.',
  'tenants.create.submit': 'Create',
  'tenants.create.submitting': 'Creating…',
  'tenants.create.cancel': 'Cancel',

  // One-time reveal of the generated admin password (closes Issue #1).
  'tenants.create.successTitle': 'Tenant created',
  'tenants.create.successDescription':
    'The tenant and its admin user (username: admin) have been created. The admin must change this password at first sign-in.',
  'tenants.create.generatedPasswordLabel': 'Generated admin password',
  'tenants.create.oneTimeTitle': 'Shown only once',
  'tenants.create.oneTimeWarning':
    'This password will not be shown again and cannot be retrieved later. Copy it now and hand it to the tenant admin over a secure channel.',
  'tenants.create.copyPassword': 'Copy password',
  'tenants.create.copied': 'Copied',
  'tenants.create.close': 'Close',

  // Users dialog (host-side tenant user picker + impersonation)
  'tenants.actions.showUsers': 'View users',
  'tenants.users.title': 'Users of {tenant}',
  'tenants.users.description':
    'The users of this tenant. Impersonation opens the product exactly as that user sees it; the session is audited and you can return to your own account at any time.',
  'tenants.users.searchPlaceholder': 'Search by username, email or name…',
  'tenants.users.empty': 'No users match.',
  'tenants.users.error': 'Users could not be loaded.',
  'tenants.users.impersonate': 'Impersonate',
  'tenants.users.impersonated': 'You are now impersonating {username}.',
  'tenants.users.previous': 'Previous',
  'tenants.users.next': 'Next',

  // Toasts
  'tenants.toast.created': 'Tenant created together with its admin user.',
  'tenants.toast.activated': 'Tenant activated.',
  'tenants.toast.deactivated': 'Tenant deactivated.',
  'tenants.toast.error': 'Operation failed. Please try again.',
} as const satisfies Record<string, string>;

export type TenantsMessageId = keyof typeof tenantsEn;

// Typed against the English key set so the two catalogues cannot drift (1:1).
export const tenantsTr: Record<TenantsMessageId, string> = {
  // List page
  'tenants.list.title': 'Kiracılar',
  'tenants.list.description':
    'Platformdaki tüm organizasyonlar. Yalnızca host yöneticileri.',
  'tenants.list.create': 'Yeni kiracı',
  'tenants.list.empty': 'Henüz kiracı yok. Başlamak için ilkini oluşturun.',
  'tenants.list.error': 'Kiracılar yüklenemedi.',

  // Data-grid columns
  'tenants.columns.name': 'Ad',
  'tenants.columns.displayName': 'Görünen ad',
  'tenants.columns.createdAt': 'Oluşturulma',
  'tenants.columns.active': 'Durum',

  // Badges
  'tenants.badge.active': 'Aktif',
  'tenants.badge.inactive': 'Pasif',

  // Row actions
  'tenants.actions.menu': 'Kiracı işlemlerini aç',
  'tenants.actions.activate': 'Aktifleştir',
  'tenants.actions.deactivate': 'Pasifleştir',
  'tenants.actions.noEdit':
    'Kiracının adı oluşturulduktan sonra değiştirilemez.',

  // Create dialog
  'tenants.create.title': 'Kiracı oluştur',
  'tenants.create.description':
    'Kiracı hemen aktifleştirilir, varsayılan aboneliği alır ve bir başlangıç yönetici kullanıcısı (kullanıcı adı: admin) oluşturulur.',
  'tenants.create.name': 'Ad',
  'tenants.create.namePlaceholder': 'örn. acme',
  'tenants.create.nameHint':
    'Küçük harf, rakam ve tire; 2-30 karakter. Girişte kullanılır ve sonradan değiştirilemez.',
  'tenants.create.namePattern':
    '2-30 karakter kullanın: küçük harf, rakam veya tire.',
  'tenants.create.displayName': 'Görünen ad',
  'tenants.create.displayNamePlaceholder': 'örn. Acme A.Ş.',
  'tenants.create.adminEmail': 'Yönetici e-postası',
  'tenants.create.adminEmailPlaceholder': 'örn. admin@acme.com',
  'tenants.create.adminEmailHint':
    'Kiracının başlangıç yönetici kullanıcısı bu e-posta ile oluşturulur.',
  'tenants.create.adminEmailInvalid': 'Geçerli bir e-posta adresi girin.',
  'tenants.create.adminPassword': 'Yönetici parolası',
  'tenants.create.adminPasswordHint':
    'İsteğe bağlı. Boş bırakılırsa güçlü bir parola otomatik oluşturulur ve oluşturma sonrasında bir kez gösterilir.',
  'tenants.create.submit': 'Oluştur',
  'tenants.create.submitting': 'Oluşturuluyor…',
  'tenants.create.cancel': 'İptal',

  // Oluşturulan yönetici parolasının tek seferlik gösterimi (Issue #1 kapanışı).
  'tenants.create.successTitle': 'Kiracı oluşturuldu',
  'tenants.create.successDescription':
    'Kiracı ve yönetici kullanıcısı (kullanıcı adı: admin) oluşturuldu. Yönetici ilk girişte bu parolayı değiştirmek zorundadır.',
  'tenants.create.generatedPasswordLabel': 'Oluşturulan yönetici parolası',
  'tenants.create.oneTimeTitle': 'Yalnızca bir kez gösterilir',
  'tenants.create.oneTimeWarning':
    'Bu parola bir daha gösterilmez ve sonradan geri alınamaz. Şimdi kopyalayın ve kiracı yöneticisine güvenli bir kanaldan iletin.',
  'tenants.create.copyPassword': 'Parolayı kopyala',
  'tenants.create.copied': 'Kopyalandı',
  'tenants.create.close': 'Kapat',

  // Kullanıcılar diyaloğu (host tarafı kiracı kullanıcı seçici + bürünme)
  'tenants.actions.showUsers': 'Kullanıcıları gör',
  'tenants.users.title': '{tenant} kullanıcıları',
  'tenants.users.description':
    'Bu kiracının kullanıcıları. Bürünme, ürünü o kullanıcının gördüğü şekliyle açar; oturum denetim kaydına işlenir ve istediğiniz an kendi hesabınıza dönebilirsiniz.',
  'tenants.users.searchPlaceholder':
    'Kullanıcı adı, e-posta veya ada göre ara…',
  'tenants.users.empty': 'Eşleşen kullanıcı yok.',
  'tenants.users.error': 'Kullanıcılar yüklenemedi.',
  'tenants.users.impersonate': 'Bürün',
  'tenants.users.impersonated': 'Artık {username} olarak oturumdasınız.',
  'tenants.users.previous': 'Önceki',
  'tenants.users.next': 'Sonraki',

  // Toasts
  'tenants.toast.created':
    'Kiracı, yönetici kullanıcısıyla birlikte oluşturuldu.',
  'tenants.toast.activated': 'Kiracı aktifleştirildi.',
  'tenants.toast.deactivated': 'Kiracı pasifleştirildi.',
  'tenants.toast.error': 'İşlem başarısız. Lütfen tekrar deneyin.',
};

export const tenantsMessages: Record<'en' | 'tr', Record<string, string>> = {
  en: tenantsEn,
  tr: tenantsTr,
};
