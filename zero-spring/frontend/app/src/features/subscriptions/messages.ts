/**
 * Subscriptions feature message catalogue (flat, dot-keyed; en + tr, keys 1:1).
 *
 * Parity is enforced at COMPILE TIME: `subscriptionsEn` is the key source of
 * truth (`as const satisfies`), and `subscriptionsTr` is typed
 * `Record<SubscriptionsMessageId, string>` — a missing or extra Turkish key
 * fails `tsc -b`.
 *
 * Merged into the global `i18n/messages/{en,tr}.ts` catalogues (namespaced, no
 * collisions) and served by the app-level `I18nProvider`.
 */

export const subscriptionsEn = {
  // List page
  'subscriptions.list.title': 'Subscriptions',
  'subscriptions.list.description':
    'Assign packages to tenants and manage their subscription lifecycle.',
  'subscriptions.list.empty': 'No subscriptions found.',
  'subscriptions.list.error': 'Subscriptions could not be loaded.',

  // Data-grid columns
  'subscriptions.columns.tenant': 'Tenant',
  'subscriptions.columns.edition': 'Edition',
  'subscriptions.columns.status': 'Status',
  'subscriptions.columns.currentPeriodEndAt': 'Period ends',
  'subscriptions.columns.billingPeriod': 'Billing',

  // Status badges
  'subscriptions.status.TRIALING': 'Trial',
  'subscriptions.status.ACTIVE': 'Active',
  'subscriptions.status.GRACE': 'Grace',
  'subscriptions.status.EXPIRED': 'Expired',
  'subscriptions.status.CANCELLED': 'Cancelled',
  'subscriptions.status.PENDING_PAYMENT': 'Pending payment',
  'subscriptions.status.unknown': 'Unknown',

  // Billing periods
  'subscriptions.period.MONTHLY': 'Monthly',
  'subscriptions.period.ANNUAL': 'Annual',
  'subscriptions.period.none': '—',

  // Row actions
  'subscriptions.actions.menu': 'Open subscription actions',
  'subscriptions.actions.assign': 'Assign edition',
  'subscriptions.actions.activate': 'Activate',
  'subscriptions.actions.cancel': 'Cancel subscription',
  'subscriptions.actions.features': 'Feature overrides',

  // Assign-edition dialog
  'subscriptions.assign.title': 'Assign edition',
  'subscriptions.assign.description':
    'Assigning an edition snapshots its current price onto the subscription of "{tenant}".',
  'subscriptions.assign.edition': 'Edition',
  'subscriptions.assign.editionPlaceholder': 'Select an edition',
  'subscriptions.assign.billingPeriod': 'Billing period',
  'subscriptions.assign.trial': 'Start as trial',
  'subscriptions.assign.trialHint':
    'Only available for paid editions that define a trial period.',
  'subscriptions.assign.submit': 'Assign',
  'subscriptions.assign.saving': 'Assigning…',
  'subscriptions.assign.cancel': 'Cancel',
  'subscriptions.assign.loadError': 'Editions could not be loaded.',
  'subscriptions.assign.empty': 'No editions are available to assign.',
  'subscriptions.assign.required': 'Select an edition to continue.',

  // Cancel confirmation
  'subscriptions.cancelConfirm.title': 'Cancel subscription',
  'subscriptions.cancelConfirm.description':
    'The subscription of "{tenant}" will be cancelled. The tenant keeps access until the end of the current period.',
  'subscriptions.cancelConfirm.confirm': 'Cancel subscription',
  'subscriptions.cancelConfirm.cancel': 'Keep subscription',

  // Tenant feature overrides
  'subscriptions.features.title': 'Feature overrides',
  'subscriptions.features.description':
    'Overrides win over the edition value; clear a field to fall back to the edition or default.',
  'subscriptions.features.empty': 'No features available for this tenant.',
  'subscriptions.features.error': 'Feature values could not be loaded.',
  'subscriptions.features.inherited': 'Inherited: {value}',
  'subscriptions.features.save': 'Save overrides',
  'subscriptions.features.saving': 'Saving…',
  'subscriptions.features.close': 'Close',

  // Mutation toasts
  'subscriptions.toast.assigned': 'Edition assigned.',
  'subscriptions.toast.activated': 'Subscription activated.',
  'subscriptions.toast.cancelled': 'Subscription cancelled.',
  'subscriptions.toast.featuresSaved': 'Feature overrides saved.',
  'subscriptions.toast.error': 'Operation failed. Please try again.',
} as const satisfies Record<string, string>;

export type SubscriptionsMessageId = keyof typeof subscriptionsEn;

// Typed against the English key set so the two catalogues cannot drift (1:1).
export const subscriptionsTr: Record<SubscriptionsMessageId, string> = {
  // List page
  'subscriptions.list.title': 'Abonelikler',
  'subscriptions.list.description':
    'Kiracılara paket atayın ve abonelik yaşam döngüsünü yönetin.',
  'subscriptions.list.empty': 'Abonelik bulunamadı.',
  'subscriptions.list.error': 'Abonelikler yüklenemedi.',

  // Data-grid columns
  'subscriptions.columns.tenant': 'Kiracı',
  'subscriptions.columns.edition': 'Paket',
  'subscriptions.columns.status': 'Durum',
  'subscriptions.columns.currentPeriodEndAt': 'Dönem sonu',
  'subscriptions.columns.billingPeriod': 'Faturalama',

  // Status badges
  'subscriptions.status.TRIALING': 'Deneme',
  'subscriptions.status.ACTIVE': 'Aktif',
  'subscriptions.status.GRACE': 'Ek süre',
  'subscriptions.status.EXPIRED': 'Süresi doldu',
  'subscriptions.status.CANCELLED': 'İptal edildi',
  'subscriptions.status.PENDING_PAYMENT': 'Ödeme bekliyor',
  'subscriptions.status.unknown': 'Bilinmiyor',

  // Billing periods
  'subscriptions.period.MONTHLY': 'Aylık',
  'subscriptions.period.ANNUAL': 'Yıllık',
  'subscriptions.period.none': '—',

  // Row actions
  'subscriptions.actions.menu': 'Abonelik işlemlerini aç',
  'subscriptions.actions.assign': 'Paket ata',
  'subscriptions.actions.activate': 'Etkinleştir',
  'subscriptions.actions.cancel': 'Aboneliği iptal et',
  'subscriptions.actions.features': 'Özellik override',

  // Assign-edition dialog
  'subscriptions.assign.title': 'Paket ata',
  'subscriptions.assign.description':
    'Paket atandığında güncel fiyat "{tenant}" aboneliğine anlık görüntü olarak kaydedilir.',
  'subscriptions.assign.edition': 'Paket',
  'subscriptions.assign.editionPlaceholder': 'Bir paket seçin',
  'subscriptions.assign.billingPeriod': 'Faturalama dönemi',
  'subscriptions.assign.trial': 'Deneme olarak başlat',
  'subscriptions.assign.trialHint':
    'Yalnızca deneme süresi tanımlı ücretli paketlerde kullanılabilir.',
  'subscriptions.assign.submit': 'Ata',
  'subscriptions.assign.saving': 'Atanıyor…',
  'subscriptions.assign.cancel': 'İptal',
  'subscriptions.assign.loadError': 'Paketler yüklenemedi.',
  'subscriptions.assign.empty': 'Atanabilecek paket yok.',
  'subscriptions.assign.required': 'Devam etmek için bir paket seçin.',

  // Cancel confirmation
  'subscriptions.cancelConfirm.title': 'Aboneliği iptal et',
  'subscriptions.cancelConfirm.description':
    '"{tenant}" kiracısının aboneliği iptal edilecek. Kiracı mevcut dönem sonuna kadar erişimini korur.',
  'subscriptions.cancelConfirm.confirm': 'Aboneliği iptal et',
  'subscriptions.cancelConfirm.cancel': 'Vazgeç',

  // Tenant feature overrides
  'subscriptions.features.title': 'Özellik override',
  'subscriptions.features.description':
    'Override değerleri paket değerinin önüne geçer; alanı temizlerseniz paket veya varsayılan değere döner.',
  'subscriptions.features.empty': 'Bu kiracı için özellik bulunamadı.',
  'subscriptions.features.error': 'Özellik değerleri yüklenemedi.',
  'subscriptions.features.inherited': 'Devralınan: {value}',
  'subscriptions.features.save': 'Override kaydet',
  'subscriptions.features.saving': 'Kaydediliyor…',
  'subscriptions.features.close': 'Kapat',

  // Mutation toasts
  'subscriptions.toast.assigned': 'Paket atandı.',
  'subscriptions.toast.activated': 'Abonelik etkinleştirildi.',
  'subscriptions.toast.cancelled': 'Abonelik iptal edildi.',
  'subscriptions.toast.featuresSaved': 'Özellik override kaydedildi.',
  'subscriptions.toast.error': 'İşlem başarısız. Lütfen tekrar deneyin.',
};

export const subscriptionsMessages: Record<
  'en' | 'tr',
  Record<string, string>
> = {
  en: subscriptionsEn,
  tr: subscriptionsTr,
};
