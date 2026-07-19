# A) SaaS Source Inventory — ASP.NET Zero (F5 girdisi)

Kaynak: `Asp.NET Zero/aspnet-core/src` (kısaltma: `<src>`). 4 paralel tarama ajanının bulguları + doğrudan doğrulamalar.
Amaç: F5 (SaaS ticari katman) için **gerçek** davranışı çıkarmak — dokümantasyon değil, kodun kendisi.

---

## 1. Veri modeli (gerçek şema)

### 1.1 `AbpEditions` — TPH (tek tablo)
| Tip | Discriminator | Alanlar |
|---|---|---|
| `Edition` (ABP base) | `"Edition"` | Id, Name(32, req), DisplayName(64, req), audit + soft-delete |
| `SubscribableEdition` | `"SubscribableEdition"` | `MonthlyPrice` decimal(18,2)?, `AnnualPrice` decimal(18,2)?, `TrialDayCount` int?, `WaitingDayAfterExpire` int?, `ExpiringEditionId` int? |

`IsFree` = `[NotMapped]` computed → `!MonthlyPrice.HasValue && !AnnualPrice.HasValue`.
`GetPlanId()` = `Name_PeriodType_Currency` — **gateway plan kimliği domain'den türetiliyor** (`<src>/…Core/Editions/SubscribableEdition.cs:61`).
Varsayılan edition: `EditionManager.DefaultEditionName = "Standard"`.

### 1.2 `AbpFeatures` — TPH (feature *değerleri*)
| Tip | Discriminator | Ek |
|---|---|---|
| `FeatureSetting` | `"FeatureSetting"` | Id(bigint), Name(128 req), **Value nvarchar(2000) req**, TenantId int? |
| `EditionFeatureSetting` | `"EditionFeatureSetting"` | `EditionId` NOT NULL, FK → AbpEditions **CASCADE**, index(EditionId, Name) |
| `TenantFeatureSetting` | `"TenantFeatureSetting"` | base `TenantId` kullanır, index(TenantId, Name) |

**Feature değerleri string olarak saklanır**; tip dönüşümü çağrı yerinde (`.To<int>()`).

### 1.3 `AbpTenants`
`EditionId` int? (FK → AbpEditions, **cascade YOK**, indexli), `SubscriptionEndDateUtc` datetime2? (**indexli**, `null` = süresiz),
`IsInTrialPeriod` bool, `SubscriptionPaymentType` int (enum), `IsActive` bool, `ConnectionString`, logo/CSS alanları, audit + soft-delete.

### 1.4 `AppSubscriptionPayments`
**Kritik:** 2024 migration'ı (`20240105114130_Common_Payment_System`) `Amount`, `EditionId`, `EditionPaymentType` kolonlarını **DROP** etti; `Description` → `ExtraProperties` (JSON) RENAME.
Kalan alanlar: `Gateway`, `Status` (protected set), `TenantId` int, `DayCount`, `PaymentPeriodType?`, `ExternalPaymentId`, `InvoiceNo` (string, FK yok), `SuccessUrl`/`ErrorUrl`, `IsRecurring?`, `IsProrationPayment`, `ExtraProperties` (JSON), `SubscriptionPaymentProducts` (tutar burada).
Index: `(ExternalPaymentId, Gateway)`, `(Status, CreationTime)`.
→ **Tutar ve edition artık ilişkisel değil; JSON/child tabloda.**

### 1.5 `AppInvoices`
`InvoiceNo` (string), `InvoiceDate`, `TenantLegalName`, `TenantAddress`, `TenantTaxNo`. **Yok:** TenantId, tutar, ödemeye FK, audit alanları, `InvoiceNo` üzerinde unique index.
Ödeme ↔ fatura bağı yalnız **string eşleşme** (`payment.InvoiceNo == invoice.InvoiceNo`).

---

## 2. Enum'lar (semantik kritik)

| Enum | Değerler | Not |
|---|---|---|
| `PaymentPeriodType` | `Monthly = 30`, `Annual = 365` | **Değerin kendisi gün sayısı**; `(int)` cast ile tarih/fiyat hesabında kullanılıyor |
| `SubscriptionStartType` | Free=1, Trial=2, Paid=3 | Paid → tenant pasif başlar |
| `SubscriptionPaymentType` | Manual=0, RecurringAutomatic=1, RecurringManual=2 | Tenant'ın yenileme modu |
| `EditionPaymentType` | NewRegistration=0, BuyNow=1, Upgrade=2, Extend=3 | Tarih güncelleme switch'ini sürer |
| `SubscriptionPaymentStatus` | NotPaid=1, Paid=2, Failed=3, Cancelled=4, **Completed=5** | `Paid` = gateway onayladı; `Completed` = tenant güncellendi |
| `SubscriptionPaymentGatewayType` | Paypal=1, Stripe=2 | |
| `EndSubscriptionResult` | TenantSetInActive, AssignedToAnotherEdition | Worker e-posta seçimi |

---

## 3. Subscription state machine (implicit)

**Durum ayrı bir kolonda tutulmuyor**; `IsActive` + `SubscriptionEndDateUtc` + `IsInTrialPeriod` + `EditionId` kombinasyonundan türetiliyor.

| Durum | IsActive | EndDateUtc | InTrial |
|---|---|---|---|
| Unlimited/Free | true | `null` | false |
| Trial | true | `> now` | true |
| Active paid | true | `> now` | false |
| Grace (waiting) | true | `end ≤ now < end + WaitingDayAfterExpire` | false |
| Downgraded | true | **`null`** (sıfırlanır) | false |
| Deactivated | false | geçmiş | false |
| Pending payment | false | `null` | false |

**Geçiş kuralları (özet):**
- `NewRegistration`/`BuyNow` → `end = now + (int)period`
- `Extend` → süresi geçmişse önce `end = now`, sonra `+ period`
- `Upgrade` → **tarihe dokunmaz** (proration kalan süreyi zaten fiyatlar); yalnız süresizse set eder
- Expire → `WaitingDayAfterExpire` grace → `ExpiringEditionId` varsa **free'ye düşür** (`end = null`), yoksa **deaktive et**
- **Free edition trial alamaz** (`CheckEditionAsync`), **`ExpiringEditionId` hedefi free olmak zorunda**
- **Trial'ın grace'i yok** (domain), ama worker ön-filtresi trial'a da grace uyguluyor → **asimetri/bug**
- Trial biten tenant asla downgrade edilmez, **daima deaktive**
- Deaktive tenant `EditionId`'yi korur

**Proration formülü** (`TenantManager.GetUpgradePrice`):
```
unusedPeriodCount = (remainingHours/24) / (int)period
unusedHours       = remainingHours % ((int)period * 24)
priceForUnused(P) = P*unusedPeriodCount + (P/(int)period)/24*unusedHours
upgradePrice      = target.priceForUnused − current.priceForUnused
```
Minimum eşik `MinimumUpgradePaymentAmount = 1M`; altındaysa **ödeme atlanır, edition anında değişir**.

---

## 4. Feature sistemi

**Sabitler** (`AppFeatures`): `App.MaxUserCount`, `App.ChatFeature` (+`.TenantToTenant`, `.TenantToHost`), `App.TestCheckFeature`, `App.TestCheckFeature2` (son ikisi template demo, `#region Example Features` içinde).

**Tanım metadata'sı** (`FeatureMetadata`): `ValueTextNormalizer` (0 → "Unlimited"), `IsVisibleOnPricingTable`, `TextHtmlColor`.

**Çözümleme:** `FeatureValueStore` ABP base'i **override etmiyor** → zincir: `TenantFeatureSetting` → `EditionFeatureSetting` (tenant.EditionId) → `Feature.DefaultValue`. Cache ABP `ICacheManager`.

**Kontrol:** `[RequiresFeature]` attribute'u **hiç kullanılmıyor** (grep 0). Tümü imperatif:
- `UserPolicy.CheckMaxUserCountAsync` → `IFeatureChecker.GetValueAsync(...).To<int>()`, aşımda `UserFriendlyException`
- `ChatFeatureChecker` → `IsEnabled(...)` üçlü kontrol

**Yazma yetkileri:** edition tarafı `EditionAppService.SetFeatureValues`; tenant override `TenantAppService.{GetTenantFeaturesForEdit, UpdateTenantFeatures, ResetTenantSpecificFeatures}` — hepsi `Pages.Tenants.ChangeFeatures` (**host-only**).

---

## 5. Payment abstraction (gerçekte ne var)

**`IPaymentGatewayManager` diye bir arayüz YOK.** Gateway'ler ortak sözleşme paylaşmıyor:

| Bileşen | Rol |
|---|---|
| `IPaymentManager` | Persistence/domain manager (CreatePayment, GetPayment, UpdatePayment) — gateway değil |
| `IPaymentGatewayConfiguration` | `IsActive`, `SupportsRecurringPayments`, `GatewayType` (`ITransientDependency`) |
| `PaymentGatewayStore` | `ResolveAll<IPaymentGatewayConfiguration>()` + IsActive filtresi — **tek genişletme noktası** |
| `ISupportsRecurringPayments` | 4 event handler (Disabled/Enabled/Updated/Cancelled) |
| `IPaymentUrlGenerator` | Core'da implementasyonu yok |

`StripeGatewayManager` ve `PayPalGatewayManager` **birbirinden bağımsız somut sınıflar**; `CancelSubscription` yalnız Stripe'ta var. Yeni gateway = 7 katmanda elle iş.

### 5.1 Stripe
- **Legacy `Plan` API** (Prices değil): `PlanService`, `ProductService`, `SessionService`, `SubscriptionService`, `CustomerService`
- Plan upsert: `GetOrCreatePlanAsync` try/catch `StripeException`
- **Tenant eşleştirme 3 farklı mekanizma:** checkout → `session.Metadata["PaymentId"]`; recurring invoice → **`Customer.Description = TenancyName`**; session → `ExtraProperties["StripeSessionId"]`
- `Customer.Description` çözümlemesinde **null kontrolü yok** → eşleşmeyen customer'da NRE
- Recurring: `Mode="subscription"` + `LineItems.Price = plan.Id`; tek seferlik: `Mode="payment"` + inline `PriceData`

### 5.2 PayPal
- **Yalnız Orders v2 Capture** (55 satır); başka API yok
- **Recurring YOK** (`SupportsRecurringPayments => false`)
- **IPN/webhook YOK** → onay tamamen tarayıcı üzerinden; kullanıcı tarayıcıyı kapatırsa **capture hiç çağrılmaz**, telafi işi yok
- `DemoUsername`/`DemoPassword` istemciye DTO ile gönderiliyor

---

## 6. Webhook handling

Yalnız Stripe (`StripeControllerBase`). İmza: `EventUtility.ConstructEvent(json, "Stripe-Signature", WebhookSecret, throwOnApiVersionMismatch: false)`.

**İşlenen:** `invoice.paid` (yalnız `BillingReason == "subscription_cycle"`), `checkout.session.completed`.
**İşlenmeyen:** `customer.subscription.deleted`, `invoice.payment_failed`, `checkout.session.expired`, `charge.refunded`, `charge.dispute.created` → Stripe'ta iptal/başarısız yenileme uygulamaya **hiç yansımıyor**.

### İdempotency: YOK — ve retry döngüsü üretiyor
- İşlenmiş `event.Id` kaydı **yok** (tablo/cache yok)
- Tek savunma durum guard'ı: `Status != NotPaid` → `ApplicationException` → controller `catch` → **`BadRequest()` (400)**
- Stripe 400'ü başarısızlık sayar → **aynı event sonsuza dek yeniden gönderilir**. Duplicate teslimatta doğru davranış `200 OK`'tir.
- **Recurring yolunda hiç guard yok:** `invoice.paid` → `RecurringPaymentSucceedEventData` → `UpdateTenantAsync`. Aynı event iki kez gelirse **abonelik iki kez uzar**.
- Recurring yenilemede `SubscriptionPayment` kaydı **oluşturulmuyor** → ödeme geçmişi yenilemeleri göstermez
- Dead-letter / manuel replay yok

---

## 7. Invoice / accounting

- Numara üretimi: `yyyyMM` + 5 hane; son faturayı `OrderByDescending(Id)` ile bulup substring parse → +1
- **Race condition:** kilit/sequence/retry yok, `InvoiceNo` unique index yok, üstelik çağıran metot `[UnitOfWork(IsolationLevel.ReadUncommitted)]`
- Format bozuk tek kayıt tüm üretimi kalıcı patlatır (`Substring(0,4)`/`(4,2)`)
- **Otomatik değil:** `CreateInvoice` yalnız UI'dan (`Index.js`) tetikleniyor; sunucuda başka çağıran yok
- Guard: `payment.Status != Completed` → hata; `InvoiceNo` doluysa tekrar üretim engelli
- **PDF yok** — yalnız yazdırılabilir Razor view
- Fatura bilgileri host/tenant **ayarlarından** (`BillingLegalName`, `BillingAddress`, `BillingTaxVatNo`)

---

## 8. Uçtan uca ödeme akışı (upgrade) ve en kritik kırılganlık

1. `SubscriptionAppService.StartUpgradeSubscription` → free→free ise ödeme yok
2. Proration hesabı → `CreateUpgradeSubscriptionPayment` → `PaymentManager.CreatePayment` (Status=`NotPaid`)
3. `/Stripe/PrePayment?paymentId=` → checkout session (`Metadata["PaymentId"]`)
4. Ödeme sonrası **iki bağımsız dönüş yolu**: (a) webhook `checkout.session.completed`, (b) tarayıcı `/Stripe/PostPayment` — (b) ayrıca `Customer.Description`'ı yazan **tek** yer
5. `ConfirmPayment` → `SetAsPaid()` → **`Paid`**
6. `TenantRegistrationAppService.UpgradeSucceed(paymentId)` → `SetAsCompleted()` → **`Completed`** → `UpdateTenantAsync` → **edition burada değişir**

> **En kritik kırılganlık:** `Paid → Completed` geçişi ve **asıl edition değişimi istemci çağrısıyla** yapılıyor; webhook yalnız `Paid`'e kadar getiriyor. Kullanıcı ödeme sonrası tarayıcıyı kapatırsa: ödeme `Paid`'de kalır, **edition hiç yükselmez**, fatura kesilemez (`Completed` şartı). Telafi eden arka plan işi yok — `NotCompletedYesterdayPaymentSpecification` yalnız `NotPaid` kayıtları hedefler.

---

## 9. Servis yüzeyi ve izinler

**AppService'ler (10):** `EditionAppService`, `TenantAppService`, `TenantRegistrationAppService`, `SubscriptionAppService`, `PaymentAppService`, `StripePaymentAppService`, `PayPalPaymentAppService`, `InvoiceAppService`, `HostDashboardAppService`.

**İzinler (hepsi `MultiTenancySides.Host`):**
`Pages.Editions` (+`.Create`, `.Edit`, `.Delete`, `.MoveTenantsToAnotherEdition`), `Pages.Tenants` (+`.Create`, `.Edit`, `.ChangeFeatures`, `.Delete`, `.Impersonation`), `Pages.Administration.Tenant.SubscriptionManagement`.

**Angular ekranları (16):** `admin/editions` (liste + create/edit + move-tenants), `admin/tenants` (liste + create/edit + **features modal**), `admin/subscription-management` (+ invoice + detail), `account/payment` (gateway-selection, stripe pre/post/cancel, paypal pre).

---

## 10. Worker'lar

| Worker | Periyot | İş |
|---|---|---|
| `SubscriptionExpirationCheckWorker` | 1 saat | Süresi dolanları deaktive/downgrade |
| `SubscriptionExpireEmailNotifierWorker` | 1 gün | N gün kala uyarı (default 7); **tam gün eşitliği** ile arar → kaçırılırsa tekrar denenmez |
| `SubscriptionPaymentNotCompletedEmailNotifierWorker` | 1 gün | Dün başlatılıp ödenmemiş (`NotPaid`) ödemelere hatırlatma |

`RunOnStart = true`, **distributed lock yok** → çok-instance'ta aynı tenant birden çok node'da işlenebilir.

---

## 11. Tespit edilen kusurlar (F5'te taşınmayacak / düzeltilecek)

| # | Kusur | Kanıt |
|---|---|---|
| K1 | **Aktivasyon istemciye bağlı** — `Paid`'de takılan ödeme edition'ı yükseltmez, telafi yok | §8 |
| K2 | **Webhook idempotency yok**; duplicate'te 400 → sonsuz retry; recurring yolunda hiç guard yok (çift uzatma) | §6 |
| K3 | `Customer.Description` ile tenant çözümleme (serbest metin, dashboard'dan değiştirilebilir) + null kontrolü yok (NRE) | §5.1 |
| K4 | PayPal'de webhook/IPN yok → tarayıcı kapanırsa ödeme kaybı | §5.2 |
| K5 | Invoice numarası race condition (ReadUncommitted, unique index yok, substring parse) | §7 |
| K6 | Invoice'ta TenantId/tutar/FK/audit yok; fatura üretimi manuel | §7 |
| K7 | `PaymentPeriodType` enum değeri = gün (30/365) → 30 gün ≠ 1 ay, takvim hataları | §2 |
| K8 | Subscription durumu implicit (3 alan kombinasyonu) → ambiguous durumlar | §3 |
| K9 | Trial grace asimetrisi (worker vs domain) | §3 |
| K10 | Worker'larda distributed lock yok | §10 |
| K11 | `SetAsFailed` guard'sız; diğer guard'lar sessiz no-op (çağıran sonucu bilemez) | §1.4 |
| K12 | `EditionAppService.GetTenantCount` **izinsiz** (tenant sayısı sızıntısı); `PaymentAppService.GetPaymentAsync`/`PaymentFailed` tenant/sahiplik kontrolsüz | §9 |
| K13 | Edition update yalnız DisplayName + feature (fiyat/trial/waiting/expiring değiştirilemez) → fiyat değişimi için "yeni edition + tenant taşıma" zorunlu | §1.1 |
| K14 | Silinen edition başka edition'ın `ExpiringEditionId`'si olabilir (dangling kontrolü yok) | §1.1 |
| K15 | `Stripe` legacy Plans API; `throwOnApiVersionMismatch: false` | §5.1, §6 |
| K16 | Web.Mvc `appsettings.json`'da gerçek görünümlü gateway secret'ları → **rotasyon gerekir** | §5 |
