# ADR-0011: Webhook idempotency — `webhook_events` UQ + duplicate/kalıcı hatada 200

- **Durum:** Accepted · **Tarih:** 2026-07-18

## Bağlam
Ödeme sağlayıcıları webhook'ları **en az bir kez** teslim eder: aynı event birden çok kez gelir ve bu
bir arıza değil, sözleşmenin parçasıdır. İşlenmiş event id'si kaydedilmezse iki ayrı hata doğar:
(1) duplicate bir yenileme event'i aboneliği **iki kez uzatır**; (2) duplicate'e hata (400) yanıtı
vermek sağlayıcıya "başarısız" sinyali gönderir ve aynı event **sonsuza dek** yeniden denenir.

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
HTTP durum kodu burada "iş başarılı mı" değil, "bu event'i bir daha gönder mi" anlamına gelir —
kalıcı bir hatada retry istemek sonsuz döngü üretir.

## Sonuçlar
- (+) Çift uzatma/çift tahsilat yapısal olarak engellenir.
- (+) `webhook_events` denetim ve replay kaynağı olur.
- (−) Ek tablo + her event'te bir INSERT maliyeti (kabul).
