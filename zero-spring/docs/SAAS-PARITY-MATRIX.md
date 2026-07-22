# SaaS Parite Matrisi — ASP.NET Zero ↔ zero-spring

Referans: `docs/history/F5-SAAS-INVENTORY.md` (kaynağın ölçülmüş davranışı, doküman değil kod).
Kanıt kolonu gerçek test sınıfı / ekran / commit'e işaret eder. Durum: **Tam** / **Tam+** (kaynaktaki
bilinen kusur düzeltilerek) / **Kısmi** / **Deferred** (bilinçli erteleme, gerekçeli).

## 1. Edition yönetimi

| Kalem | ASP.NET Zero | zero-spring | Durum | Kanıt |
|---|---|---|---|---|
| Liste/detay/oluştur/güncelle/sil | `EditionAppService` | `GET/POST/PUT/DELETE /api/editions` | **Tam+** | `EditionCrudIT`; kaynakta update yalnız DisplayName+feature idi (K13) — burada fiyat/trial/grace de düzenlenebilir (abonelik fiyatı **snapshot**, ADR-0012) |
| Aktif/pasif | `IsActive` | `Edition.active` + update | **Tam** | `EditionCrudIT` |
| Edition feature seti | `SetFeatureValues` | `PUT /api/editions/{id}/features` + UI editörü | **Tam** | `feature-values-editor.tsx` + testi |
| Expiring edition (süre dolunca düşülecek paket) | `ExpiringEditionId` (dangling riski K14) | `expiring_edition_id` + **free-hedef zorunlu** doğrulama | **Tam+** | `SubscriptionLifecycleIT` downgrade; `SubscriptionService.downgradeToExpiringEdition` guard |
| Tenant'ları toplu başka edition'a taşıma | `MoveTenantsToAnotherEdition` | Tenant-başına `change-edition` var; **toplu taşıma ucu yok** | **Deferred** | Gerekçe: kaynakta bu uç, edition düzenlenemediği için (K13) zorunluydu; burada edition düzenlenebilir + fiyat snapshot → ihtiyaç operasyonel istisnaya indi. Silme, abonesi olan edition'da zaten engelli (`countByEditionId`) |

## 2. Feature yönetimi

| Kalem | ASP.NET Zero | zero-spring | Durum | Kanıt |
|---|---|---|---|---|
| Çözümleme zinciri | Tenant → Edition → Default | `tenant_features → edition_features → FeatureDefinition.default` | **Tam** | `FeatureResolutionIT` |
| Tenant'a yansıyan efektif görünüm | Features modal | `GET /api/tenant-features/{tenantId}` + `TenantFeaturesPanel` (override + edition + default kolonları) | **Tam** | `tenant-features-panel.test.tsx` |
| Edition feature değişince tenant etkisi | ABP cache | `@EvictsSaasCaches` — edition/tenant feature yazımı + edition ataması cache'i düşürür; Redis yoksa no-op degrade | **Tam** | `FeatureResolutionIT` (yazma-sonrası yeni değer) |
| Enforcement | `[RequiresFeature]` hiç kullanılmıyordu; imperatif | `@RequiresFeature` AOP **gerçekten kullanılıyor** + `intValue` limitleri | **Tam+** | `FeatureEnforcementIT`, `MaxUserCountIT` |
| Yazma yetkisi host-only | `Pages.Tenants.ChangeFeatures` | `tenantfeatures.manage` (Side.HOST) üçlü kilit | **Tam** | `SaasAuthorizationIT` (403 negatifleri) |

## 3. Subscription yaşam döngüsü

| Kalem | ASP.NET Zero | zero-spring | Durum | Kanıt |
|---|---|---|---|---|
| Durum modeli | **Implicit** (IsActive+EndDate+InTrial, K8) | **Explicit** `SubscriptionStatus` + guard'lı geçiş tablosu | **Tam+** | `SubscriptionStateMachineIT` (geçersiz geçiş → 400, sessiz no-op yok) |
| Başlatma (free/trial/paid) | SubscriptionStartType | S1/S2/S3: free→ACTIVE, trial→TRIALING, paid→PENDING_PAYMENT | **Tam** | `SubscriptionAssignmentIT`; tenant oluşturunca otomatik provisioning (`TenantLifecycleListener`) |
| Yükseltme/düşürme + proration | `GetUpgradePrice` (30/365 gün, K7) | `change-edition` + `ProrationCalculator` (takvim ayı, ADR-0013; minimum eşik altında ödemesiz anında değişim) | **Tam+** | `EditionChangeIT`, `ProrationCalculatorTest` |
| İptal | Stripe-only cancel | `POST /{tenantId}/cancel` (S12; dönem sonuna dek erişim) | **Tam** | `SubscriptionStateMachineIT` |
| Yenileme (dönem uzatma) | Webhook `invoice.paid` (guard'sız, çift uzatma K2) | Ödeme onayı → ACTIVE + period advance; webhook **idempotent** (`webhook_events` UQ) | **Tam+** | `BillingWebhookIT`, `PayTRWebhookIT`, `IyzicoWebhookIT`, `BillingConfirmationConcurrencyIT` |
| Otomatik kart-tahsilatlı recurring | Stripe subscription mode | **Deferred** — PayTR/iyzico entegrasyonları tekrar-checkout modeli; Stripe sağlayıcısı uykuda (kod var, canlı değil) | **Deferred** | Operatör kendi merchant'ıyla Stripe'ı açarsa SPI hazır (`StripeBillingProvider`) |
| Süre dolumu: grace → expire → downgrade | Worker (trial-grace asimetrisi K9, lock yok K10) | `SubscriptionLifecycleJob` (ShedLock) + processor; trial'da grace YOK (asimetri düzeltildi) | **Tam+** | `SubscriptionLifecycleIT`, `ShedLockIT` |
| Tarihsel kayıt | Yok (yalnız audit-log genel izi) | `subscription_events` domain izi + **UI: detay sheet'te yaşam döngüsü zaman çizelgesi** | **Tam+** | `subscription-detail-sheet.test.tsx`; her geçiş from/to/reason/actor ile |

## 4. Tenant tarafı SaaS görünümü

| Kalem | ASP.NET Zero | zero-spring | Durum | Kanıt |
|---|---|---|---|---|
| Kendi aboneliğini görme | subscription-management (tenant) | `GET /api/subscriptions/me` + dashboard Finans sekmesi widget'ı (plan, durum rozeti, dönem bitişi) | **Tam** | `SubscriptionGuardIT`, dashboard suite (host'ta çağrılmaz negatifi) |
| Abonelik geçersizse erişim kapısı | Tenant deaktive edilir | `SubscriptionGuard`: EXPIRED/PENDING_PAYMENT → 403 `SUBSCRIPTION_INVALID`; muaf yol listesi startup'ta doğrulanır | **Tam+** | `SubscriptionGuardIT`, `SubscriptionExemptPathBindingIT`, `SubscriptionExemptPathsStartupCheckTest` |

## 5. Host tarafı SaaS yönetimi

| Kalem | ASP.NET Zero | zero-spring | Durum | Kanıt |
|---|---|---|---|---|
| Tenantlar-arası abonelik listesi + aksiyonlar | subscription-management | `subscriptions-list` (server-paged grid; assign / pay&assign / activate / cancel / features / **detay+geçmiş**) | **Tam** | `subscriptions-list.test.tsx` + `SaasAuthorizationIT` |
| Dashboard görünürlüğü | Host dashboard (gelir/istatistik) | Dashboard Finans sekmesi: abonelik özeti widget'ı (host) | **Kısmi** | Gelir grafikleri/istatistik yok — `payments` tablosu ilişkisel olduğundan eklenebilir; şablon kapsamında ertelendi |
| İzinler | `Pages.Editions/Tenants/...` (K12: GetTenantCount izinsizdi) | `SaasPermissions` (Side.HOST) — her uç `@PreAuthorize` + `<Can>` + route guard | **Tam+** | `SaasAuthorizationIT`, `SaasPermissionsAlignmentTest` (izinsiz uç kalmadı) |

## 6. Notification / audit entegrasyonu

| Kalem | ASP.NET Zero | zero-spring | Durum | Kanıt |
|---|---|---|---|---|
| Yaklaşan süre dolumu uyarısı | `SubscriptionExpireEmailNotifierWorker` (**tam-gün eşitliği**: koşu kaçarsa uyarı kaybolur) | Lifecycle job'da **pencere taraması** (`expiry-notice-days`, varsayılan 7) + `EXPIRY_NOTICE` event-ledger idempotency — geç koşu yine uyarır, saatlik koşu çift uyarmaz | **Tam+** | `SubscriptionExpiryNoticeIT` (pencere dışı 0 · içi 1 · tekrar koşu yine 1) |
| SaaS olayları → kullanıcıya bildirim | E-posta (worker'lar) | `SubscriptionChanged` (saas::api) → identity `SubscriptionNotificationBridge` → tenant **Admin** üyelerine in-app bildirim (activated/cancelled/period-ended/expired/downgraded/expiring-soon; i18n en+tr) | **Tam** | `SaasNotificationBridgeIT` (aktivasyon+iptal bildirir; **provisioning bildirmez** negatifi) |
| Domain izi | Yok | `subscription_events` (from/to/reason/actor/zaman) + entity-history | **Tam+** | `SubscriptionLifecycleIT` event assert'leri |

## 7. UI paritesi (16 Angular ekranına karşı)

| Kaynak ekran | zero-spring karşılığı | Durum |
|---|---|---|
| admin/editions (liste+form+features) | `editions-list` + `edition-form` + `feature-values-editor` | **Tam** |
| admin/editions move-tenants | — | **Deferred** (bkz. §1) |
| admin/tenants + features modal | `tenants` feature + `TenantFeaturesPanel` | **Tam** |
| admin/subscription-management (+detail) | `subscriptions-list` + **detay sheet (geçmişle)** | **Tam** |
| account/payment (gateway seçim, pre/post/cancel) | `CheckoutDialog` (sağlayıcı seçimi) + `payment-result` rotaları ("activated asla istemciden denmez", ADR-0014) | **Tam+** |
| Invoice ekranı | — | **Deferred** — kaynakta fatura üretimi manuel + race'liydi (K5/K6); `invoices` şeması hazır, üretim/PDF sonraki faz |

## Bilinçli farklar (kusur taşınmadı)

K1 istemci-bağımlı aktivasyon → server-authoritative (webhook+reconciliation) · K2 idempotency yok →
`webhook_events` UQ + duplicate'te 200 · K3 `Customer.Description` eşleştirme → metadata/ref · K5-K6
fatura race/FK'sızlık → ilişkisel şema (üretim deferred) · K7 30/365 gün → takvim `Period` · K8
implicit durum → explicit enum · K9 trial-grace asimetrisi → tutarlı (grace yok) · K10 lock'suz
worker → ShedLock · K11 sessiz no-op → `DomainException` · K12 izinsiz uç → tam üçlü kilit ·
K13 düzenlenemez edition → snapshot fiyat · exact-day uyarı kaybı → pencere+ledger.

## Redis duruşu

SaaS cache'leri Redis'liyken hızlanır; **Redis yokken degrade no-op** (feature çözümleme ve guard
DB'den doğru cevabı verir, ekran kırılmaz). Bildirim köprüsü ve lifecycle job Redis kullanmaz.
(Auth'un revocation fail-closed Redis bağımlılığı SaaS-dışı, bilinçli ayrı karar — PROD-R13.)
