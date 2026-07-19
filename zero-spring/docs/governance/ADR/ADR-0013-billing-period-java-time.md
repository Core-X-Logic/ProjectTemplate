# ADR-0013: `BillingPeriod` + `java.time` (30/365 gün sabitleri terk edildi)

- **Durum:** Accepted · **Tarih:** 2026-07-18

## Bağlam
Faturalama dönemini gün sayısı olarak modellemek yaygın bir kısayoldur (`Monthly = 30`, `Annual = 365`)
ve enum değeri doğrudan tarih aritmetiğinde kullanılır. Sorun şu: 30 gün ≠ 1 ay. 31 Ocak'ta başlayan
aylık bir abonelik 2 Mart'ta biter ve sapma yıl boyunca birikir. Hata sessizdir — kimse şikâyet
etmeden dönem sınırları takvimden kayar.

## Karar
`BillingPeriod { MONTHLY, ANNUAL }` — **değer gün taşımaz**. Tarih aritmetiği `java.time.Period.ofMonths(1|12)`
ile; ay-sonu taşmaları `java.time`'ın clamp davranışıyla (31 Oca + 1 ay = 28/29 Şub) ve testle sabitlenir.
Tüm zaman damgaları UTC (`timestamptz`).

## Gerekçe
Takvim doğruluğu sözleşmesel bir konudur: müşteri "aylık" satın aldığında ay sonunu bekler, 30 günü
değil. Dönem uzunluğunu enum değerine gömmek ayrıca iki ayrı sorumluluğu (dönem kimliği ve tarih
aritmetiği) tek bir sayıya bindirir.

## Sonuçlar
- (+) Fatura dönemleri takvimle hizalı; ay sonu taşmaları testle sabit.
- (−) 30-günlük mantıkla üretilmiş mevcut bitiş tarihleri varsa (dışarıdan veri aktarımı) bunlar
  **yeniden hesaplanmamalı, olduğu gibi taşınmalıdır** — aksi halde müşterinin dönem sonu kayar.
- (−) Proration gün değil, dönem/oran bazlı hesaplanır (`ProrationCalculator`).
