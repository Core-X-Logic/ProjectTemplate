# A) Frontend Architecture Addendum — zero-platform (React + Vite + TS)

ADR-0008 kararının uygulanabilir mimari eki. Kaynak: `frontend/vendor/` (Metronic React starter v9.3.2,
REFERANS). Ürün: `frontend/app/` (sıfırdan kurulur; altyapı vendor'dan seçmeli taşınır).

## 1. Stack (kilitli)

- **React 19 + Vite 7 + TypeScript 5.9** (strict). Node ≥ 20 (doğrulanan: v24.16).
- **Tailwind CSS 4** (CSS-first, `@tailwindcss/vite`, klasik `tailwind.config.js` YOK) + **shadcn/ui** (radix, zinc, cssVariables).
- **@tanstack/react-query v5** (server state) — tek data-fetching kütüphanesi.
- **react-router-dom v7** (declarative) — public (auth) + protected (admin shell) grupları.
- **react-hook-form + zod** (form + doğrulama) — tek form kütüphanesi.
- **react-intl** (i18n, en/tr) · **next-themes** (dark mode) · **sonner** (toast) · **react-helmet-async** (head).
- **Typed API client**: backend OpenAPI'sinden `openapi-typescript` ile üretilen tipler + ince `fetch` wrapper.

## 2. Vendor → app taşıma politikası (governance: ham vendor commit edilmez)

| AYNEN taşınır (altyapı) | SIFIRDAN yazılır (iş katmanı) |
|---|---|
| `lib/utils.ts` (`cn`), `lib/helpers.ts`, `lib/dom.ts` | `api/client.ts` (typed fetch, interceptor, token, hata→ProblemDetail) |
| `components/ui/*` (78 shadcn primitifi + `data-grid*`) | `api/query-client.ts` (QueryClient) + generated `api/schema.d.ts` |
| `styles/globals.css` + `config.metronic.css` + `styles/components/*` | `providers/auth-provider.tsx` (token store, login/logout/refresh) |
| `hooks/*` (7 generic) | `providers/tenant-provider.tsx` (aktif tenant + `X-Tenant`) |
| `config/types.ts` (`MenuConfig`) | `providers/i18n-provider.tsx` (IntlProvider + locale switch) |
| `components/layouts/layout-1/` (tek admin shell) + `hooks/use-menu.ts` | `auth/rbac.tsx` (`usePermission`, `<Can>`, menü/rota filtre) |
| config bütünü (vite/tsconfig/components.json/eslint/prettier) | `auth/require-auth.tsx` (route guard) + `auth/pages/login.tsx` |
| — | `i18n/messages/{en,tr}.ts` · `config/menu.config.tsx` (budanmış) · `routing/routes.tsx` |

**Taşınmaz:** 34 diğer layout, tüm `pages/` demoları (referans).

## 3. Sağlayıcı zinciri (App.tsx — hedef)

```
ThemeProvider (next-themes, storageKey="vite-theme")
 └ HelmetProvider
    └ QueryClientProvider (+ Devtools dev'de)
       └ I18nProvider (react-intl, locale state)
          └ AuthProvider (token + me)
             └ TenantProvider (aktif tenant)
                └ BrowserRouter
                   └ Toaster (sonner) + AppRoutes
```

## 4. Auth akışı (Faz 1 backend API'sine dayanır — stabil)

- `POST /api/auth/login` (body `usernameOrEmail`, `password`; header `X-Tenant` seçili tenant için) → `{accessToken, refreshToken, expiresInSeconds}`.
- Token saklama: **access token bellek (memory) + refresh token httpOnly değil → localStorage** (SPA gerçeği; XSS azaltımı: CSP + kısa access TTL). **Varsayım:** cookie-tabanlı refresh F4 hardening'de değerlendirilecek (R-06/R-19).
- `POST /api/auth/refresh` (401 interceptor'da otomatik, tek uçuş/singleflight) → rotate.
- `GET /api/auth/me` → `{id, username, email, tenantId, roles, permissions}` → AuthContext + RBAC kaynağı.
- Logout → `POST /api/auth/logout` + token temizle.

## 5. RBAC (permission guard)

- `permissions` (me'den) → `usePermission(perm)` boolean; `<Can permission="users.read">…</Can>`.
- Route guard: `<RequireAuth permission="users.read">` — yetkisiz → 403 sayfası; giriş yok → `/login`.
- Menü (`menu.config.tsx`) her item'a `permission` alanı; `use-menu` filtreler. Backend `@PreAuthorize` ile **çift kilit** (frontend gizleme UX, backend zorlama güvenlik).

## 6. i18n

- `react-intl` `IntlProvider`; mesajlar `i18n/messages/en.ts` + `tr.ts` (backend `/api/localization/{culture}` ile senkron anahtarlar — Faz 2 backend'i aynı anahtar setini üretir). Locale switch (header) → localStorage + `Accept-Language`.
- **Varsayım:** İlk sürümde mesajlar statik TS; backend DB-çeviri (F3) sonrası runtime birleştirme eklenebilir.

## 7. Data katmanı + typed client

- Backend `mvnw` çalışırken `/v3/api-docs` → `openapi-typescript` → `src/api/schema.d.ts` (build öncesi `npm run gen:api`).
- `api/client.ts`: `apiFetch<T>(path, init)` — base URL `VITE_API_BASE_URL`, `Authorization: Bearer`, `X-Tenant`, 401→refresh→retry, hata gövdesi RFC 9457 ProblemDetail → `ApiError`.
- Feature'lar `@tanstack/react-query` `useQuery`/`useMutation` ile typed endpoint sarmalayıcıları.

## 8. Klasör yapısı (`frontend/app/src`)

```
main.tsx, App.tsx
styles/            (vendor: globals.css + config.metronic.css + components/*)
lib/               (vendor: utils cn, helpers, dom)
components/ui/      (vendor: 78 shadcn + data-grid)
hooks/             (vendor: 7 generic + use-auth, use-tenant)
api/               client.ts, query-client.ts, schema.d.ts(gen), endpoints/<feature>.ts
providers/         auth-provider, tenant-provider, i18n-provider
auth/              rbac.tsx, require-auth.tsx, pages/{login,forgot-password}
i18n/messages/     en.ts, tr.ts
layouts/admin/     (vendor layout-1 uyarlaması)
config/            types.ts, menu.config.tsx
features/          users/ roles/ organization-units/ notifications/ (+audit,settings,saas sonraki slice)
                     her feature: api.ts, hooks.ts, pages/, components/, types.ts
routing/           app-routing.tsx, routes.tsx
test/              setup.ts (vitest + @testing-library/react)
```

## 9. Build & test (quality gate — kanıt zorunlu)

- Build: `npm run build` (`tsc && vite build`) BUILD SUCCESS.
- Typed client: `npm run gen:api` (backend çalışırken) hatasız.
- Test: **Vitest + @testing-library/react** — her vertical slice ≥ 1 davranış testi (örn. login formu doğrulama + submit; users listesi permission guard). Ek: Playwright e2e F4 (opsiyonel, slice başına smoke).
- Lint: `eslint src` temiz; TS strict hata yok.

## 10. Tekilleştirme (R-16, ADR-0008)

app/ package.json'dan ATILANLAR: `react-query@3` (→ @tanstack v5), `formik` (→ rhf+zod),
`vite-plugin-windicss` (Tailwind 4 tek motor), `react-helmet` (→ helmet-async), `notistack` (→ sonner),
atıl `postcss/autoprefixer/postcss-preset-env`, ikinci charting lib (recharts tutulur, apexcharts opsiyonel).
