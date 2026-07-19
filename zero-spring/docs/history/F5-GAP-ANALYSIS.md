# B) F5 Gap Analysis — ASP.NET Zero SaaS → zero-spring

Girdi: `F5-SAAS-INVENTORY.md` (kaynak) + zero-spring mevcut durum taraması (Faz 1-2 çıktısı).

## 0. Tek cümlelik özet

zero-spring'de **SaaS ticari katmanı sıfırdan kurulacak**: `edition|subscription|billing|plan|feature|trial|invoice`
kelimelerinin uygulama kaynağında **hiç isabeti yok**; `Tenant` yalnız `name`/`displayName`/`active` taşıyor.
Buna karşılık üzerine inşa edilecek altyapı (Modulith sınırları, tenant izolasyonu, permission ağacı, settings
registry deseni, typed client, test hattı) **hazır ve kanıtlı**.

---

## 1. Modül/varlık bazlı gap tablosu

| # | Yetenek | ASP.NET Zero (kaynak) | zero-spring (mevcut) | Gap | F5 Slice |
|---|---|---|---|---|---|
| G1 | Edition/paket entity | `AbpEditions` TPH + `SubscribableEdition` (fiyat/trial/waiting/expiring) | **YOK** | Tam yeni `editions` tablosu + CRUD | A |
| G2 | Feature tanımı | `AppFeatures` sabitleri + `AppFeatureProvider` (metadata: normalizer, pricing-table, renk) | **YOK** (`Feature*` identifier'ı bile yok) | `FeatureDefinitions` registry (settings deseni kopyası) | A |
| G3 | Feature değeri (edition) | `EditionFeatureSetting` (string value, cascade FK) | **YOK** | `edition_features` tablosu | A |
| G4 | Feature değeri (tenant override) | `TenantFeatureSetting` | **YOK** | `tenant_features` tablosu | A |
| G5 | Feature çözümleme | ABP `FeatureValueStore`: tenant → edition → default (cache'li) | **YOK** | `FeatureValueResolver` + Redis cache + evict | A |
| G6 | Feature enforcement | İmperatif `IFeatureChecker` (`[RequiresFeature]` kullanılmıyor) | **YOK** | `@RequiresFeature` AOP + programatik `FeatureChecker` (**iyileştirme**) | B |
| G7 | Tenant↔edition ilişkisi | `AbpTenants.EditionId` (nullable FK) | **YOK** | `subscriptions.edition_id` (Tenant'a kolon eklemek yerine ayrı agregat) | A |
| G8 | Abonelik durumu | **Implicit** (IsActive+EndDate+InTrial kombinasyonu) | **YOK** | **Explicit `subscriptions.status`** (**iyileştirme**) | A |
| G9 | Abonelik yaşam döngüsü | 3 worker (expire/notify/payment-reminder), distributed lock yok | **YOK** (Quartz/Scheduler de yok) | `@Scheduled` + **ShedLock** (**iyileştirme**) | B |
| G10 | Trial | `TrialDayCount`, free edition trial alamaz, grace yok (asimetri bug) | **YOK** | Trial durum + tutarlı grace kuralı | A(model)/B(job) |
| G11 | Proration/upgrade | `GetUpgradePrice` formülü + min eşik 1M | **YOK** | Proration servisi (formül korunur, `BillingPeriod`'a uyarlanır) | B |
| G12 | Payment kaydı | `AppSubscriptionPayments` (Amount/EditionId **DROP** edilmiş, JSON'da) | **YOK** | `payments` tablosu — **tutar/edition ilişkisel** (**iyileştirme**) | B |
| G13 | Payment gateway soyutlaması | **Ortak arayüz YOK** (Stripe/PayPal bağımsız sınıflar) | **YOK** | **`BillingProvider` SPI** (**en büyük iyileştirme**) | A(SPI)/C(Stripe) |
| G14 | Webhook | Yalnız Stripe; **idempotency yok**, duplicate'te 400→sonsuz retry | **YOK** | `webhook_events` + INSERT-ON-CONFLICT idempotency + **her zaman 200** (**iyileştirme**) | C |
| G15 | Invoice | TenantId/tutar/FK/audit yok, numara race, manuel üretim, PDF yok | **YOK** | `invoices` (FK'li, tutarlı, DB sequence, **otomatik**) | C |
| G16 | Tenant self-registration | Free/Trial/Paid + pasif başlatma + captcha | **YOK** (host-only tenant create var) | Kapsam dışı bırakılabilir → **C (opsiyonel)** | C |
| G17 | SaaS izinleri | 11 host-only izin (Editions.*, Tenants.*, SubscriptionManagement) | **YOK** (17 izin, hiçbiri ticari) | Yeni izin ağacı düğümleri | A |
| G18 | SaaS admin UI | 16 Angular ekranı | **YOK** | React: editions, tenant-subscription, (C: billing/invoice) | A/C |
| G19 | Gateway plan senkronu | `GetPlanId()` = `Name_Period_Currency`, upsert | **YOK** | Provider adapter'da (external plan ref) | C |
| G20 | Host dashboard SaaS metrikleri | Expiring tenants, edition dağılımı | **YOK** | Kapsam dışı (F5 sonrası) | — |

---

## 2. Üzerine inşa edilecek hazır altyapı (yeniden yazılmayacak)

| Altyapı | Durum | F5'te kullanımı |
|---|---|---|
| Modulith sınırları + `ModularityTests` | ✅ 9 modül, named interface deseni (`notification :: email`) | `saas` modülü + `saas :: api` |
| Tenant çözümleme + izolasyon | ✅ `TenantResolverFilter` (aktif tenant kapısı), `AuthenticatedTenantFilter` (JWT claim ↔ header), Hibernate `@Filter` | Abonelik geçerliliği kapısı **aynı noktaya** eklenecek |
| Permission ağacı | ✅ `AppPermissions` + `PermissionDefinitions` (Side.HOST/TENANT/BOTH), 4 adımlı ekleme deseni | SaaS izinleri `Side.HOST` |
| Settings registry | ✅ `SettingDefinitions` (statik liste, scope zinciri, `visibleToClient`) | **Desen kopyalanacak**, tablo paylaşılmayacak |
| Seeder | ✅ idempotent, host-only izinleri tenant admin'den otomatik dışlar | Yeni izinler otomatik; edition seed'i **ayrı idempotent adım** gerekir |
| Flyway | ✅ V1-V3, konvansiyon net (`identity` PK, `timestamptz`, `nulls not distinct`) | **V4** SaaS şeması |
| Typed client | ✅ `gen:api` → `schema.d.ts` (42 path) | SaaS DTO'ları otomatik türetilecek |
| Frontend feature şablonu | ✅ api/hooks/types/messages/pages/components/__tests__ | `features/editions`, `features/subscription` |
| RBAC (double-lock) | ✅ `RequireAuth` + `<Can>` + `@PreAuthorize` | SaaS ekranlarında aynı |
| Test hattı | ✅ backend 53 test/17 IT (Testcontainers), frontend 68 test | SaaS IT'leri + davranış testleri |
| Audit + entity history | ✅ HTTP audit + `@TrackChanges` tip listesi | Edition/subscription değişimleri **izlenmeli** |

---

## 3. Kaynak sistemden **taşınmayacak** kararlar

| Kaynak davranışı | Karar | Gerekçe |
|---|---|---|
| İstemci-tetikli aktivasyon (`UpgradeSucceed`) | **Taşınmaz** → server-authoritative | K1: ödeme alınıp edition yükselmeme riski |
| Duplicate webhook'ta 400 dönme | **Taşınmaz** → idempotent + 200 | K2: sonsuz retry döngüsü |
| `Customer.Description` ile tenant çözümleme | **Taşınmaz** → provider metadata `tenantId` + `subscriptionId` | K3: kırılgan, elle değiştirilebilir |
| `PaymentPeriodType = 30/365 gün` | **Taşınmaz** → `BillingPeriod{MONTHLY, ANNUAL}` + `java.time.Period` | K7: 30 gün ≠ 1 ay |
| Implicit durum (3 alan kombinasyonu) | **Taşınmaz** → explicit `status` kolonu + domain geçiş metotları | K8: ambiguous durumlar |
| Edition update kısıtı (fiyat değiştirilemez) | **Taşınmaz** → edition düzenlenebilir, **abonelik fiyatı snapshot'lanır** | K13: daha iyi; mevcut aboneler etkilenmez |
| Manuel fatura üretimi | **Taşınmaz** → ödeme tamamlanınca otomatik | K6 |
| Sessiz no-op durum guard'ları | **Taşınmaz** → geçiş ihlali `DomainException` | K11 |
| PayPal (recurring/webhook yok) | **Kapsam dışı** | K4; F5'te tek gerçek gateway Stripe (Slice C) |
| Tenant-başına ayrı DB, TPH discriminator | **Taşınmaz** | Mevcut mimari kararlar (ADR-0002/0003) |

---

## 4. Yeni riskler (kaynakta olmayan, F5'e özgü)

| ID | Risk | Seviye | Mitigasyon |
|---|---|---|---|
| F5-R1 | Modulith döngüsü: feature gating için `identity → saas`, izin sabitleri için `saas → identity` | **Yüksek** | `saas :: api` named interface; SaaS izin sabitleri `saas` içinde; `tenancy`'ye asla `saas` bağımlılığı (event) |
| F5-R2 | Feature cache tutarsızlığı (edition/tenant değişince stale) | Orta | Redis cache + yazma yollarında explicit evict + IT ile kanıt |
| F5-R3 | Tenant kendi feature/limitini yükseltebilme | **Yüksek** | Tüm SaaS yazma uçları `Side.HOST`; tenant yalnız okuma; `TenantEscalationIT` deseninde negatif test |
| F5-R4 | Para/tutar hassasiyeti (double kullanımı) | Orta | `BigDecimal` + `numeric(19,4)`; para birimi kolonu zorunlu |
| F5-R5 | Zaman dilimi/ay sonu kaymaları (31 Ocak + 1 ay) | Orta | `java.time` + `Period`, UTC saklama, ay-sonu clamp kuralı testli |
| F5-R6 | Seeder idempotency tuzağı (host admin varsa seed atlanır) | Orta | Edition seed'i **ayrı** idempotent adım (varlık kontrolü edition üzerinden) |
| F5-R7 | Abonelik geçerliliği kapısının yanlış yere konması (her istekte DB) | Orta | `TenantResolverFilter`'da cache'li kontrol; sadece tenant-scoped isteklerde |
