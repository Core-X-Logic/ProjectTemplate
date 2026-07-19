# ADR-0009: Explicit `SubscriptionStatus` + domain geçiş metotları

- **Durum:** Accepted · **Tarih:** 2026-07-18

## Bağlam
Abonelik durumu iki şekilde temsil edilebilir: birkaç bayrak ve tarihten **türetilerek**
(`isActive` + `endDate` + `isInTrial` + `editionId`), ya da **explicit bir kolonda** tutularak.
Türetme yaygın bir başlangıçtır ve iki tuzağı vardır: (1) ambiguous kombinasyonlar üretir
(`isActive=false` + gelecek tarihli bitiş gibi) ve her okuyucu kendi yorumunu yapar; (2) durum
geçişleri "sessiz no-op" olmaya meyleder — çağıran, geçişin gerçekleşip gerçekleşmediğini bilemez.

## Karar
`subscriptions.status` **explicit** kolon: `TRIALING | ACTIVE | GRACE | EXPIRED | CANCELLED | PENDING_PAYMENT`.
Geçişler yalnız domain metotlarıyla; geçersiz geçiş **`DomainException(VALIDATION)` fırlatır** (sessiz no-op yasak).
Her başarılı geçiş `subscription_events` tablosuna (from/to/reason/actor) yazılır.
Provisioning (henüz bir durumu olmayan yeni abonelik) geçiş guard'ına tabi değildir; durumu edition'dan hesaplar.

## Gerekçe
- Ambiguity ortadan kalkar; sorgular (`status = 'EXPIRED'`) doğrudan ve indekslenebilir.
- Yaşam döngüsü denetlenebilir (`subscription_events`): "bu abonelik neden bu durumda" sorusunun cevabı kayıtlı.
- Geçiş ihlali sessizce yutulmaz: geçersiz geçiş çağıranın yüzüne patlar.

## Sonuçlar
- (+) `SubscriptionStateMachineIT` ile geçiş matrisi test edilebilir hale geldi.
- (−) Mevcut bir sistemden veri aktarılacaksa implicit → explicit **durum türetme kuralı** yazmak gerekir.
- (−) Provisioning/transition ayrımı bir nüans: `CANCELLED` abonelik `activate` ile açılamaz ama paket
  yeniden atanarak kurtarılabilir — bilinçli ve testli.
