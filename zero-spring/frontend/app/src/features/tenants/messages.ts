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
    'The tenant is activated immediately and receives the default subscription.',
  'tenants.create.name': 'Name',
  'tenants.create.namePlaceholder': 'e.g. acme',
  'tenants.create.nameHint':
    'Lowercase letters, digits and hyphens, 2-30 characters. Used at sign-in and cannot be changed later.',
  'tenants.create.namePattern':
    'Use 2-30 characters: lowercase letters, digits or hyphens.',
  'tenants.create.displayName': 'Display name',
  'tenants.create.displayNamePlaceholder': 'e.g. Acme Inc.',
  'tenants.create.submit': 'Create',
  'tenants.create.submitting': 'Creating…',
  'tenants.create.cancel': 'Cancel',

  // Known gap (Issue #1) — surfaced in the create dialog and after creation.
  'tenants.create.noAdminTitle': 'No admin user is created',
  'tenants.create.noAdminDescription':
    'Creating a tenant does not create a user for it, so nobody can sign in to it yet. Add a user under Users and assign it to this tenant.',

  // Toasts
  'tenants.toast.created': 'Tenant created. Remember to add its first user.',
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
    'Kiracı hemen aktifleştirilir ve varsayılan aboneliği alır.',
  'tenants.create.name': 'Ad',
  'tenants.create.namePlaceholder': 'örn. acme',
  'tenants.create.nameHint':
    'Küçük harf, rakam ve tire; 2-30 karakter. Girişte kullanılır ve sonradan değiştirilemez.',
  'tenants.create.namePattern':
    '2-30 karakter kullanın: küçük harf, rakam veya tire.',
  'tenants.create.displayName': 'Görünen ad',
  'tenants.create.displayNamePlaceholder': 'örn. Acme A.Ş.',
  'tenants.create.submit': 'Oluştur',
  'tenants.create.submitting': 'Oluşturuluyor…',
  'tenants.create.cancel': 'İptal',

  // Known gap (Issue #1)
  'tenants.create.noAdminTitle': 'Yönetici kullanıcı oluşturulmaz',
  'tenants.create.noAdminDescription':
    'Kiracı oluşturmak ona ait bir kullanıcı oluşturmaz; bu nedenle henüz kimse giriş yapamaz. Kullanıcılar bölümünden bir kullanıcı ekleyip bu kiracıya atayın.',

  // Toasts
  'tenants.toast.created':
    'Kiracı oluşturuldu. İlk kullanıcısını eklemeyi unutmayın.',
  'tenants.toast.activated': 'Kiracı aktifleştirildi.',
  'tenants.toast.deactivated': 'Kiracı pasifleştirildi.',
  'tenants.toast.error': 'İşlem başarısız. Lütfen tekrar deneyin.',
};

export const tenantsMessages: Record<'en' | 'tr', Record<string, string>> = {
  en: tenantsEn,
  tr: tenantsTr,
};
