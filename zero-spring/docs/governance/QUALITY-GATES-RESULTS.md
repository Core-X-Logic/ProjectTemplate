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

> Kural: bu tablo "yeşil" göstermeden hiçbir Faz 2 kalemi "Done" sayılmaz.
