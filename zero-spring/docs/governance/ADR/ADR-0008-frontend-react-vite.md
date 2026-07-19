# ADR-0008: Frontend — React + Vite + TypeScript (Metronic React starter)

- **Durum:** Accepted
- **Tarih:** 2026-07-17

## Bağlam

Admin SPA'nın framework'ü ve veri katmanı seçilmeli. Seçim yalnız dil/kütüphane tercihi değil:
API sözleşmesinin nasıl taşınacağı (elle yazılan tipler mi, üretilen typed client mı) ve hazır bir
admin tasarım sisteminin kullanılıp kullanılmayacağı da buna bağlı.

UI kaynağı olarak `zero-spring/frontend/vendor/` altında bir **Metronic React starter kit v9.3.2**
bulunur (React 19 + Vite 7 + TS 5.9 + Tailwind 4 + radix/shadcn + @tanstack/react-query +
react-router 7 + react-intl).

## Karar

1. Frontend = **React 19 + Vite 7 + TypeScript**.
2. Ürün uygulaması `zero-spring/frontend/app/` altında kurulur. Vendor **ham dosyaları ürün kodu gibi
   commit edilmez**; yalnız gerekli parçalar (Tailwind config, `cn` util, theme provider, shadcn/ui
   primitifleri, admin layout/sidebar) app'e taşınır.
3. Backend OpenAPI'sinden **typed client** üretilir (`openapi-typescript` + typed fetch, veya
   `@openapitools/openapi-generator` typescript). API sözleşmesi tek kaynak.
4. Modül yapısı: `auth`, `tenant context`, `permission guard`, `i18n (react-intl, en/tr)`,
   `api-client`, feature modülleri (users, roles, organization-units, notifications, audit, settings, saas).
5. State/data: `@tanstack/react-query` (server state) + hafif context (auth/tenant/permission). Legacy
   `react-query@3` ve `formik` (starter'da mevcut) **kullanılmaz** — tekilleştir: react-hook-form + zod.

## Gerekçe

- Vendor starter hazır tasarım sistemi (Tailwind 4 + shadcn) + admin shell sağlar → ekranlar sıfırdan
  kurulmaz.
- Vite + TS build hızlı; react-query + typed client, backend sözleşmesine derleme zamanında bağlı
  temiz bir veri katmanı verir.

## Sonuçlar

- (+) Modern, lisans-esnek (Tailwind/shadcn) UI; geniş işe alım havuzu.
- (+) Typed client → derleme-zamanı API sözleşme güvencesi (quality gate: contract).
- (−) Starter'da çift bağımlılık (react-query v3+v5, formik+rhf, Windi+Tailwind) → app'te tekilleştirme gerekli.
- (−) Vendor→app taşıma disiplini gerekir (ham vendor commit edilmez) — governance kuralı.
