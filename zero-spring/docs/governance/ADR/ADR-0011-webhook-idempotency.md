# ADR-0011: Webhook idempotency — `webhook_events` UQ + duplicate/kalıcı hatada 200

- **Durum:** Accepted · **Tarih:** 2026-07-18 · **Faz:** F5-C (tasarım F5-A'da sabitlendi)

## Bağlam
ASP.NET Zero'da webhook idempotency **yok**: işlenmiş `event.Id` kaydı tutulmuyor. Tek savunma bir durum
guard'ı; duplicate event geldiğinde `ApplicationException` → controller `catch` → **`BadRequest()` (400)**.
Stripe 400'ü başarısızlık sayar ve **aynı event'i sonsuza dek yeniden gönderir**. Recurring yolunda
(`invoice.paid`) hiç guard yok → aynı event iki kez gelirse **abonelik iki kez uzar**.

## Karar
```
1. İmza doğrula. Geçersiz → 400 (+alarm).
2. INSERT webhook_events(provider, event_id, …) ON CONFLICT (provider, event_id) DO NOTHING
   0 satır ⇒ DUPLICATE → hiçbir iş yapma, 200 OK.
3. İş mantığı ayrı tx; başarı → PROCESSED.
4. Geçici hata → RETRYABLE + 5xx (sağlayıcı yeniden dener)
   Kalıcı hata → DEAD + last_error + 200 OK  (sonsuz retry döngüsü YASAK)
5. Bilinmeyen event tipi → kaydet + 200.
```
Aynı idempotency recurring yenileme yoluna da uygulanır. `DEAD` kayıtlar admin ekranından **manuel replay** edilebilir.

## Gerekçe
Duplicate teslimat bir hata değil, sağlayıcının **normal** davranışıdır; doğru yanıt 200'dür.
Kalıcı hatada retry istemek sonsuz döngü üretir (kaynak sistemin fiili durumu).

## Sonuçlar
- (+) Çift uzatma/çift tahsilat yapısal olarak engellenir.
- (+) `webhook_events` denetim ve replay kaynağı olur.
- (−) Ek tablo + her event'te bir INSERT maliyeti (kabul).
