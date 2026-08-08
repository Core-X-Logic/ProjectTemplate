/**
 * Billing providers feature message catalogue (flat, dot-keyed; en + tr, 1:1).
 *
 * Parity is enforced at COMPILE TIME: `billingProvidersEn` is the key source of
 * truth (`as const satisfies`), and `billingProvidersTr` is typed
 * `Record<BillingProvidersMessageId, string>` — a missing or extra Turkish key
 * fails `tsc -b`. Merged into `i18n/messages/{en,tr}.ts` (namespaced).
 */

export const billingProvidersEn = {
  // Page
  'billingProviders.list.title': 'Payment providers',
  'billingProviders.list.description':
    'Manage payment provider credentials and the failover order used for new checkouts.',
  'billingProviders.list.error': 'Payment providers could not be loaded.',
  'billingProviders.list.empty': 'No payment providers are available.',
  'billingProviders.list.securityHint':
    'Credentials are write-only: they are encrypted at rest and never shown again — only a masked hint is displayed.',

  // Status badges
  'billingProviders.badge.stored': 'Configured in portal',
  'billingProviders.badge.env': 'From environment',
  'billingProviders.badge.unconfigured': 'Not configured',
  'billingProviders.badge.enabled': 'Enabled',
  'billingProviders.badge.disabled': 'Disabled',
  'billingProviders.badge.unordered': 'Unordered',

  // Card
  'billingProviders.card.orderLabel': 'Failover position {position}',
  'billingProviders.card.maskLabel': 'Stored credential: {mask}',
  'billingProviders.card.envHint':
    'Credentials come from server environment variables. Saving here overrides them.',
  'billingProviders.card.unconfiguredHint':
    'This provider cannot be used for checkout until credentials are entered.',

  // Actions
  'billingProviders.actions.configure': 'Enter credentials',
  'billingProviders.actions.update': 'Update credentials',
  'billingProviders.actions.clear': 'Clear credentials',
  'billingProviders.actions.moveUp': 'Move {name} up',
  'billingProviders.actions.moveDown': 'Move {name} down',

  // Credentials dialog
  'billingProviders.dialog.title': '{name} credentials',
  'billingProviders.dialog.description':
    'Values are write-only: existing values are never displayed. Stored values are replaced on save.',
  'billingProviders.dialog.updateHint':
    'Leave a field empty to keep its current value.',
  'billingProviders.dialog.requiredAll':
    'All fields are required for the first save.',
  'billingProviders.dialog.atLeastOne':
    'Enter at least one field to change, or cancel.',
  'billingProviders.dialog.submit': 'Save',
  'billingProviders.dialog.saving': 'Saving…',
  'billingProviders.dialog.cancel': 'Cancel',

  // Field labels (wire names are the backend contract; labels are human)
  'billingProviders.field.merchantId': 'Merchant ID',
  'billingProviders.field.merchantKey': 'Merchant key',
  'billingProviders.field.merchantSalt': 'Merchant salt',
  'billingProviders.field.apiKey': 'API key',
  'billingProviders.field.secretKey': 'Secret key',
  'billingProviders.field.configuredBadge': 'Set',

  // Clear confirmation
  'billingProviders.clear.title': 'Clear credentials',
  'billingProviders.clear.description':
    'The stored {name} credentials will be deleted. The provider falls back to the server environment configuration, if any. Payments already in flight are not affected.',
  'billingProviders.clear.confirm': 'Clear',
  'billingProviders.clear.cancel': 'Cancel',

  // Order section
  'billingProviders.order.title': 'Failover order',
  'billingProviders.order.description':
    'New checkouts try providers in this order. A provider is skipped only on connection errors or 5xx responses.',

  // Mutation toasts
  'billingProviders.toast.saved': 'Credentials saved.',
  'billingProviders.toast.cleared': 'Credentials cleared.',
  'billingProviders.toast.orderSaved': 'Provider order saved.',
  'billingProviders.toast.error': 'Operation failed. Please try again.',
} as const satisfies Record<string, string>;

export type BillingProvidersMessageId = keyof typeof billingProvidersEn;

// Typed against the English key set so the two catalogues cannot drift (1:1).
export const billingProvidersTr: Record<BillingProvidersMessageId, string> = {
  // Page
  'billingProviders.list.title': 'Ödeme sağlayıcıları',
  'billingProviders.list.description':
    'Ödeme sağlayıcı bilgilerini ve yeni ödemelerde kullanılacak yedekleme (failover) sırasını yönetin.',
  'billingProviders.list.error': 'Ödeme sağlayıcıları yüklenemedi.',
  'billingProviders.list.empty': 'Kullanılabilir ödeme sağlayıcısı yok.',
  'billingProviders.list.securityHint':
    'Bilgiler yalnızca yazılabilir: şifrelenerek saklanır ve bir daha gösterilmez — yalnızca maskeli bir ipucu görüntülenir.',

  // Status badges
  'billingProviders.badge.stored': 'Portalden ayarlı',
  'billingProviders.badge.env': "Ortam değişkeninden",
  'billingProviders.badge.unconfigured': 'Ayarsız',
  'billingProviders.badge.enabled': 'Aktif',
  'billingProviders.badge.disabled': 'Pasif',
  'billingProviders.badge.unordered': 'Sırasız',

  // Card
  'billingProviders.card.orderLabel': 'Yedekleme sırası {position}',
  'billingProviders.card.maskLabel': 'Kayıtlı bilgi: {mask}',
  'billingProviders.card.envHint':
    'Bilgiler sunucu ortam değişkenlerinden geliyor. Buradan kaydederseniz onların yerine geçer.',
  'billingProviders.card.unconfiguredHint':
    'Bilgiler girilene kadar bu sağlayıcı ödeme başlatmada kullanılamaz.',

  // Actions
  'billingProviders.actions.configure': 'Bilgileri gir',
  'billingProviders.actions.update': 'Bilgileri güncelle',
  'billingProviders.actions.clear': 'Bilgileri temizle',
  'billingProviders.actions.moveUp': "{name} sağlayıcısını yukarı taşı",
  'billingProviders.actions.moveDown': "{name} sağlayıcısını aşağı taşı",

  // Credentials dialog
  'billingProviders.dialog.title': '{name} bilgileri',
  'billingProviders.dialog.description':
    'Değerler yalnızca yazılabilir: mevcut değerler asla gösterilmez. Kaydedince saklanan değerlerin yerine geçer.',
  'billingProviders.dialog.updateHint':
    'Bir alanı boş bırakırsanız mevcut değeri korunur.',
  'billingProviders.dialog.requiredAll':
    'İlk kayıt için tüm alanlar zorunludur.',
  'billingProviders.dialog.atLeastOne':
    'Değiştirmek için en az bir alan girin ya da iptal edin.',
  'billingProviders.dialog.submit': 'Kaydet',
  'billingProviders.dialog.saving': 'Kaydediliyor…',
  'billingProviders.dialog.cancel': 'İptal',

  // Field labels (wire names are the backend contract; labels are human)
  'billingProviders.field.merchantId': 'Mağaza no (Merchant ID)',
  'billingProviders.field.merchantKey': 'Mağaza anahtarı (Merchant key)',
  'billingProviders.field.merchantSalt': 'Mağaza gizli anahtarı (Merchant salt)',
  'billingProviders.field.apiKey': 'API anahtarı',
  'billingProviders.field.secretKey': 'Gizli anahtar (Secret key)',
  'billingProviders.field.configuredBadge': 'Ayarlı',

  // Clear confirmation
  'billingProviders.clear.title': 'Bilgileri temizle',
  'billingProviders.clear.description':
    'Kayıtlı {name} bilgileri silinecek. Varsa sunucu ortam yapılandırmasına geri dönülür. Devam eden ödemeler etkilenmez.',
  'billingProviders.clear.confirm': 'Temizle',
  'billingProviders.clear.cancel': 'İptal',

  // Order section
  'billingProviders.order.title': 'Yedekleme sırası',
  'billingProviders.order.description':
    'Yeni ödemeler sağlayıcıları bu sırayla dener. Bir sağlayıcı yalnızca bağlantı hatası veya 5xx yanıtında atlanır.',

  // Mutation toasts
  'billingProviders.toast.saved': 'Bilgiler kaydedildi.',
  'billingProviders.toast.cleared': 'Bilgiler temizlendi.',
  'billingProviders.toast.orderSaved': 'Sağlayıcı sırası kaydedildi.',
  'billingProviders.toast.error': 'İşlem başarısız. Lütfen tekrar deneyin.',
};

export const billingProvidersMessages: Record<
  'en' | 'tr',
  Record<string, string>
> = {
  en: billingProvidersEn,
  tr: billingProvidersTr,
};
