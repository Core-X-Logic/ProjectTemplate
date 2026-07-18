# F5 Slice A Final Report — SaaS ticari katman (2026-07-18)

## A) Source inventory
`F5-SAAS-INVENTORY.md` — 4 paralel taramanın sentezi. Öne çıkanlar: `AbpEditions`/`AbpFeatures` TPH şeması;
`AppSubscriptionPayments`'ta `Amount`/`EditionId` kolonlarının **DROP edilip JSON'a taşınmış** olması;
`PaymentPeriodType` enum değerinin **gün sayısı** olması (30/365); implicit state machine (13 geçiş);
`IPaymentGatewayManager` diye bir arayüzün **gerçekte olmaması**; webhook idempotency yokluğu; invoice numarası
race condition'ı; **16 kusur (K1-K16)**.

## B) Gap analysis
`F5-GAP-ANALYSIS.md` — 20 gap. zero-spring'de SaaS **sıfırdan** kuruldu (`edition|subscription|billing|feature`
kaynakta hiç yoktu); üzerine inşa edilen hazır altyapı: Modulith sınırları, tenant izolasyonu, permission ağacı,
settings registry deseni, typed client, test hattı. Kaynaktan **taşınmayan** kararlar tablosu (istemci-tetikli
aktivasyon, 400-retry webhook, `Customer.Description` eşleştirme, 30/365 gün, implicit durum, manuel fatura).

## C) Architecture addendum
`F5-ARCHITECTURE.md` — `saas` modülü + `saas :: api` named interface (Modulith döngü yasağı), V4 şeması,
explicit state machine, `BillingProvider` SPI, webhook idempotency stratejisi, feature gating, provisioning akışı,
host-only izinler. Kararlar ADR-0009..0015 olarak kayda geçti.

## D) F5 contract
`CONTRACT-phase5.md` — Slice A (editions/features/subscriptions/admin UI), Slice B (lifecycle + enforcement +
proration), Slice C (Stripe + webhook + invoice); her slice için kabul ve test kriterleri.
ETL etkisi: `F5-ETL-IMPACT.md` (12 risk + F6 için bağlayıcı durum türetme tablosu + 10 tasarım kararı).

## E) First slice implementation report (Slice A)

| Katman | Çıktı |
|---|---|
| Migration | `V4__saas.sql`: editions, edition_features, tenant_features, subscriptions, subscription_events (+ `legacy_*`, `external_ref`, `provider` ETL kolonları) |
| Modül | `saas` (`allowedDependencies = {shared, tenancy, settings}`), `saas :: api` (`FeatureChecker`, `SubscriptionGuard`, `SaasFeatures`) |
| Domain | Edition/EditionFeature, FeatureDefinitions registry + TenantFeature + FeatureValueResolver, Subscription + SubscriptionStatus + SubscriptionEvent |
| API | 12 uç (editions CRUD + features batch, feature definitions, tenant-features, subscriptions liste/detay/atama/activate/cancel, `/me`) |
| Yetki | 5 izin `Side.HOST` + `SaasPermissionsAlignmentTest` (drift'i build'de kırar) |
| Provisioning | `TenantCreatedEvent` → otomatik abonelik (tek transaction, `tenancy → saas` bağımlılığı yok) |
| Seed | Ayrı idempotent SaaS seed + **statik rol izin uzlaştırması** |
| Frontend | `features/editions` (liste/form/feature editörü), `features/subscriptions` (liste/atama dialogu/tenant-feature paneli), routing+menü+i18n en/tr |

## F) Quality gates

| Kapı | Sonuç |
|---|---|
| Backend `mvnw verify` (Lead) | **89 test** (86 IT + 3 unit), 0 fail |
| `ModularityTests` | geçti (saas sınırları, döngü yok) |
| Frontend build (Lead) | built 7.56s, 3037 modül |
| Frontend test (Lead) | **90/90** (19 dosya) |
| Typed client | 42 → **53 path** |
| **Uçtan uca canlı smoke** | **10/10 PASS** |
| Açık kritik/yüksek güvenlik | **0** |
| Modül kapanışı (5 sütun) | Editions, Features, Package assignment, Subscription status, SaaS yetkilendirme → **Closed** |

**Smoke kanıtı:** host 22 izin · edition create (49.90 USD) · feature ata (25) · paket atama
**TRIALING + snapshot 49.9000 USD/MONTHLY** · free+trial **400** · kullanımdaki edition silme **409** ·
tenant override 25→**50** · tenant izinleri **14** · tenant edition create **403** · tenant feature yükseltme
**403** · `/me` yalnız kendi aboneliği.

## G) Risk register delta
- **Kapandı:** F5-R1 (Modulith döngüsü — `saas :: api` + ModularityTests), F5-R3 (tenant kendi limitini
  yükseltemez — canlı 403), F5-R6 (seeder idempotency — ayrı SaaS seed adımı), **F5-R9** (izin uzlaştırma).
- **Yeni/açık:** F5-R2 (feature cache — Slice B), F5-R5 (ay-sonu clamp — Slice B), F5-R7 (abonelik kapısı —
  Slice B), F5-R8 (kaynak kusurlarının kopyalanması — Slice C'de webhook/billing ile test edilecek).
- **Tasarım borcu (ADR-0015):** SaaS'ta `@Filter` güvenlik ağı yok; her yeni uç için negatif yetki testi
  zorunlu, ArchUnit kuralı Slice B adayı.
- **Süreç öğrenimi:** temiz-DB testleri mevcut-kurulum hatalarını göremez → **her faz için canlı smoke zorunlu**.
- **Kapsam dışı bulgu → ticket açıldı:** `POST /api/tenants` ile açılan tenant'lara `Admin` rolü/kullanıcısı
  oluşturulmuyor (Faz 1'den beri); tenant giriş yapılamaz halde kalıyor ve izin uzlaştırması onu atlıyor.
  Kaynak sistemde `TenantManager.CreateWithAdminUserAsync` bunu tek akışta yapıyordu.
  **[Issue #1 — Tenant creation does not bootstrap an Admin role or admin user](https://github.com/Core-X-Logic/ProjectTemplate/issues/1)**
  (F5 kapsamı dışı; Slice C tenant self-registration'ın ön koşulu).

---

F5 SAAS EXECUTION: **GO** — Slice A beş modülde 5 sütun tam, backend 89 + frontend 90 test, uçtan uca canlı
smoke 10/10, açık kritik/yüksek güvenlik 0; Slice B/C sözleşmesi hazır.

---

## H) Slice B + Production Hardening kapanışı — 2026-07-18 (bu rapora ek)

Slice A'nın **süreç öğrenimi** ("temiz-DB testleri mevcut-kurulum hatalarını göremez") bu turda
sözleşmeye bir quality gate olarak girdi ve **karşılığını verdi**: canlı smoke, 326 yeşil testin
görmediği iki kusur buldu — `/actuator/prometheus`'un sıfır izinli tenant kullanıcısına açık olması
(PROD-R17) ve `/api/users`'ın bellekte sayfalaması (PROD-R21).

**Ölçümler (Lead tarafından elle koşuldu):**

| Kalem | Sonuç |
|---|---|
| `mvnw clean verify` | **326 test** (236 IT + 90 unit), 0 fail/error/skip, `BUILD SUCCESS`, 0 `[ERROR]` |
| Canlı smoke (mevcut migrate edilmiş DB) | **14/14 PASS**, backend log'unda **0 ERROR** satırı |
| Load smoke | 420 istek / 8 worker → p95 **39.7 ms**, hata oranı **%0.00** |
| Migration dry-run | 6 migration validate, drift yok; V4/V5/V6 **dolu** tablolar üzerine uygulanmış |
| Açık kritik/yüksek **sömürülebilir** güvenlik | **0** |

**Tur sayısı ve şiddet eğrisi (dürüst kayıt).** Sertleştirme 8 tur sürdü ve **her tur yeni bulgu
çıkardı** — yani "bulgu yok" bir eşik olarak hiç gerçekleşmedi. Anlamlı olan şiddetin yönü:
turlar 1-4 sömürülebilir yetki/rate-limit bypass'larıydı (B1-B3, C4, D1); turlar 5-7
availability/log bütünlüğü (C3, D3, E1); kapanış turu ise **deployment katmanında mitigasyonu olan**
maddeler (F1, PROD-R17..R20). Karar bu yüzden "sıfır bulgu"ya değil, şu üç kritere bağlandı:
açık **Kritik/Yüksek + sömürülebilir + mitigasyonsuz** bulgu yok, canlı smoke geçti, verify yeşil.

**Tekrar eden hata deseni ve karşılığı.** Dört kez, raporlanan *yazımı* düzeltmek bir sonraki
varyantı açık bıraktı (415 → wildcard → `application/yaml` → sort'un üçüncü şekli). Her seferinde
doğru cevap sınıfı kapatmaktı: medya tipleri uygulamanın kendi converter'larından **türetildi**,
HTTP durumu istisnanın kendisinden (`ErrorResponse`) **soruldu**, log bütçesi isim listesi yerine
**özellik** olarak ifade edildi. Aynı desen yönetişimde de çıktı — register "PROD-R12 gate CI'ya
eklendi" diyordu, gate yoktu; iddia doğrulanmadan kapalı yazılmıştı.

**Issue #1 durumu:** hâlâ **açık** ve freeze kapsamı dışı. Tenant self-registration'ın ön koşulu
olduğu için Slice C'nin ilk maddesi olarak kalıyor.

---

PRODUCTION READINESS FOR F5-B: **GO** — koşullu; deployment ön koşulları §1.3-I/J ve artık risk
tablosu (PROD-R6, R16, R21 + frontend yeniden doğrulaması) kabul edilmek kaydıyla.
