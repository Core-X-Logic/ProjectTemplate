# ADR-0008: Frontend — React + Vite + TypeScript (Metronic React starter)

- **Durum:** Accepted
- **Tarih:** 2026-07-17
- **Faz:** F2 (frontend execution)
- **Supersedes:** ADR-0006 (Angular)

## Bağlam

Kullanıcı, ADR-0006'daki (Angular ile devam) kararı bir **scope-lock sözleşmesiyle** geri aldı.
Yeni zorunlu stack: **React + Vite + TypeScript**. UI kaynağı: repoya eklenen
`zero-spring/frontend/vendor/` = **Metronic React starter kit v9.3.2**
(React 19 + Vite 7 + TS 5.9 + Tailwind 4 + radix/shadcn + @tanstack/react-query + react-router 7 + react-intl).

## Karar

1. Frontend = **React 19 + Vite 7 + TypeScript**. Angular tamamen iptal (mevcut ASP.NET Zero Angular yalnız
   parite karşılaştırması için referans; ürün kodu değil).
2. Ürün uygulaması `zero-spring/frontend/app/` altında kurulur. Vendor **ham dosyaları ürün kodu gibi
   commit edilmez**; yalnız gerekli parçalar (Tailwind config, `cn` util, theme provider, shadcn/ui
   primitifleri, admin layout/sidebar) app'e taşınır.
3. Backend OpenAPI'sinden **typed client** üretilir (`openapi-typescript` + typed fetch, veya
   `@openapitools/openapi-generator` typescript). API sözleşmesi tek kaynak.
4. Parity-odaklı mimari: `auth`, `tenant context`, `permission guard`, `i18n (react-intl, en/tr)`,
   `api-client`, feature modülleri (users, roles, organization-units, notifications, audit, settings, saas).
5. State/data: `@tanstack/react-query` (server state) + hafif context (auth/tenant/permission). Legacy
   `react-query@3` ve `formik` (starter'da mevcut) **kullanılmaz** — tekilleştir: react-hook-form + zod.

## Gerekçe

- Kullanıcı kararı (governance teknoloji kilidi — bu ADR o kararı kayda geçirir).
- Vendor starter hazır tasarım sistemi (Tailwind 4 + shadcn) + admin shell sağlar → UI paritesi hızlanır.
- Vite + TS build hızlı; react-query + typed client, ABP response-zarfına bağımlı olmayan temiz veri katmanı.

## Sonuçlar

- (+) Modern, lisans-esnek (Tailwind/shadcn) UI; geniş işe alım havuzu.
- (+) Typed client → derleme-zamanı API sözleşme güvencesi (quality gate: contract).
- (−) Tüm admin ekranları React'te sıfırdan (Angular mirası kullanılmaz) — R-09 güncellendi.
- (−) Starter'da çift bağımlılık (react-query v3+v5, formik+rhf, Windi+Tailwind) → app'te tekilleştirme gerekli (R-16).
- (−) Vendor→app taşıma disiplini gerekir (ham vendor commit edilmez) — governance kuralı.
