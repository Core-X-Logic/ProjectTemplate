# D) F5 Contract — SaaS ticari katman (Slice 1-3)

Bağlayıcı sözleşme. Mimari: `F5-ARCHITECTURE.md`. Kapsam kilidi: F5 scope-lock (SaaS 8 modül; chat/realtime,
tam ETL, mobil, public site, files/S3, yeni stack **yasak**).
Mevcut hat korunur: backend 53 test / frontend 68 test yeşil, Modulith sınırları, RBAC double-lock, typed client.

---

## Slice F5-A — Editions + Features + Package assignment + Subscription model + Admin UI

**Kapsam (kullanıcı tanımı):** Editions CRUD · Feature definitions + feature values · Tenant package assignment ·
Subscription status model · SaaS admin React ekranları · permission + i18n + tests.
**Kapsam DIŞI (bu slice):** gerçek ödeme gateway'i, proration hesabı, yaşam döngüsü job'ları, fatura, webhook,
tenant self-registration, feature enforcement AOP.

### A.1 Backend

**Modül:** `com.mycompanyname.zero.saas`, `package-info` → `allowedDependencies = {"shared", "tenancy", "settings"}`;
`saas/api` → `@NamedInterface("api")`. `identity/package-info`'ya `"saas :: api"` **eklenmez** (Slice B'de gerekecek).

**Migration `V4__saas.sql`** — `editions`, `edition_features`, `tenant_features`, `subscriptions`, `subscription_events`
(şema: `F5-ARCHITECTURE.md` §2; `payments`/`invoices`/`webhook_events` **Slice C'de**).

**Entity/servis:**
- `edition/Edition` (+`EditionFeature`), `EditionRepository`, `EditionService`
- `feature/FeatureDefinition` + `FeatureDefinitions` registry (min. tanımlar: `app.maxUserCount` NUMBER default `0`,
  `app.auditLog` BOOLEAN default `true`, `app.organizationUnits` BOOLEAN default `true`),
  `TenantFeature`, `FeatureValueResolver` (tenant → edition → default; **cache Slice B**)
- `subscription/Subscription` (agregat), `SubscriptionStatus` enum, `SubscriptionService` (durum geçiş metotları,
  geçersiz geçiş → `DomainException(VALIDATION)`), `SubscriptionEvent` kaydı
- `api/FeatureChecker` implementasyonu (okuma; enforcement Slice B)
- `SaasPermissions` (saas içinde) + permission ağacına kayıt (`config`/`seed` üzerinden, `Side.HOST`)
- Event listener: `TenantCreated` → varsayılan abonelik (tek transaction)
- Seed: varsayılan `Standard` edition + `default` tenant aboneliği — **ayrı idempotent adım** (edition varlığına bakar)

**Endpoint'ler:**
| Method | Path | İzin |
|---|---|---|
| GET | `/api/editions` (page) | `editions.read` |
| GET | `/api/editions/{id}` (+feature değerleri) | `editions.read` |
| POST | `/api/editions` | `editions.manage` |
| PUT | `/api/editions/{id}` (fiyat dahil **düzenlenebilir**) | `editions.manage` |
| DELETE | `/api/editions/{id}` | `editions.manage` |
| GET | `/api/features/definitions` | `editions.read` |
| PUT | `/api/editions/{id}/features` (batch) | `editions.manage` |
| GET | `/api/subscriptions` (tenant listesi + edition/durum) | `subscriptions.read` |
| GET | `/api/subscriptions/{tenantId}` | `subscriptions.read` |
| PUT | `/api/subscriptions/{tenantId}/edition` (paket atama) | `subscriptions.manage` |
| POST | `/api/subscriptions/{tenantId}/cancel` / `/activate` | `subscriptions.manage` |
| GET | `/api/tenant-features/{tenantId}` · PUT (batch) | `tenantfeatures.manage` |
| GET | `/api/subscriptions/me` | authenticated (tenant kendi aboneliği, salt okuma) |

**İş kuralları (zorunlu):**
1. Edition silme: **abonelik varsa 409**; ayrıca başka edition'ın `expiring_edition_id`'si ise **409** (K14 çözümü).
2. `expiring_edition_id` hedefi **free olmalı** (aksi 400).
3. Free edition'a **trial atanamaz** (aksi 400).
4. Paket atama: abonelikte fiyat **snapshot**lanır (`price_amount`, `price_currency`, `billing_period`).
5. Tenant kendi feature'ını/edition'ını **değiştiremez** (tüm yazma uçları `Side.HOST`).
6. `app.maxUserCount` değeri `0` = sınırsız (kaynak semantiği korunur).

### A.2 Frontend (React)

`features/editions/` ve `features/subscriptions/` — mevcut şablon (api/hooks/types/messages/pages/components/__tests__).
- **Editions:** liste (data-grid: name, displayName, monthly/annual price, trial, grace, active), create/edit formu
  (rhf+zod), **feature değerleri editörü** (definition tipine göre input: boolean switch / number / text), delete (409 mesajı).
- **Subscriptions:** tenant listesi (tenant, edition, status badge, dönem sonu), paket atama dialogu (edition seçimi +
  period + trial), cancel/activate aksiyonları, tenant-feature override paneli.
- Menü: `Saas` grubu → Editions, Subscriptions (`Side.HOST` izinleriyle `anyPermission`).
- i18n `en`/`tr` birebir; loading/empty/error state zorunlu; typed client `gen:api` ile yeniden üretilir.

### A.3 Kabul kriterleri (acceptance)
- [ ] Host admin edition oluşturur/düzenler/siler; kullanımdaki edition silinmeye çalışılınca **409 + anlaşılır mesaj**
- [ ] Edition'a feature değeri atanır; tenant override edilebilir; **çözümleme tenant → edition → default** doğru
- [ ] Tenant'a paket atanır; abonelik `ACTIVE`/`TRIALING` olur; fiyat snapshot'lanır
- [ ] Geçersiz durum geçişi (`EXPIRED → TRIALING` gibi) **reddedilir** (400), `subscription_events` kaydı oluşur
- [ ] Tenant kullanıcısı `/api/editions` (yazma) ve `/api/subscriptions/{id}` uçlarına **403** alır; `/api/subscriptions/me` ile yalnız kendi aboneliğini görür
- [ ] React ekranları çalışır: liste/form/atama; permission guard + i18n en/tr; boş/yükleniyor/hata durumları
- [ ] `ModularityTests` yeşil (`saas` sınırları, döngü yok)

### A.4 Test kriterleri
**Backend IT (min. 5):**
- `saas/EditionCrudIT` — CRUD + kullanımdaki edition silme 409 + expiring-edition free kuralı 400
- `saas/FeatureResolutionIT` — tenant override > edition > default zinciri; bilinmeyen feature 400
- `saas/SubscriptionAssignmentIT` — paket atama, fiyat snapshot, `TenantCreated` → otomatik abonelik
- `saas/SubscriptionStateMachineIT` — geçerli/geçersiz geçişler, `subscription_events` kaydı
- `saas/SaasAuthorizationIT` — tenant kullanıcısı tüm SaaS yazma uçlarında 403; `subscriptions/me` yalnız kendi tenant'ı
  (mevcut `TenantEscalationIT` deseni)

**Frontend (min. 4):** editions-list (render + RBAC), edition-form (feature editörü + submit), subscriptions-list
(status badge + atama dialogu), tenant-features (override kaydetme).

**Quality gate:** `mvnw verify` yeşil (≥58 test), `npm run build` + `npm run test` yeşil (≥72 test), typed client senkron,
açık kritik/yüksek güvenlik 0.

---

## Slice F5-B — Lifecycle + Feature enforcement + Proration + Tenant self-service

**Kapsam:** yaşam döngüsü job'ları (trial bitişi, expire, grace, downgrade) `@Scheduled` + **ShedLock**;
`@RequiresFeature` AOP + Redis feature cache (+evict); proration hesabı ve edition upgrade/downgrade;
abonelik geçerlilik kapısı (`SubscriptionGuard` → `TenantResolverFilter`); tenant self-service abonelik ekranı
(kendi paketi, kalan süre, upgrade talebi — ödeme Slice C); `identity` → `saas :: api` bağımlılığı (max user count).

**Kabul:** süresi biten abonelik `GRACE`→`EXPIRED`→(varsa)downgrade otomatik; çok-instance'ta job **tek** çalışır;
`@RequiresFeature` kapalı feature'da 403; `app.maxUserCount` aşımında kullanıcı oluşturma reddedilir;
`EXPIRED` tenant iş uçlarında 403 alır, abonelik ekranına erişebilir; cache invalidation kanıtlı.
**Test:** `SubscriptionLifecycleIT` (zaman ileri alma), `ShedLockIT` (iki tetikleme tek çalışma), `FeatureEnforcementIT`,
`MaxUserCountIT`, `ProrationCalculationTest` (birim, kaynak formülü + ay-sonu clamp), `SubscriptionGuardIT`; FE: subscription-me sayfası + upgrade akışı testleri.

### Slice B quality gate — **canlı smoke ZORUNLU** (yeni)

Slice A'da öğrenildi: Testcontainers her koşuda **temiz DB** kullandığı için "mevcut kurulum" hataları
testlerde görünmez (F5-R9: yeni izinler statik Admin rollerine eklenmiyordu → host admin 17/22, `/api/editions`
403; suite yine de yeşildi — **false-green**). Bu yüzden Slice B, aşağıdaki kapı geçilmeden **kapanmaz**:

1. **Mevcut (migrate edilmiş) veritabanı** üzerinde backend `dev` profiliyle ayağa kaldırılır — temiz DB değil,
   önceki slice'ın verisini taşıyan gerçek şema.
2. Slice B'nin **her kabul kriteri** canlı HTTP çağrılarıyla doğrulanır; en az:
   - süresi geçmiş abonelik → job sonrası `GRACE`/`EXPIRED`/downgrade (durum canlı okunur)
   - `@RequiresFeature` kapalı feature → **403**; `app.maxUserCount` aşımı → kullanıcı oluşturma reddi
   - `EXPIRED` tenant → iş ucunda **403**, abonelik ekranında erişim
   - feature değeri değişiminden sonra **cache stale değil** (yeni değer okunur)
3. Bu adımlar **PASS/FAIL** olarak raporlanır ve `governance/QUALITY-GATES-RESULTS.md`'ye kanıtla yazılır.
4. Smoke sırasında bulunan her hata için: düzeltme + **pozitif test** (boşluğu gerçekten kanıtlayan) eklenir.

**Kural:** "verify yeşil" tek başına done kanıtı değildir; canlı smoke geçmeden Slice B modülleri
parity matrisinde kapanmaz.

---

### ⛔ FEATURE FREEZE — 2026-07-18 (Slice B verify sonrası)

Slice B backend verify yeşil (**133 test**) olduğu anda **feature freeze** ilan edildi.
Bu noktadan sonra **yalnız** şunlar üzerinde çalışılır:
- `RISK-REGISTER.md` → "F5-B Production Readiness — P0" tablosundaki **PROD-R1..R16**
- **F5-R9 prod fix** (izin uzlaştırmasının prod'da fiilen çalışması)

**Dondurulan (bu fazda YAPILMAYACAK):**
- Slice B frontend kalemleri: tenant self-service abonelik sayfası (`/subscriptions/me` ekranı), upgrade akışı UI
  → backend `SubscriptionGuard` + `/api/subscriptions/me` hazır; ekran bir sonraki fazda
- Slice C'nin tamamı (Stripe, webhook, invoice, tenant self-registration)
- `current_period_start_at` kalıcılığı (changeEdition rekonstrüksiyon nüansı — Slice B ajanı notu)

**Kanıt kuralı:** her düzeltme için **test + (uygulanabilirse) canlı smoke + risk durumu + gate sonucu** zorunlu.
**GO kuralı:** tüm P0'lar kapanmadan ve **canlı smoke geçmeden GO verilmez.**

## Slice F5-C — Billing provider (Stripe) + Webhook + Invoice

**Kapsam:** `payments`, `invoices`, `webhook_events` tabloları; `StripeBillingProvider` (**Prices API**,
`metadata.tenantId/subscriptionId`); checkout akışı; **webhook idempotency** (§5 stratejisi); reconciliation job;
otomatik fatura üretimi (DB sequence ile numara, unique index); manuel ödeme onayı (`ManualBillingProvider`);
billing admin UI (ödeme listesi, fatura, webhook DEAD replay).

**Kabul:** ödeme tamamlanınca abonelik **yalnız sunucu tarafından** aktive olur (istemci çağrısı durum değiştirmez);
aynı webhook event'i iki kez gelince **tek** etki + **200** dönülür; kalıcı hatada retry döngüsü **oluşmaz**;
fatura numarası eşzamanlı üretimde **çakışmaz**; iptal/başarısız yenileme event'leri işlenir.
**Test:** `WebhookIdempotencyIT` (aynı event ×2 → tek etki, 200), `WebhookSignatureIT`, `PaymentReconciliationIT`
(webhook kaybında job tamamlar), `InvoiceNumberConcurrencyIT` (paralel üretim, unique), `StripeProviderTest` (WireMock);
FE: billing ekranları + davranış testleri.

---

## Ortak kurallar (tüm slice'lar)

- **Kanıtsız done yok:** her modül backend IT + frontend davranış testi + parity satırı dolmadan kapanmaz.
- **Canlı smoke zorunlu (tüm slice'lar):** temiz-DB testleri "mevcut kurulum" hatalarını göremez (F5-R9).
  Her slice, mevcut/migrate edilmiş DB üzerinde çalışan backend'e karşı HTTP smoke ile doğrulanır ve sonuç
  `QUALITY-GATES-RESULTS.md`'ye PASS/FAIL olarak yazılır.
- Modulith: `saas` çekirdeği `identity`'ye bağlanmaz; `tenancy`'ye `saas` bağımlılığı eklenmez (event).
- Para: `BigDecimal` + `numeric(19,4)` + currency; tarih: `java.time`, UTC.
- Güvenlik: tüm SaaS yazma uçları `Side.HOST`; tenant escalation negatif testi zorunlu.
- Secret yalnız env; gateway anahtarları repoya girmez.
- Her slice sonunda: checkpoint (5 başlık) + governance güncellemesi (parity, quality-gates, risk, changelog).
