# ADR-0013: `BillingPeriod` + `java.time` (30/365 gün sabitleri terk edildi)

- **Durum:** Accepted · **Tarih:** 2026-07-18 · **Faz:** F5-A

## Bağlam
ASP.NET Zero'da `PaymentPeriodType` enum **değerinin kendisi gün sayısıdır**: `Monthly = 30`, `Annual = 365`.
Bu değer `(int)` cast ile hem tarih hesabında (`end = now + (int)period`) hem proration formülünde kullanılıyor.
Sonuç: 30 gün ≠ 1 ay. 31 Ocak'ta başlayan aylık abonelik 2 Mart'ta biter; yıl içinde kayma birikir.

## Karar
`BillingPeriod { MONTHLY, ANNUAL }` — **değer gün taşımaz**. Tarih aritmetiği `java.time.Period.ofMonths(1|12)`
ile; ay-sonu taşmaları `java.time`'ın clamp davranışıyla (31 Oca + 1 ay = 28/29 Şub) ve testle sabitlenir.
Tüm zaman damgaları UTC (`timestamptz`).

## Gerekçe
Takvim doğruluğu sözleşmesel bir konu: müşteri "aylık" satın aldığında ay sonunu bekler, 30 günü değil.
Kaynak sistemin sapması sessiz ve birikimli.

## Sonuçlar
- (+) K7 çözüldü; fatura dönemleri takvimle hizalı.
- (−) **ETL riski:** mevcut `SubscriptionEndDateUtc` değerleri 30-günlük mantıkla üretilmiş.
  Bu yüzden F6'da tarih **yeniden hesaplanmayacak, doğrudan taşınacak** (`F5-ETL-IMPACT.md` P4 / F6-R2).
- (−) Proration formülü kaynak formülünden uyarlanırken gün yerine dönem/oran bazlı yazılacak (F5-B).
