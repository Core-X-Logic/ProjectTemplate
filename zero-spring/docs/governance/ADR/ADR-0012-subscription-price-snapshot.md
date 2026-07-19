# ADR-0012: Abonelikte fiyat snapshot; edition düzenlenebilir kalır

- **Durum:** Accepted · **Tarih:** 2026-07-18

## Bağlam
"Mevcut abonelerin fiyatı geriye dönük değişmemeli" kuralı iki şekilde sağlanabilir. Yaygın yol
edition'ı **kısmen dondurmaktır**: fiyat alanları düzenlenemez yapılır, fiyat değiştirmek için yeni bir
edition açılıp tenant'lar oraya taşınır. Amaç doğrudur ama maliyeti yüksektir — katalog her fiyat
değişikliğinde çoğalır ve bir yazım hatasını düzeltmek bile yeni bir plan gerektirir.

## Karar
Edition **tam düzenlenebilir** (fiyat, trial, grace, expiring dahil). Buna karşılık abonelik oluşturulurken
fiyat **snapshot'lanır**: `subscriptions.price_amount`, `price_currency`, `billing_period`.
Mevcut aboneler edition fiyatı değişse de kendi snapshot'larından faturalanır.

## Gerekçe
- Aynı koruma (mevcut abone etkilenmez) daha ucuz yoldan sağlanır.
- Operatör yazım hatasını düzeltmek için yeni edition açmak zorunda kalmaz.
- Fatura/proration hesabı aboneliğin kendi verisinden yapılır — edition'a bağımlı değil.

## Sonuçlar
- (+) Katalog temiz kalır; fiyat düzeltmesi yeni plan açmayı gerektirmez.
- (+) Snapshot'lama abonelik atamasında testle doğrulanır (`SubscriptionAssignmentIT`).
- (−) "Tüm abonelere yeni fiyatı uygula" senaryosu artık **açık bir toplu işlem** gerektirir (henüz yok).
