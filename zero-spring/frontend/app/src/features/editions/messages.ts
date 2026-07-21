/**
 * Editions feature message catalogue (flat, dot-keyed; en + tr, keys 1:1).
 *
 * Parity is enforced at COMPILE TIME: `editionsEn` is the key source of truth
 * (`as const satisfies`), and `editionsTr` is typed `Record<EditionsMessageId,
 * string>` — a missing or extra Turkish key fails `tsc -b`.
 *
 * These keys are merged into the global `i18n/messages/{en,tr}.ts` catalogues
 * (namespaced, no collisions) and served by the app-level `I18nProvider`.
 */

export const editionsEn = {
  // List page
  'editions.list.title': 'Editions',
  'editions.list.description':
    'Define the packages tenants can subscribe to and what each one includes.',
  'editions.list.create': 'New edition',
  'editions.list.empty': 'No editions found.',
  'editions.list.error': 'Editions could not be loaded.',

  // Data-grid columns
  'editions.columns.name': 'Name',
  'editions.columns.displayName': 'Display name',
  'editions.columns.monthlyPrice': 'Monthly',
  'editions.columns.annualPrice': 'Annual',
  'editions.columns.trialDayCount': 'Trial (days)',
  'editions.columns.graceDayCount': 'Grace (days)',
  'editions.columns.active': 'Status',

  // Badges
  'editions.badge.active': 'Active',
  'editions.badge.inactive': 'Inactive',
  'editions.badge.free': 'Free',

  // Row actions
  'editions.actions.menu': 'Open edition actions',
  'editions.actions.edit': 'Edit',
  'editions.actions.delete': 'Delete',

  // Delete confirmation
  'editions.delete.title': 'Delete edition',
  'editions.delete.description':
    'The edition "{name}" will be permanently deleted. Editions used by a subscription cannot be deleted.',
  'editions.delete.confirm': 'Delete',
  'editions.delete.cancel': 'Cancel',

  // Form page
  'editions.form.createTitle': 'Create edition',
  'editions.form.editTitle': 'Edit edition',
  'editions.form.sectionGeneral': 'General',
  'editions.form.sectionPricing': 'Pricing',
  'editions.form.sectionLifecycle': 'Trial & lifecycle',
  'editions.form.name': 'Name',
  'editions.form.namePlaceholder': 'e.g. standard',
  'editions.form.nameHint':
    'Unique technical name; it cannot be changed later.',
  'editions.form.displayName': 'Display name',
  'editions.form.displayNamePlaceholder': 'e.g. Standard',
  'editions.form.description': 'Description',
  'editions.form.descriptionPlaceholder': 'Shown on the pricing table.',
  'editions.form.monthlyPrice': 'Monthly price',
  'editions.form.annualPrice': 'Annual price',
  'editions.form.currency': 'Currency',
  'editions.form.currencyPlaceholder': 'e.g. USD',
  'editions.form.priceHint': 'Leave empty for a free edition.',
  'editions.form.trialDayCount': 'Trial days',
  'editions.form.trialHint': '0 disables the trial. Free editions allow no trial.',
  'editions.form.graceDayCount': 'Grace days',
  'editions.form.graceHint':
    'Days the subscription stays usable after the period ends.',
  'editions.form.expiringEdition': 'Expiring edition',
  'editions.form.expiringEditionHint':
    'Tenants are downgraded to this edition when the subscription expires. The target must be free.',
  'editions.form.expiringEditionNone': 'None',
  'editions.form.sortOrder': 'Sort order',
  'editions.form.isActive': 'Active',
  'editions.form.isActiveHint':
    'Inactive editions cannot be assigned to new tenants.',
  'editions.form.submitCreate': 'Create',
  'editions.form.submitUpdate': 'Save',
  'editions.form.saving': 'Saving…',
  'editions.form.cancel': 'Cancel',
  'editions.form.loadError': 'Edition could not be loaded.',
  'editions.form.numberError': 'Enter a number of 0 or greater.',

  // Feature values editor
  'editions.features.title': 'Features',
  'editions.features.description':
    'Values assigned here apply to every tenant on this edition unless overridden.',
  'editions.features.empty': 'No feature definitions available.',
  'editions.features.error': 'Feature definitions could not be loaded.',
  'editions.features.default': 'Default: {value}',
  'editions.features.save': 'Save features',
  'editions.features.saving': 'Saving…',
  'editions.features.createHint':
    'Feature values can be assigned once the edition has been created.',

  // Mutation toasts
  'editions.toast.created': 'Edition created.',
  'editions.toast.updated': 'Edition updated.',
  'editions.toast.deleted': 'Edition deleted.',
  'editions.toast.featuresSaved': 'Feature values saved.',
  'editions.toast.error': 'Operation failed. Please try again.',
} as const satisfies Record<string, string>;

export type EditionsMessageId = keyof typeof editionsEn;

// Typed against the English key set so the two catalogues cannot drift (1:1).
export const editionsTr: Record<EditionsMessageId, string> = {
  // List page
  'editions.list.title': 'Paketler',
  'editions.list.description':
    'Kiracıların abone olabileceği paketleri ve içeriklerini tanımlayın.',
  'editions.list.create': 'Yeni paket',
  'editions.list.empty': 'Paket bulunamadı.',
  'editions.list.error': 'Paketler yüklenemedi.',

  // Data-grid columns
  'editions.columns.name': 'Ad',
  'editions.columns.displayName': 'Görünen ad',
  'editions.columns.monthlyPrice': 'Aylık',
  'editions.columns.annualPrice': 'Yıllık',
  'editions.columns.trialDayCount': 'Deneme (gün)',
  'editions.columns.graceDayCount': 'Ek süre (gün)',
  'editions.columns.active': 'Durum',

  // Badges
  'editions.badge.active': 'Aktif',
  'editions.badge.inactive': 'Pasif',
  'editions.badge.free': 'Ücretsiz',

  // Row actions
  'editions.actions.menu': 'Paket işlemlerini aç',
  'editions.actions.edit': 'Düzenle',
  'editions.actions.delete': 'Sil',

  // Delete confirmation
  'editions.delete.title': 'Paketi sil',
  'editions.delete.description':
    '"{name}" paketi kalıcı olarak silinecek. Bir abonelikte kullanılan paketler silinemez.',
  'editions.delete.confirm': 'Sil',
  'editions.delete.cancel': 'İptal',

  // Form page
  'editions.form.createTitle': 'Paket oluştur',
  'editions.form.editTitle': 'Paketi düzenle',
  'editions.form.sectionGeneral': 'Genel',
  'editions.form.sectionPricing': 'Fiyatlandırma',
  'editions.form.sectionLifecycle': 'Deneme ve yaşam döngüsü',
  'editions.form.name': 'Ad',
  'editions.form.namePlaceholder': 'örn. standard',
  'editions.form.nameHint': 'Benzersiz teknik ad; sonradan değiştirilemez.',
  'editions.form.displayName': 'Görünen ad',
  'editions.form.displayNamePlaceholder': 'örn. Standart',
  'editions.form.description': 'Açıklama',
  'editions.form.descriptionPlaceholder': 'Fiyat tablosunda gösterilir.',
  'editions.form.monthlyPrice': 'Aylık fiyat',
  'editions.form.annualPrice': 'Yıllık fiyat',
  'editions.form.currency': 'Para birimi',
  'editions.form.currencyPlaceholder': 'örn. TRY',
  'editions.form.priceHint': 'Ücretsiz paket için boş bırakın.',
  'editions.form.trialDayCount': 'Deneme günü',
  'editions.form.trialHint':
    '0 denemeyi kapatır. Ücretsiz paketlerde deneme tanımlanamaz.',
  'editions.form.graceDayCount': 'Ek süre günü',
  'editions.form.graceHint':
    'Dönem bittikten sonra aboneliğin kullanılabilir kaldığı gün sayısı.',
  'editions.form.expiringEdition': 'Süre sonu paketi',
  'editions.form.expiringEditionHint':
    'Abonelik sona erdiğinde kiracı bu pakete düşürülür. Hedef paket ücretsiz olmalıdır.',
  'editions.form.expiringEditionNone': 'Yok',
  'editions.form.sortOrder': 'Sıra',
  'editions.form.isActive': 'Aktif',
  'editions.form.isActiveHint': 'Pasif paketler yeni kiracılara atanamaz.',
  'editions.form.submitCreate': 'Oluştur',
  'editions.form.submitUpdate': 'Kaydet',
  'editions.form.saving': 'Kaydediliyor…',
  'editions.form.cancel': 'İptal',
  'editions.form.loadError': 'Paket yüklenemedi.',
  'editions.form.numberError': '0 veya daha büyük bir sayı girin.',

  // Feature values editor
  'editions.features.title': 'Özellikler',
  'editions.features.description':
    'Burada atanan değerler, override edilmediği sürece bu paketteki tüm kiracılara uygulanır.',
  'editions.features.empty': 'Tanımlı özellik yok.',
  'editions.features.error': 'Özellik tanımları yüklenemedi.',
  'editions.features.default': 'Varsayılan: {value}',
  'editions.features.save': 'Özellikleri kaydet',
  'editions.features.saving': 'Kaydediliyor…',
  'editions.features.createHint':
    'Özellik değerleri paket oluşturulduktan sonra atanabilir.',

  // Mutation toasts
  'editions.toast.created': 'Paket oluşturuldu.',
  'editions.toast.updated': 'Paket güncellendi.',
  'editions.toast.deleted': 'Paket silindi.',
  'editions.toast.featuresSaved': 'Özellik değerleri kaydedildi.',
  'editions.toast.error': 'İşlem başarısız. Lütfen tekrar deneyin.',
};

export const editionsMessages: Record<'en' | 'tr', Record<string, string>> = {
  en: editionsEn,
  tr: editionsTr,
};
