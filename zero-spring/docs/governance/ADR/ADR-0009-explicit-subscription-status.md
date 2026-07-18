# ADR-0009: Explicit `SubscriptionStatus` + domain geçiş metotları

- **Durum:** Accepted · **Tarih:** 2026-07-18 · **Faz:** F5-A

## Bağlam
ASP.NET Zero'da abonelik durumu **ayrı bir kolonda tutulmuyor**; `Tenant.IsActive` +
`SubscriptionEndDateUtc` + `IsInTrialPeriod` + `EditionId` kombinasyonundan türetiliyor. Bu, ambiguous
durumlar üretiyor (`IsActive=false` + gelecek tarihli end date gibi) ve her okuyucu kendi yorumunu yapıyor.
Ayrıca durum geçiş guard'ları sessiz no-op (`SetAsPaid` vb.) — çağıran geçişin olup olmadığını bilemiyor.

## Karar
`subscriptions.status` **explicit** kolon: `TRIALING | ACTIVE | GRACE | EXPIRED | CANCELLED | PENDING_PAYMENT`.
Geçişler yalnız domain metotlarıyla; geçersiz geçiş **`DomainException(VALIDATION)` fırlatır** (sessiz no-op yasak).
Her başarılı geçiş `subscription_events` tablosuna (from/to/reason/actor) yazılır.
Provisioning (kaynak durumu olmayan S1-S3) geçiş guard'ına tabi değildir; durumu edition'dan hesaplar.

## Gerekçe
- Ambiguity ortadan kalkar; sorgular (`status = 'EXPIRED'`) doğrudan ve indekslenebilir.
- Yaşam döngüsü denetlenebilir (`subscription_events`), ETL'de "bu abonelik nereden geldi" izi kalır.
- Geçiş ihlali sessizce yutulmaz → K11 tekrarlanmaz.

## Sonuçlar
- (+) `SubscriptionStateMachineIT` ile geçiş matrisi test edilebilir hale geldi.
- (−) ETL'de implicit → explicit **durum türetme kuralı** gerekiyor (`F5-ETL-IMPACT.md` §2'de sabitlendi, F6-R1).
- (−) Provisioning/transition ayrımı bir nüans: `CANCELLED` abonelik `activate` ile açılamaz ama paket
  yeniden atanarak kurtarılabilir — bilinçli ve testli.
