# Değişiklik Günlüğü (CHANGELOG)

Keep a Changelog formatı. Tarihler mutlak (proje takvimi: 2026-07-17'de başladı).

## [Faz 1] — 2026-07-17 — İskelet + Auth + Tenant  ✅ GO

### Eklendi
- Spring Boot 3.5 / Java 21 / Modulith proje iskeleti (`backend/`), Maven Wrapper.
- Auth: JWT HS512 access (15 dk) + rotate-eden refresh (7 gün, SHA-256 hash), lockout (5/5dk).
- RBAC: `AppPermissions` izin sabitleri + method security (`@PreAuthorize`).
- Multi-tenancy: `X-Tenant` çözümleme + Hibernate `@Filter` izolasyon + JWT-claim otoriter doğrulama.
- Flyway `V1__baseline.sql` (tenants, users, roles, role_permissions, user_roles, refresh_tokens).
- Seed (host admin + default tenant), OpenAPI/Swagger, docker-compose (postgres/redis/mailpit), Dockerfile, CI.
- Testler: ModularityTests + IT'ler (auth, tenant izolasyon, user CRUD).

### Güvenlik (faz-sonu inceleme → düzeltildi)
- **[Kritik]** Tenant izolasyonu: header otoriteydi (privilege escalation) → `AuthenticatedTenantFilter`
  ile JWT `tenant` claim otoriter, mismatch=403. `TenantEscalationIT` 3/3.
- **[Yüksek]** `HibernateTenantFilterAspect` `@Order` + `TransactionOrderConfig` ile tx içinde garanti.
- **[Orta]** Refresh rotasyonu atomik + reuse kaskadı; prod seed default kapalı + fail-fast.

### Kanıt
- `mvnw verify`: **15/15 yeşil** (1 surefire + 14 failsafe, Testcontainers postgres:16-alpine, ~22s).
- `ApplicationModules.verify()`: geçti.

## [Faz 2] — devam ediyor — RBAC parite + OU + Audit + Settings + i18n + Email

### Planlanan (CONTRACT-phase2.md)
- Permission tree, Role tam CRUD, User tam yönetim (unlock/roles/OU/activate/soft-delete/Excel), Profil.
- Organization Units (materialized-path), Impersonation (act claim), şifre politikası + history.
- Audit modülü (HTTP audit + entity change history), Settings (hiyerarşik), Localization (en/tr), Email (Thymeleaf).
- Governance artefaktları kuruldu (working agreement, ADR 0001-0007, risk register, parity matrisi).

### Durum
- Backend codegen workflow başlatıldı (wlvp5pndp). **Kanıt bekleniyor** — verify yeşil olmadan "done" YOK.

### Değişti — Frontend teknoloji pivotu (2026-07-17, scope-lock)
- **Angular İPTAL → React + Vite + TypeScript zorunlu.** ADR-0006 superseded by **ADR-0008**.
- UI kaynağı: `frontend/vendor/` = Metronic React starter v9.3.2 (React 19 / Vite 7 / TS 5.9 / Tailwind 4 /
  radix-shadcn / @tanstack/react-query / react-router 7 / react-intl). Vendor ham dosyaları ürün kodu
  değil; gerekli parçalar `frontend/app/`'e taşınacak.
- Zorunlu modül kapsamı genişledi: Notifications (ilk slice'a çekildi, R-18) + SaaS (tenants/editions/
  feature gating/subscription, F5). Governance kanıt kuralı sertleşti: backend endpoint + React ekran +
  permission guard + i18n + test birlikte olmadan "done" yok.
- Risk register: R-16 (starter çift bağımlılık), R-17 (API sözleşme kayması), R-18 (notifications backend) eklendi.
- Toolchain doğrulandı: Node v24.16, npm 11.16 (Vite 7 uyumlu).

### Faz 2 backend codegen turu 1 (2026-07-18)
- 6 paralel yazıcı → RBAC/OU/impersonation/audit/settings/localization/email üretildi. `mvnw verify`:
  **42 IT + ModularityTests, 0 fail** (~48s). Yeni migration V2 (OU/settings/audit/entity_changes/password_history
  + user/role kolonları). Modulith yeni modüller: audit/settings/localization/notification.
- **Adversaryal inceleme false-green yakaladı (R-19):** verify yeşil ama boşluklar test edilmemiş —
  change-password policy bypass (R-20), password-history inert, OU entity-history bozuk, welcome/confirmation
  email gönderilmiyor, shouldChangePassword flag yok, leaf i18n çözülmüyor, policy/lockout/email setting'lerden
  okunmuyor, AuditLogIT flaky riski. **Modüller "done" değil.**
- Düzeltme workflow başlatıldı (w2lbc8ut9): 11 bulgu + 5 yeni pozitif parity testi (history reddi, OU history,
  email dispatch, shouldChangePassword, leaf i18n). Frontend slice A workflow (w130yhbgk) paralel.
- Frontend build config deprecation'ları (R-21) not edildi; slice A build turunda düzeltilecek.

### Faz 2 frontend slice A (2026-07-18) ✅ — Lead elle doğruladı
- `frontend/app/` React 19 + Vite 7 + TS strict iskeleti kuruldu; vendor Metronic altyapısı (shadcn/ui,
  admin shell, styles, hooks, cn util) taşındı; tekilleştirildi (formik/react-query@3/windicss/notistack atıldı).
- İş katmanı: typed `api/client` (401 refresh singleflight), auth/tenant/i18n providers, RBAC
  (`usePermission`/`<Can>`/`RequireAuth`), login (rhf+zod), i18n en/tr, admin layout + permission-filtreli menü.
- **Kanıt:** `npm run build` → built 4.38s (dist, 2025 modül); `npm run test` → **10/10 PASS**
  (login.test 3 + rbac.test 7), `tsc -b` strict temiz. Lead tarafından bağımsız koşuldu.
- Not: vendor CSS'te Tailwind4-uyumsuz `@media (max-width: var(...))` build uyarısı (kozmetik, R-21).
- Kalan: feature ekranları (users/roles/OU/notifications) placeholder → slice B (typed client + uçtan uca).

### Faz 2 Notifications backend + typed client (2026-07-18) ✅
- Notifications inbox backend (V3 + service + 4 endpoint + welcome-publish); NotificationInboxIT sahiplik
  izolasyonu kanıtlı; **51 test** (Lead + workflow doğrulaması). R-18 Closed.
- Backend dev'de ayağa kaldırıldı (5433/6380); `npm run gen:api` → typed client `schema.d.ts` (42 path). Contract gate ✅.
- CI: frontend job eklendi (npm ci + build + test); backend job JaCoCo artifact. mvnw LF + `.gitattributes` (R-22 mitigating).

### Faz 2 frontend slice B (2026-07-18) — feature ekranları
- 4 feature (Users/Roles+PermissionTree/OU/Notifications) typed client ile uçtan uca; build yeşil (2062 modül),
  **28 test** (slice A 10 + slice B 18). Güvenlik incelemesi 0 kritik/yüksek (token/tenant tek noktadan,
  permission adları birebir, double-lock route+`<Can>`+backend).
- İnceleme fonksiyonel gap'ler buldu (kritik değil): users search backend'de yok (#1), menü /audit+/settings
  route'suz 404 (#2), form submit `<Can>` eksik (#3), exportExcel 401-refresh atlıyor (#4), ölü feature
  IntlProvider (#5). **Polish turu başlatıldı** (backend search wb53z9m56 + frontend polish wg0dk6w1r) —
  kapanmadan modüller "done" sayılmaz.

### Faz 2 slice B polish + kapanış (2026-07-18) ✅
- Backend user search (wb53z9m56): GET /api/users `search` (tenant-scoped, case-insensitive) + IT → **52 test**.
- Frontend polish (wg0dk6w1r): 5 gap kapatıldı (menü-404, form `<Can>`, exportExcel apiFetch+blob, ölü IntlProvider,
  useMemo deps) + 16 yeni test (dropdown `<Can>`, require-auth, role-form) → **44 test**, build yeşil, ESLint 0.
- Backend güncel kodla restart; typed client regen (search param); Lead doğrulama: frontend 44 test + build 5.09s.
- **Uçtan uca smoke (canlı backend, seed admin):** login→me(14 izin)→users list+search→roles→permission-tree→
  OU→notifications→**tenant-escalation 403** = **9/9 PASS**.
- **İlk vertical slice KAPANDI:** Users + Roles/Permissions + OU + Notifications — 5 sütun tam, uçtan uca kanıtlı.
- Impersonation/Audit/Settings React ekranları slice C'ye ertelendi (kapsam kilidi). Faz 2 backend paritesi tam (52 test).

### Faz 2 Slice C — Impersonation + Audit + Settings UI (2026-07-18) ✅ KAPANDI
- 3 UI alanı uçtan uca: Impersonation (banner + users satır aksiyonu + auth-provider token swap + act-claim decode +
  cascade-block + back-to-impersonator); Audit (logs filtre/sıralama/sayfa/xlsx export + entity-history property diff);
  Settings (host/tenant tab + rhf batch update + any-permission guard + defaultValue ipucu).
- Backend: SettingDto.defaultValue eklendi (**53 test**). Frontend: **68 test** (44+24), build yeşil, tsc/eslint temiz.
- İnceleme 4 minör (kritik/yüksek 0) → hepsi kapatıldı: defaultValue backend, settings any-permission route/menu,
  impersonate DropdownMenuItem a11y, kullanılmayan i18n render.
- Typed client regen (defaultValue). **Uçtan uca smoke (canlı):** audit 9 + entity-changes 5 + settings defaultValue +
  impersonate→authenticate→cascade 403→back = tümü PASS.
- **Tüm Faz 2 modülleri KAPANDI** (Users/Roles/OU/Notifications + Impersonation/Audit/Settings). Yeni riskler: R-24
  (soft-delete unique username, düşük), R-25 (impersonate cascade UI-only, backend authoritative, düşük).
- Faz dışı: SaaS (F5), veri migration (F6), chat/realtime — kapsam dışı.
