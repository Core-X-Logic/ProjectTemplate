# D) ETL Impact Note — F5 kararlarının F6 (veri migration) üzerindeki etkisi

Amaç: F6 **başlamadan**, F5 tasarımının veri taşımayı bozmamasını garanti etmek. F5 kapsamında yalnız
*tasarıma yansıtılacaklar* uygulanır; gerçek ETL kodu F6'dadır (scope-lock).

---

## 1. Kaynak → hedef eşleme riskleri

| # | Kaynak yapı | Hedef | Risk | Sınıf |
|---|---|---|---|---|
| E1 | `AbpEditions` **TPH** (`Discriminator`) | `editions` (tek tip) | Discriminator filtresi atlanırsa base `Edition` satırları da taşınır | Düşük |
| E2 | `AbpFeatures` TPH → `EditionFeatureSetting` / `TenantFeatureSetting` | `edition_features` / `tenant_features` | İki alt tip aynı tabloda; ayrım Discriminator + `EditionId` null'luğu ile. Yanlış ayrım → tenant override'ların edition'a yazılması (**yetki yükseltme etkisi**) | **Yüksek** |
| E3 | Feature değerleri **string** (`nvarchar(2000)`) | `value varchar(2000)` + `FeatureDefinition.type` | Tip bilgisi kaynakta yok; hedefte definition'dan gelir. Kaynakta tanımsız feature adları olabilir (silinmiş provider) | Orta |
| E4 | `AbpTenants.{EditionId, SubscriptionEndDateUtc, IsInTrialPeriod, SubscriptionPaymentType, IsActive}` | `subscriptions` satırı (**explicit status**) | **Durum türetme**: implicit kombinasyondan `status` çıkarılmalı. Ambiguous vakalar var (bkz. §2) | **Yüksek** |
| E5 | `AppSubscriptionPayments` — `Amount`/`EditionId` kolonları **DROP** edilmiş | `payments` (ilişkisel tutar/edition) | Tutar `SubscriptionPaymentProducts.TotalAmount`'tan, edition `ExtraProperties` **JSON**'undan çıkarılmalı; JSON şeması sürüm sürüm değişmiş olabilir | **Yüksek** |
| E6 | `AppInvoices` — TenantId/tutar/FK yok | `invoices` (FK'li, tutarlı) | Fatura ↔ ödeme bağı yalnız `InvoiceNo` **string** eşleşme; eşleşmeyen/yetim faturalar | Orta |
| E7 | `PaymentPeriodType` = 30/365 **gün** | `BillingPeriod{MONTHLY, ANNUAL}` | Mevcut `SubscriptionEndDateUtc` 30 günlük mantıkla hesaplanmış; hedefte ay bazlı. **Yeniden hesaplama yapılırsa müşteri süresi kayar** | **Yüksek** |
| E8 | Stripe `Customer.Description = TenancyName` | `metadata.tenantId` | Gateway tarafındaki mevcut müşterilerde metadata yok; cutover'da **Stripe üzerinde de** güncelleme gerekir | **Yüksek** |
| E9 | `ExternalPaymentId` (PaymentIntentId **veya** SubscriptionId) | `external_payment_id` + `provider` | Tek kolonda iki farklı anlam; recurring/tek-seferlik ayrımı `IsRecurring`'den türetilmeli | Orta |
| E10 | Recurring yenilemelerde **payment kaydı yok** | `payments` geçmişi | Geçmiş yenilemeler kaynakta hiç yok → hedefte de boş kalacak (gelir raporu geçmişi eksik) | Bilgi |
| E11 | `SubscriptionPaymentStatus.Completed` (5) | `payments.status` | Kaynakta `Paid`'de takılı kayıtlar olabilir (K1) → hedefte `PENDING`/manuel inceleme kuyruğu | Orta |
| E12 | Silinmiş (soft-delete) edition/tenant | — | `IsDeleted=1` satırlar taşınmamalı; ama aktif tenant silinmiş edition'a bağlı olabilir (FK cascade yok) → **yetim EditionId** | Orta |

---

## 2. Durum türetme kuralı (E4) — F6 için bağlayıcı karar tablosu

Kaynakta durum implicit; hedefte explicit. F6 ETL bu tabloyu **aynen** uygulayacak:

| IsActive | EndDateUtc | IsInTrial | EditionId | → `subscriptions.status` | Not |
|---|---|---|---|---|---|
| true | null | false | free/any | `ACTIVE` | süresiz (`current_period_end_at = null`) |
| true | `> now` | true | ücretli | `TRIALING` | `trial_end_at = EndDateUtc` |
| true | `> now` | false | any | `ACTIVE` | `current_period_end_at = EndDateUtc` |
| true | `≤ now` | false | grace içinde | `GRACE` | `grace_end_at = EndDateUtc + edition.WaitingDayAfterExpire` |
| true | `≤ now` | false | grace dışı | `EXPIRED` | worker henüz çalışmamış kayıtlar |
| true | `≤ now` | **true** | any | `EXPIRED` | **kaynaktaki asimetri** (worker trial'a grace veriyordu) → hedefte trial grace almaz |
| false | herhangi | any | any | `EXPIRED` | deaktive; `EditionId` korunur |
| false | null | false | null | `PENDING_PAYMENT` | ödeme bekleyen kayıt (paid registration) |

**Ambiguous kalanlar** (manuel inceleme kuyruğu): `IsActive=false` + `EndDateUtc > now` (host elle kapatmış olabilir);
`EditionId = null` + `EndDateUtc` dolu.

---

## 3. Şimdi (F5) tasarıma yansıtılacaklar

| # | Karar | Neden şimdi |
|---|---|---|
| P1 | `subscriptions.legacy_edition_id` + `legacy_tenant_payment_ref` kolonları | Taşınan kayıtların kaynağını izlemek; doğrulama raporu bu kolonlarla eşleştirir |
| P2 | `payments.legacy_payment_id` + `invoices.legacy_invoice_no` (Slice C şemasında) | Aynı gerekçe; string `InvoiceNo` bağı (E6) hedefte FK'ye çevrilirken denetlenebilir |
| P3 | `subscriptions.external_ref` + `provider` **Slice A'da** (ödeme Slice C'de olsa bile) | E8/E9: gateway eşleştirmesi migration'da yazılacak; sonradan kolon eklemek migration + downtime maliyeti |
| P4 | `current_period_end_at` **doğrudan taşınır**, yeniden hesaplanmaz | E7: 30-gün → ay dönüşümünde müşteri süresi kaymasın (sözleşmesel risk) |
| P5 | `billing_period` **nullable** | Kaynakta `PaymentPeriodType` null olabilen kayıtlar var (free/süresiz) |
| P6 | Feature değerleri **string** saklanır (tip definition'da) | E3: kaynak formatı birebir taşınır, dönüşüm kaybı olmaz |
| P7 | `edition_features` / `tenant_features` **ayrı tablolar** (TPH değil) | E2: kaynaktaki TPH ayrım hatası riskini yapısal olarak ortadan kaldırır |
| P8 | `webhook_events` UQ(provider, event_id) (Slice C) | Cutover sırasında gateway'in geçmiş event'leri yeniden göndermesi ihtimaline karşı |
| P9 | `subscription_events` tablosu | Taşıma sonrası "bu abonelik nereden geldi" izi (`reason = 'ETL_IMPORT'`) |
| P10 | Para alanları `numeric(19,4)` + zorunlu `currency` | Kaynakta `decimal(18,2)` ve currency ayarda; hedefte kayıt başına currency taşınır |

---

## 4. F6'ya devredilenler (F5'te YAPILMAZ)

- Gerçek ETL kodu/scripti, doğrulama raporu, cutover runbook'u (bkz. `ANALYSIS.md` §4.3)
- Stripe tarafında **mevcut müşterilere `metadata.tenantId` yazma** işi (E8) — cutover adımı
- `Paid`'de takılı ödemelerin (E11) manuel mutabakatı
- Silinmiş edition'a bağlı yetim tenant'ların (E12) temizliği
- Geçmiş recurring yenilemelerin (E10) yeniden inşası — **mümkün değil**, kabul edilen veri kaybı

---

## 5. Risk register'a eklenecekler

| ID | Risk | Seviye | Sahip faz |
|---|---|---|---|
| F6-R1 | Durum türetme (E4) yanlış → müşteri erişimi haksız kesilir/açılır | **Yüksek** | F6 (kural F5'te sabitlendi) |
| F6-R2 | 30-gün → ay dönüşümünde abonelik süresi kayması (E7) | **Yüksek** | F5 kararı P4 ile azaltıldı |
| F6-R3 | JSON `ExtraProperties`'ten tutar/edition çıkarma (E5) | **Yüksek** | F6 |
| F6-R4 | Feature TPH ayrım hatası → tenant override'ın edition'a yazılması (E2) | **Yüksek** | F5 kararı P7 ile azaltıldı |
| F6-R5 | Gateway metadata migration'ı (E8) unutulursa recurring webhook'lar tenant çözemez | **Yüksek** | F6 cutover |
