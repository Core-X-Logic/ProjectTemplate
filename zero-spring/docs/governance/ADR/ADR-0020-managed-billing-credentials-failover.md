# ADR-0020: Portal'dan yönetilebilir ödeme sağlayıcı kimlik bilgileri + checkout failover

- **Durum:** Accepted · **Tarih:** 2026-08-08
- **İş kaydı:** `feat/managed-billing-credentials-failover` · sağlayıcı stratejisi **ADR-0017** ·
  SPI **ADR-0010** · anahtar riski **PROD-R49** · saklama deseni `FieldEncryptionService`
  (2FA/TOTP dilimi)

## Bağlam

ADR-0017'ye kadar sağlayıcı kimlik bilgileri yalnız ortamdan geliyordu
(`zero.billing.<provider>.*` ← env placeholder), sağlayıcı bean'i
`@ConditionalOnProperty(enabled)` arkasında **hiç kayıt olmuyordu** ve "bean var" ile "sağlayıcı
açık" aynı olguydu. Operatör bir sağlayıcıyı açmak/kapamak ya da anahtar değiştirmek için deploy
istiyordu; PayTR+iyzico'nun birlikte satıldığı kurulumda "biri çökünce diğerinden başlat" diye bir
kavram yoktu.

## Karar

### 1. Saklama: AYRI tablo (`billing_provider_credentials`, V16) — settings DEĞİL

Sağlayıcı başına bir satır: `provider` (unique), `enabled`, `display_order`,
`credentials_secret`. Settings modülü bilinçli reddedildi: (a) `GET /api/settings/host` değerleri
UI'ya **geri döner** — secret asla dönmemeli, generic sözleşmeyi kırmak gerekirdi; (b)
`SettingDefinition.encrypted` bayrağı ölü — canlandırmak SettingManager + cache'i (düz metin
cache riski) ellemek demekti; (c) kimlik bilgisi çok-alanlı atomik bir yapı + sıra + enabled — bir
domain kavramı. Tablo **host-global**: `tenant_id` YOK (sağlayıcı hesabı kurulumundur), dolayısıyla
`RlsCoverageIT` taramaz ve ADR-0019 muafiyet listesine giriş **gerekmez** — telafi edici kontrol,
tek yüzeyin `Side.HOST` `billing.credentials.manage` izni arkasında olması (ADR-0015 deseni).

### 2. Şifreleme, hash değil — tek ciphertext

`credentials_secret = FieldEncryptionService.encrypt(JSON{alan→değer})` (AES-256-GCM,
`zero.crypto.field-key` — PROD-R49'daki anahtarın kendisi). **Hash değil**, çünkü değerler
sağlayıcının API'sine GERİ GÖNDERİLİR: geri çevrilebilirlik gereksinimdir, hash tanımı gereği
reddeder (parola/davet token'ı tam tersi sınıftır — V14/V15). Alan adı "secret" içerir →
`AuditSupport.isSensitive` entity-history'de `***` basar, ek kablolama yok. Çözülmüş değerler
**cache'lenmez**: checkout başına birkaç decrypt ucuzdur; cache, secret'ı isteğin ötesinde bellekte
tutmak demekti (reddedilen alternatif).

### 3. Bean'ler artık KOŞULSUZ; "açık mı" sorusu `BillingProviderAvailability`'de

Bean runtime'da var olamayacağı için `@ConditionalOnProperty` kalktı; üç sağlayıcı bean'i her zaman
kayıtlı, kimlik bilgilerini **çağrı anında** çözen `Managed*Properties` sarmalayıcıları üstünde
(adapter içleri değişmedi — PayTR/iyzico getter'ları zaten çağrı anında okuyordu; portal
değişikliği restart istemez). Yüzey kuralları:

- `surfaceExists` (webhook/callback/mutabakat): env-enabled **VEYA** DB'de kimlik bilgisi var —
  `enabled` bayrağına BAKILMAZ. **"Disabled = yeni checkout'a kapalı, webhook'a değil":** bir
  sağlayıcıda başlayan ödeme o sağlayıcıda bitmek zorunda. İkisi de yoksa 404 — taze klon davranışı
  aynen (`*DisabledSurfaceIT` pinli).
- `checkoutEnabled` (yeni checkout): env-enabled VEYA (DB satırı enabled **ve** kimlik bilgisi
  var). DB yolunun bütünlük doğrulaması yazım anında (`BillingProviderAdminService`, boot
  validator'larının yazım-zamanı karşılığı); env yolunda `Billing*SecretValidator` boot reddine
  devam eder.

### 4. Failover + devre kesici (kütüphanesiz)

Checkout'ta sağlayıcı SEÇİLMEMİŞSE adaylar `display_order` sırasıyla denenir. Sıradakine geçiş
YALNIZ transport-sınıfı hatada: `ResourceAccessException`, cause zincirinde
`IOException`/`TimeoutException`, HTTP **5xx**. **4xx geçmez** — sağlayıcı cevap verdi ve
reddetti; aynı bozuk isteği başka sağlayıcıya taşımak hatayı yayar (mutasyonla kanıtlı:
`BillingCheckoutFailoverIT.clientErrorDoesNotFailOver`). Sağlayıcı başına 2 **ardışık** transport
hatası → 60 sn cool-down (`BillingCheckoutCircuitBreaker`, zaman `SchedulingConfig.clock()`
bean'inden — testler MutableClock ile oynatır). Kesici bellek-içi ve node başına: durum, checkout
TRANSACTION'ının rollback'inden bağımsız yaşamak zorunda. Kesici tek başına checkout REDDETMEZ —
bütün adaylar cool-down'daysa yine denenir; isimle seçilmiş sağlayıcıda failover YOK.
**resilience4j eklenmedi:** bir map + iki kural + test edilebilir Clock, konfigürasyon yüzeyi bu
sınıftan büyük bir bağımlılığı yenmiyor.

`payments.provider` her denemeden ÖNCE yazılır ve failover'da yeniden yazılır → commit'lenen satır
**gerçekten session'ı kesen** sağlayıcıyı taşır (webhook + mutabakat bu satırla yönlenir; V9
sözleşmesi korunur). Log her deneme için "hangi sağlayıcı / neden geçildi" satırı basar (yanıt
gövdesi değil).

## Sonuçlar ve kabul edilen riskler

- **Transaction içinde N canlı çağrı:** failover, tek HTTPS round-trip kabulünü (ADR-0014 dilim
  notu) "aday sayısı × adapter tavanı"na genişletir; DB bağlantısı bu süre boyunca tutulur.
  Sınır: aday sayısı küçük (≤3) ve cool-down kötü sağlayıcıyı rotasyondan çıkarır. Kayıt: risk
  defteri R-50.
- **Stripe istisnası:** `StripeBillingProvider` API client'ını CONSTRUCTION'da kurar
  (instance-scoped `StripeClient` — SDK'nın mutable global'ine bilinçli tercih). Portal'dan yazılan
  Stripe `secretKey` checkout için **restart'ta** etkinleşir; webhook secret/publishable key çağrı
  anında çözülür. Stripe uykuda (ADR-0017) ve portal varsayılan ekranında listelenmez ama
  DIŞLANMAZ; kaldırmak adapter içine dokunur → ayrı iş.
- **Anahtar bağımlılığı büyüdü:** `FIELD_ENCRYPTION_KEY` kaybı artık 2FA'ya ek olarak sağlayıcı
  kimlik bilgilerini de çözülemez kılar (PROD-R49 kapsam genişlemesi — risk defterinde güncellendi).
  Kayıp durumunda değerler operatörde durur: satırı silip yeniden girmek yeter (2FA'dan farklı
  olarak yeniden üretilebilir).
- **Kimlik bilgisi yüzeyi write-only:** GET yalnız maske/alan adı döner; PUT merge'dir (boş alan =
  "değiştirme"); temizlik DELETE. `BillingProviderCredentialsAdminIT` ham değerin hiçbir yanıtta
  olmadığını ve at-rest ciphertext'i pinler.
