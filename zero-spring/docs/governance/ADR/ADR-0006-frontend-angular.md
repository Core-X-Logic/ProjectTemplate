# ADR-0006: Frontend Angular ile devam (React/Next.js değil)

- **Durum:** ~~Accepted~~ **SUPERSEDED by [ADR-0008](ADR-0008-frontend-react-vite.md)** (2026-07-17)
- **Tarih:** 2026-07-17
- **Faz:** F2 (planlama) / F4 (uygulama)

> **NOT:** Bu karar aynı gün kullanıcı tarafından geri alındı. Yeni scope-lock sözleşmesi frontend'i
> **React + Vite + TypeScript** (Metronic React starter kit vendor'ı) olarak zorunlu kıldı. Angular iptal.
> Aşağıdaki içerik tarihsel kayıt olarak korunur; geçerli karar ADR-0008'dedir.

## Bağlam

Analiz (ANALYSIS §5) React/Next.js önerdi; ancak kullanıcı, netleştirme sorusuna **Angular ile devam**
yanıtı verdi. Karar kullanıcı tarafından sabitlendi.

## Karar

Frontend **Angular** ile devam eder. Mevcut Angular 19 kod tabanı temel alınır; ancak:
- `abp-ng2-module` / `abp.js` / ABP response-zarfı bağımlılıkları yerine **ince, ABP-bağımsız oturum ve
  izin soyutlama katmanı** yazılır (yeni backend ABP değil).
- Service proxy'leri `openapi-generator` (typescript-angular) ile üretilir; `API_BASE_URL` token'ı korunur.
- SignalR istemcisi → STOMP istemcisine geçer (F3 real-time için).

## Gerekçe

- Kullanıcı kararı (governance: teknoloji kilidi — yeni framework kararı alınmaz).
- Ekip Angular bilgisi + mevcut ekran mirasının yeniden kullanımı.
- Not: "Angular devamı" bile büyük refactor içerir (ABP katmanı sökümü); bu ADR o maliyeti kabul eder.

## Sonuçlar

- (+) Mevcut UI/UX ve ekip bilgisi korunur.
- (−) `abp-ng2-module` söküm efori (R-09).
- (−) Metronic lisansı + gulp bundle pipeline'ı devam eder (ticari karar dışıdır bu ADR'nin).
