# Quality Gates — Sonuç Tablosu

Kanıta dayalı; her `mvnw verify` koşusundan sonra güncellenir. Eşikler: `QUALITY-GATES.md`.

## Faz 1 — 2026-07-17

| Kapı | Eşik | Sonuç | Durum |
|---|---|---|---|
| `mvnw compile` | BUILD SUCCESS | BUILD SUCCESS | ✅ |
| `mvnw verify` (surefire+failsafe) | tüm test yeşil | **15/15 yeşil** (1+14), ~22s | ✅ |
| `ApplicationModules.verify()` | ihlal yok | geçti | ✅ |
| Tenant izolasyon testi | pozitif+negatif | TenantIsolationIT 4/4 + TenantEscalationIT 3/3 | ✅ |
| Auth akış testi | happy+authz-negatif | AuthFlowIT 6/6 (refresh reuse kaskadı dahil) | ✅ |
| Açık kritik/yüksek güvenlik bulgusu | 0 | 0 (re-review temiz) | ✅ |
| JaCoCo coverage | ≥%75 satır | *ölçülmedi (F1 sözleşme dışı); F2'de JaCoCo eklendi* | ⏳ |

**Faz 1 Quality Gate: GEÇTİ.**

## Faz 2 backend — codegen turu 1 (2026-07-18)

| Kapı | Eşik | Sonuç | Durum |
|---|---|---|---|
| `mvnw compile` | BUILD SUCCESS | BUILD SUCCESS | ✅ |
| `mvnw verify` | tüm test yeşil | **42 failsafe + ModularityTests, 0 fail** (~48s) | ✅ |
| Modül IT kanıtı | her modül ≥1 IT | 15 IT sınıfı (identity/audit/settings/localization/permission/tenancy) | ✅ |
| `ApplicationModules.verify()` | yeni modül sınırları | geçti (audit/settings/localization/notification allowedDeps={shared}) | ✅ |
| JaCoCo | rapor (haltOnFailure=false) | üretildi | ✅ |
| **Parity bütünlüğü** | boşluk yok | ❌ **8+ parity gap** (aşağıda) | ❌ |
| **Açık kritik/yüksek güvenlik** | 0 (GO şartı) | ❌ 2 açık (changePassword policy bypass, password-history inert) | ❌ |

> ⚠️ **KRİTİK GOVERNANCE NOTU:** verify yeşil ama **testler boşlukları test etmediği için yeşil** —
> false-green (R-19). Adversaryal inceleme şu gerçek boşlukları buldu: change-password şifre politikasını
> atlıyor (`aaaaaaaa` kabul); `PasswordHistoryService` hiç çağrılmıyor (history inert); OU entity-history
> bozuk (yanlış FQN); welcome/confirmation e-posta gönderilmiyor; `shouldChangePassword` flag `MeDto`'da yok;
> leaf permission i18n çözülmüyor; policy/lockout/email setting'lerden okunmuyor; AuditLogIT flaky riski.
> **Bu boşluklar kapanana kadar hiçbir Faz 2 modülü "Done" DEĞİL** (kanıtsız done yok kuralı işledi).

## Faz 2 backend — parity/güvenlik düzeltme turu (w2lbc8ut9) — 2026-07-18 ✅

| Kapı | Eşik | Sonuç | Durum |
|---|---|---|---|
| 11 boşluk düzeltme + pozitif parity testleri | her boşluk test edilir | 11/11 kapalı, her biri geçen testle kanıtlı | ✅ |
| `mvnw verify` (yeni testlerle) | tüm test yeşil | **50 test (1 surefire + 49 failsafe, 16 IT), 0 fail** | ✅ |
| Yeni parity testleri | pozitif kanıt | PasswordPolicyIT(4, +history reuse red), EntityHistoryIT(2, +OU), EmailDispatchIT(2, GreenMail welcome/confirm), MeShouldChangePasswordIT(1), PermissionTreeIT(3, +leaf i18n) | ✅ |
| Açık kritik/yüksek güvenlik | 0 (GO şartı) | **0** (re-review still_open=[], security_open=[]) | ✅ |
| `ApplicationModules.verify()` | ihlal yok | geçti (yeni `settings` bağımlılığı named, döngü yok) | ✅ |
| **Lead bağımsız doğrulama** | verify yeşil | **BUILD SUCCESS, 49 failsafe + 1 surefire, 0 fail** (elle koşuldu, bpvso7mqm) | ✅ |

Kapatılan bulgular: change-password policy+history (R-20 → **Closed**), password-history wiring, OU entity-history,
welcome/confirmation email, shouldChangePassword flag, leaf i18n, setting-tabanlı policy/lockout/email,
AuditLogIT flaky (default DESC sort). **False-green (R-19) → Closed:** artık her boşluk pozitif testle kanıtlı.

## Faz 2 frontend — slice A (w130yhbgk) — 2026-07-18 ✅ (Lead tarafından elle doğrulandı)

| Kapı | Eşik | Sonuç | Durum |
|---|---|---|---|
| `npm run build` (tsc -b + vite) | BUILD SUCCESS | **built in 4.38s**, dist üretildi (2025 modül, index+css+js) | ✅ |
| TS strict | hata yok | `tsc -b` exit 0 | ✅ |
| Vitest davranış testi | yeşil | **10/10 PASS** (login.test 3 + rbac.test 7), vitest v3.2.7, 1.67s | ✅ |
| Tekilleştirme (R-16) | ölü lib yok | formik/react-query@3/windicss/notistack/helmet-nonasync eklenmedi | ✅ |
| Kalite notu | — | vendor `config.metronic.css` Tailwind4-uyumsuz `@media (max-width: var(...))` uyarısı (kozmetik, R-21) | ⚠️ |

**Slice A kapsamı (Done, evidenced):** app iskeleti, vendor altyapı taşıma (shadcn/ui + admin shell +
styles), auth/tenant/i18n provider zinciri, typed `api/client` (401 refresh singleflight), RBAC guard
(`usePermission`/`<Can>`/`RequireAuth`), login sayfası (rhf+zod), i18n en/tr, admin layout + menü.
**Slice A KAPSAMINDA DEĞİL:** users/roles/OU/notifications feature ekranları (placeholder) → slice B.

## Faz 2 — Contract gate (typed client) — 2026-07-18 ✅

| Kapı | Eşik | Sonuç | Durum |
|---|---|---|---|
| Backend ayağa kalkış (dev, 5433/6380) | `/v3/api-docs` 200 | UP (28.7 KB OpenAPI) | ✅ |
| `npm run gen:api` (openapi-typescript) | hatasız typed client | `src/api/schema.d.ts` üretildi, **42 path** | ✅ |

## Faz 2 — Notifications backend (w66msuafd) — 2026-07-18 ✅

| Kapı | Eşik | Sonuç | Durum |
|---|---|---|---|
| `mvnw verify` | tüm test yeşil | **51 test** (1 surefire + 50 failsafe), 0 fail | ✅ |
| NotificationInboxIT | sahiplik izolasyonu | welcome→inbox→unread→mark-read→foreign 403 | ✅ |
| Modulith döngü | notification→identity yok | ModularityTests yeşil (identity→notification izinli yön) | ✅ |
| Açık bulgu | kritik/yüksek 0 | 0 (R-22 mvnw CRLF = altyapı, R-23 read-all testsiz = düşük) | ✅ |

## Faz 2 backend — user search (wb53z9m56) — 2026-07-18 ✅

| Kapı | Eşik | Sonuç | Durum |
|---|---|---|---|
| `mvnw verify` | tüm test yeşil | **52 test** (1 surefire + 51 failsafe), 0 fail | ✅ |
| GET /api/users search paritesi | tenant-scoped, izolasyon | UserManagementIT search testi (case-insensitive + host sızmıyor) | ✅ |

## Faz 2 frontend — slice B (w0s7u3xxn) + polish (wg0dk6w1r devam) — 2026-07-18

| Kapı | Eşik | Sonuç | Durum |
|---|---|---|---|
| `npm run build` (slice B) | BUILD SUCCESS | built (2062 modül, dist) | ✅ |
| Vitest (slice B) | yeşil | **28 test** (7 dosya: slice A 10 + 4 feature 18) | ✅ |
| Güvenlik incelemesi | kritik/yüksek 0 | 0 (token/tenant tek nokta, perm birebir, double-lock) | ✅ |
| Fonksiyonel gap'ler | kapalı | #1-#5 **hepsi kapalı** (polish wg0dk6w1r) | ✅ |

## Faz 2 frontend — slice B polish + Lead doğrulama + smoke — 2026-07-18 ✅

| Kapı | Eşik | Sonuç | Durum |
|---|---|---|---|
| Gap düzeltme (#1-#5) | tümü kapalı | users-search backend + menü-404 + form-`<Can>` + exportExcel apiFetch + ölü-Intl kaldır | ✅ |
| `npm run build` (Lead) | BUILD SUCCESS | **built 5.09s**, dist (regen'li typed client) | ✅ |
| Vitest (Lead) | yeşil | **44/44 PASS** (9 dosya; +16 yeni: dropdown `<Can>`, require-auth, role-form) | ✅ |
| `gen:api` (regen) | search param | 42 path, `search` param mevcut | ✅ |
| **Uçtan uca smoke** (canlı backend) | akış + izolasyon | **9/9 PASS**: login→me(14 izin)→users list+search→roles→permission-tree→OU→notifications→**tenant-escalation 403** | ✅ |

**İlk vertical slice quality-gate: GEÇTİ.** Backend 52 test + frontend 44 test + uçtan uca smoke, 0 kritik/yüksek.

## Faz 2 Slice C — Impersonation + Audit + Settings — 2026-07-18 ✅ (Lead doğruladı)

| Kapı | Eşik | Sonuç | Durum |
|---|---|---|---|
| Backend verify | yeşil | **53 test** (+SettingDto.defaultValue SettingsIT), 0 fail | ✅ |
| Frontend build | BUILD SUCCESS | built (3021 modül, dist) | ✅ |
| Frontend test (Lead) | yeşil | **68/68 PASS** (14 dosya; +24 slice C: impersonation 7, audit 9, settings 5, require-auth any-perm) | ✅ |
| Typed client senkron | gen:api | 42 path + SettingDto.defaultValue mevcut | ✅ |
| Güvenlik | kritik/yüksek 0 | 0 (token swap tokenStore, act-decode güvenli, cascade UI+backend, host double-lock, visible-to-client OK) | ✅ |
| **Uçtan uca smoke** (canlı backend) | 3 modül akış | audit(9) + entity-changes(5) + settings-defaultValue(6) + impersonate→authenticate→**cascade 403**→back-to-impersonator | ✅ |
| İnceleme minörleri (#1-#4) | kapalı | defaultValue backend + settings any-perm + impersonate a11y + i18n temizlik | ✅ |
| Modül kapama (5 sütun) | tam | Impersonation/Audit/Settings **Closed** | ✅ |

**Slice C quality-gate: GEÇTİ.** Backend 53 + frontend 68 + uçtan uca smoke; 0 kritik/yüksek; 3 modül kapandı.

## F5 Slice A — SaaS (Editions + Features + Subscriptions) — 2026-07-18 ✅ (Lead doğruladı)

| Kapı | Eşik | Sonuç | Durum |
|---|---|---|---|
| Backend verify | yeşil | **89 test** (86 IT + 3 unit), 0 fail — Lead elle koştu | ✅ |
| `ModularityTests` | `saas` sınırları, döngü yok | geçti (`saas :: api` named interface) | ✅ |
| Frontend build | BUILD SUCCESS | built 7.56s (3037 modül) | ✅ |
| Frontend test (Lead) | yeşil | **90/90 PASS** (19 dosya; 68→90) | ✅ |
| Typed client senkron | `gen:api` | 42 → **53 path**, tüm SaaS uçları | ✅ |
| **Uçtan uca smoke** (canlı backend) | iş kuralları + güvenlik | **10/10 PASS** (aşağıda) | ✅ |
| Açık kritik/yüksek güvenlik | 0 | 0 | ✅ |
| Sözleşme iş kuralları (A.1 §1-6) | tümü | free+trial 400, kullanımdaki edition 409, fiyat snapshot, override zinciri — canlı kanıt | ✅ |

**Smoke kanıtı (canlı):** host izinleri 22 · editions list · edition create (id=4, 49.90 USD) ·
edition feature ata (maxUserCount=25) · paket atama **TRIALING + snapshot 49.9000 USD/MONTHLY + trialEnd 14g** ·
free+trial reddi **400** · kullanımdaki edition silme reddi **409** · tenant override 25→**50** ·
tenant izin seti **14** (host-only yok) · tenant edition create **403** · tenant feature yükseltme **403** ·
`/me` yalnız kendi aboneliği.

**Süreç bulgusu (F5-R9):** İlk smoke, testlerin yakalayamadığı gerçek bir üretim hatasını ortaya çıkardı —
mevcut kurulumda yeni izinler statik Admin rollerine eklenmiyordu (host admin 17/22, `/api/editions` 403).
Testler temiz DB kullandığı için yeşildi (**false-green**, R-19 sınıfı). Düzeltme: her açılışta çalışan
idempotent izin-uzlaştırma + `RolePermissionReconciliationIT` (negatif kanıtla doğrulandı). Canlı log:
`Static role 'Admin' reconciled to 22 permission(s)`.
**Kural:** her faz için **canlı smoke zorunlu** — temiz-DB testi yeterli değil.

**F5 Slice A quality-gate: GEÇTİ.**

---

## F5 Slice B + Production Hardening — kapanış turu — 2026-07-18 ✅ (Lead doğruladı)

> **Neden bu girdi geç yazıldı — kaydı düzeltme.** `CONTRACT-phase5` §"Slice B quality gate — canlı
> smoke ZORUNLU" madde 3, sonuçların bu dosyaya PASS/FAIL olarak yazılmasını şart koşuyordu. Slice B
> ve ardından gelen yedi sertleştirme turu boyunca **bu satır hiç yazılmadı**; dosyanın son girdisi
> F5 Slice A'ydı. Sözleşmenin kendi kanıt kapısı yerine getirilmemişti ve bunu turu kapatan
> bağımsız inceleme yakaladı. Aşağıdaki tablolar o boşluğu bu turun **gerçek ölçümleriyle** kapatıyor;
> geçmişe dönük tahmin yok, her satır ya bir test adı ya bir canlı ölçümdür.

### Mühendislik kapıları

| Kapı | Eşik | Sonuç | Durum |
|---|---|---|---|
| Backend `clean verify` | yeşil | **326 test** (236 IT + 90 unit), 0 fail / 0 error / 0 skip — Lead elle koştu, `BUILD SUCCESS` | ✅ |
| `clean` gerçekten koştu mu | zorunlu (E3) | `maven-clean-plugin` log'da; sıfırdan derleme, incremental değil | ✅ |
| Build log `[ERROR]` satırı | 0 | **0** | ✅ |
| JaCoCo coverage check | geçmeli | "All coverage checks have been met" | ✅ |
| `ModularityTests` | döngü yok | geçti | ✅ |
| Frontend | yeşil | **90/90** (F5 Slice A'dan beri değişmedi — bu turda backend sözleşmesi değişti, **yeniden doğrulanmadı**, artık risk olarak kayıtlı) | ⚠️ |
| Açık kritik/yüksek **sömürülebilir** güvenlik bulgusu | 0 | 0 | ✅ |

### Sözleşme kapısı — Slice B canlı smoke (CONTRACT-phase5 §Slice B)

Ortam: **temiz değil, migrate edilmiş mevcut DB** (`zero-postgres:5433`, şema v6), dev profil, gerçek
`java -jar` başlangıcı. Bu şart F5-R9'dan sonra konuldu: temiz-DB suite'i yeşilken çalışan kurulum bozuktu.

| Kriter | Sonuç | Kanıt (canlı ölçüm) |
|---|---|---|
| Süresi geçmiş abonelik → GRACE / EXPIRED | **PASS** | `grace_day_count=0` → ACTIVE→**EXPIRED**; `=3` → ACTIVE→**GRACE**, `grace_end_at = period_end + 3g`. `subscription_events` id=10/11, `actor=lifecycle-job`. Log: `advanced 2 subscription(s)` |
| Guard: EXPIRED tenant iş uçlarında 403 | **PASS** | `/api/users`, `/api/roles`, `/api/organization-units`, `/api/audit-logs` → **403 SUBSCRIPTION_INVALID** |
| Guard self-lock **yok** | **PASS** | Aynı anda `/api/auth/login` **200**, `/api/auth/me` **200**, `/api/subscriptions/me` **200** (`status: EXPIRED`) — kullanıcı kilitlenip ödeme yapamaz duruma düşmüyor |
| `@RequiresFeature` kapalı feature → 403 | **PASS** | `app.organizationUnits=false` → `/api/organization-units` **403 FORBIDDEN** (öncesinde 200) |
| Feature değişiminden sonra cache **stale değil** | **PASS** | Kapat→**anında 403**, aç→**anında 200**. Bekleme/retry yok (F5-R2) |
| `app.maxUserCount` aşımı → red | **PASS** | canlı=4, limit=4 → **400 VALIDATION**; limit=0 → **201** (0 = sınırsız). Limit yalnız `deleted=false` sayıyor — doğru |
| Proration; dönem sonu **kaymıyor** | **PASS** | 100→300 MONTHLY upgrade: `prorationAmount 199.9988` = `(300-100) × 0.999994026284` (HALF_UP, scale 4). `period_end` öncesi/sonrası **birebir aynı** |
| Migration dry-run (mevcut veri) | **PASS** | `Successfully validated 6 migrations`, `Schema public is up to date`. V4/V5/V6 **dolu** tablolar üzerine uygulanmış (history `installed_on` > veri `created_at`), `success=t`. Ayrıca boş DB'de V1→V6 temiz kurulum kanıtı |

### Sertleştirme regresyon smoke'u (7 tur, canlı)

| Kontrol | Sonuç |
|---|---|
| Rate limit + `Retry-After` | 1-10 → 401, **11+ → 429**, `Retry-After` 45→44 azalarak (refill doğru) |
| Yol/medya-tipi bypass'ları (B1/B2/D1/D2) | `text/plain` **415**, `application/yaml` **415**, 20 KB login **413** (`maxBodyBytes=16384`) |
| Kimlikli uç + sınır üstü gövde (F1) | 1.5 MB `/api/users` **413**; **`Transfer-Encoding: chunked` de 413** (header-only kontrol burada atlanırdı); WARN, stack trace yok |
| Güvenlik başlıkları (PROD-R4/R5) | Düz HTTP'de HSTS **yok**; `X-Forwarded-Proto: https` ile **var**. CSP/Referrer/Permissions/`X-Frame-Options: DENY` her yanıtta |
| CORS (PROD-R3) | Listeli origin **200 + ACAO**; `evil.example.com` **403**, ACAO yok |
| `/v3/api-docs` (B6/C5) | dev **200**; **profilsiz boot 401** (fail-closed doğrulandı) |
| Log bütçesi (E1/E4) | 12 bozuk istek → 415/404/400/400/413/400/200/405/401/400/400/400; **ERROR satırı 0**, 500 **yok**. Tüm smoke boyunca toplam ERROR: **0** |
| Tenant izolasyonu (regresyon) | tenant→`POST /api/editions` **403**; host token + `X-Tenant` mismatch **403**; host `/me` **22 izin**, `tenantId=null` |
| **Actuator (PROD-R17)** | anonim **401**, sıfır izinli tenant kullanıcısı **403**, host admin **200**, probe'lar anonim **200** |

### Load smoke

420 istek / 8 paralel worker / 3 uç. **p50 20.9 ms · p95 39.7 ms · p99 46.2 ms · max 52.9 ms**,
throughput 343.5 req/s, **hata oranı %0.00 (0/420)**, Hikari leak/timeout uyarısı 0.
*Uyarı:* ölçüm dev profilinde **Hibernate SQL DEBUG açıkken** alındı → gerçek prod performansının alt sınırı.

### SQL / N+1 gözlemi

Yöntem: her istek öncesi/sonrası `org.hibernate.SQL` ifade sayımı.
`/api/subscriptions` sayfa boyutu 1 → 5 ifade, 5 → **5 ifade** (satırla büyümüyor, N+1 yok);
`/api/users` roller join-fetch, kullanıcı başına sorgu yok; `/api/editions` 2 ifade.

**Bulgu (bloke edici değil, açık):** `/api/users` **SQL'de sayfalamıyor** —
`HHH90003004: firstResult/maxResults specified with collection fetch; applying in memory`.
Sebep: `UserRepository:36-71`, dört sayfalanan sorguda `@EntityGraph("roles")` + `Page<User>`.
5 kullanıcıda görünmez; tek tenant'ta 50k kullanıcıda **her sayfa isteği** tüm seti heap'e çeker.
Feature freeze nedeniyle değiştirilmedi; **PROD-R21** olarak kaydedildi.

**F5 Slice B + hardening quality-gate: GEÇTİ.**

> Kural: bu tablo "yeşil" göstermeden hiçbir Faz 2 kalemi "Done" sayılmaz.
