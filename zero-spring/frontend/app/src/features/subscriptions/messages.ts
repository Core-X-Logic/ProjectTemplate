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
  'subscriptions.actions.detail': 'History & detail',
  'subscriptions.detail.description':
    'Subscription state and its full lifecycle history.',
  'subscriptions.detail.price': 'Price',
  'subscriptions.detail.trialEndAt': 'Trial ends',
  'subscriptions.detail.cancelledAt': 'Cancelled at',
  'subscriptions.detail.history': 'Lifecycle history',
  'subscriptions.detail.historyEmpty': 'No lifecycle events yet.',
  'subscriptions.detail.error': 'The subscription detail could not be loaded.',
  'subscriptions.detail.reason.PROVISIONED': 'Provisioned',
  'subscriptions.detail.reason.EDITION_ASSIGNED': 'Edition assigned',
  'subscriptions.detail.reason.EDITION_CHANGED': 'Edition changed',
  'subscriptions.detail.reason.ACTIVATED': 'Activated',
  'subscriptions.detail.reason.CANCELLED': 'Cancelled',
  'subscriptions.detail.reason.DOWNGRADED': 'Downgraded to free edition',
  'subscriptions.detail.reason.TRIAL_ENDED': 'Trial ended',
  'subscriptions.detail.reason.PERIOD_ENDED': 'Billing period ended',
  'subscriptions.detail.reason.GRACE_ENDED': 'Grace window ended',
  'subscriptions.detail.reason.EXPIRY_NOTICE': 'Expiry warning sent',
  'subscriptions.actions.assign': 'Assign edition',
  'subscriptions.actions.checkout': 'Pay & assign',
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

  // Checkout ("Pay & assign") dialog
  'subscriptions.checkout.title': 'Pay & assign',
  'subscriptions.checkout.description':
    'Opens the provider’s hosted payment page for "{tenant}". The edition is assigned only after the provider confirms the payment.',
  'subscriptions.checkout.provider': 'Payment provider',
  'subscriptions.checkout.provider.paytr': 'PayTR',
  'subscriptions.checkout.provider.iyzico': 'iyzico',
  'subscriptions.checkout.submit': 'Start payment',
  'subscriptions.checkout.starting': 'Starting…',
  'subscriptions.checkout.cancel': 'Cancel',
  'subscriptions.checkout.close': 'Close',
  'subscriptions.checkout.error': 'The payment could not be started.',
  'subscriptions.checkout.retry': 'Retry',
  'subscriptions.checkout.toast.error':
    'The payment could not be started. Please try again.',
  'subscriptions.checkout.started.title': 'Payment started',
  'subscriptions.checkout.started.description':
    'The payment page opened in a new tab.',
  'subscriptions.checkout.started.warning':
    'Activation completes on the server once the provider confirms the payment (webhook / reconciliation). Closing the payment tab or this dialog neither cancels nor confirms anything — the subscription status appears on the subscriptions list.',
  'subscriptions.checkout.started.paymentId': 'Payment reference: {id}',
  'subscriptions.checkout.started.fallback':
    'If the payment page did not open, use this link:',
  'subscriptions.checkout.started.fallbackLink': 'Open the payment page',

  // Payment result landing pages (provider redirect targets)
  'subscriptions.paymentResult.success.title':
    'Payment received by the provider',
  'subscriptions.paymentResult.success.description':
    'The payment provider accepted the payment.',
  'subscriptions.paymentResult.success.warning':
    'Activation completes server-side once the payment is confirmed (webhook / reconciliation) — this page does not mean the subscription is active yet. Check the current status on the subscriptions list.',
  'subscriptions.paymentResult.cancel.title': 'Payment not completed',
  'subscriptions.paymentResult.cancel.description':
    'The payment was cancelled or could not be completed.',
  'subscriptions.paymentResult.cancel.warning':
    'Nothing was changed. You can start a new payment from the subscriptions page.',
  'subscriptions.paymentResult.goToSubscriptions': 'Go to subscriptions',

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
  'subscriptions.actions.detail': 'Geçmiş ve detay',
  'subscriptions.detail.description':
    'Abonelik durumu ve tam yaşam döngüsü geçmişi.',
  'subscriptions.detail.price': 'Fiyat',
  'subscriptions.detail.trialEndAt': 'Deneme bitişi',
  'subscriptions.detail.cancelledAt': 'İptal tarihi',
  'subscriptions.detail.history': 'Yaşam döngüsü geçmişi',
  'subscriptions.detail.historyEmpty': 'Henüz yaşam döngüsü olayı yok.',
  'subscriptions.detail.error': 'Abonelik detayı yüklenemedi.',
  'subscriptions.detail.reason.PROVISIONED': 'Oluşturuldu',
  'subscriptions.detail.reason.EDITION_ASSIGNED': 'Paket atandı',
  'subscriptions.detail.reason.EDITION_CHANGED': 'Paket değişti',
  'subscriptions.detail.reason.ACTIVATED': 'Etkinleştirildi',
  'subscriptions.detail.reason.CANCELLED': 'İptal edildi',
  'subscriptions.detail.reason.DOWNGRADED': 'Ücretsiz pakete düşürüldü',
  'subscriptions.detail.reason.TRIAL_ENDED': 'Deneme süresi doldu',
  'subscriptions.detail.reason.PERIOD_ENDED': 'Faturalama dönemi bitti',
  'subscriptions.detail.reason.GRACE_ENDED': 'Ek süre doldu',
  'subscriptions.detail.reason.EXPIRY_NOTICE': 'Süre dolumu uyarısı gönderildi',
  'subscriptions.actions.assign': 'Paket ata',
  'subscriptions.actions.checkout': 'Öde ve ata',
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

  // Checkout ("Pay & assign") dialog
  'subscriptions.checkout.title': 'Öde ve ata',
  'subscriptions.checkout.description':
    '"{tenant}" için sağlayıcının güvenli ödeme sayfası açılır. Paket, yalnızca sağlayıcı ödemeyi onayladıktan sonra atanır.',
  'subscriptions.checkout.provider': 'Ödeme sağlayıcısı',
  'subscriptions.checkout.provider.paytr': 'PayTR',
  'subscriptions.checkout.provider.iyzico': 'iyzico',
  'subscriptions.checkout.submit': 'Ödemeyi başlat',
  'subscriptions.checkout.starting': 'Başlatılıyor…',
  'subscriptions.checkout.cancel': 'İptal',
  'subscriptions.checkout.close': 'Kapat',
  'subscriptions.checkout.error': 'Ödeme başlatılamadı.',
  'subscriptions.checkout.retry': 'Tekrar dene',
  'subscriptions.checkout.toast.error':
    'Ödeme başlatılamadı. Lütfen tekrar deneyin.',
  'subscriptions.checkout.started.title': 'Ödeme başlatıldı',
  'subscriptions.checkout.started.description':
    'Ödeme sayfası yeni sekmede açıldı.',
  'subscriptions.checkout.started.warning':
    'Etkinleştirme, sağlayıcı ödemeyi onayladıktan sonra sunucu tarafında tamamlanır (webhook / mutabakat). Ödeme sekmesini veya bu pencereyi kapatmak hiçbir şeyi iptal etmez ya da onaylamaz — abonelik durumu abonelikler listesinde görünür.',
  'subscriptions.checkout.started.paymentId': 'Ödeme referansı: {id}',
  'subscriptions.checkout.started.fallback':
    'Ödeme sayfası açılmadıysa bu bağlantıyı kullanın:',
  'subscriptions.checkout.started.fallbackLink': 'Ödeme sayfasını aç',

  // Payment result landing pages (provider redirect targets)
  'subscriptions.paymentResult.success.title': 'Ödeme sağlayıcıya ulaştı',
  'subscriptions.paymentResult.success.description':
    'Ödeme sağlayıcısı ödemeyi kabul etti.',
  'subscriptions.paymentResult.success.warning':
    'Etkinleştirme, ödeme onaylandıktan sonra sunucu tarafında tamamlanır (webhook / mutabakat) — bu sayfa aboneliğin etkinleştiği anlamına gelmez. Güncel durumu abonelikler listesinden kontrol edin.',
  'subscriptions.paymentResult.cancel.title': 'Ödeme tamamlanmadı',
  'subscriptions.paymentResult.cancel.description':
    'Ödeme iptal edildi veya tamamlanamadı.',
  'subscriptions.paymentResult.cancel.warning':
    'Hiçbir değişiklik yapılmadı. Abonelikler sayfasından yeni bir ödeme başlatabilirsiniz.',
  'subscriptions.paymentResult.goToSubscriptions': 'Abonelikler sayfasına git',

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
