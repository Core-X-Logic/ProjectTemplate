# ADR-0012: Abonelikte fiyat snapshot; edition düzenlenebilir kalır

- **Durum:** Accepted · **Tarih:** 2026-07-18 · **Faz:** F5-A

## Bağlam
ASP.NET Zero'da `UpdateEdition` **yalnız `DisplayName` ve feature değerlerini** günceller; `MonthlyPrice`,
`AnnualPrice`, `TrialDayCount`, `WaitingDayAfterExpire` DTO'da bile yok. Fiyat değiştirmek için **yeni edition
oluşturup tenant'ları taşımak** gerekiyor (`MoveTenantsToAnotherEdition`). Amaç doğru (aktif abonelerin fiyatını
geriye dönük bozmamak) ama maliyeti yüksek: edition katalogu çoğalıyor, plan kimliği (`Name_Period_Currency`)
dondurulmuş oluyor.

## Karar
Edition **tam düzenlenebilir** (fiyat, trial, grace, expiring dahil). Buna karşılık abonelik oluşturulurken
fiyat **snapshot'lanır**: `subscriptions.price_amount`, `price_currency`, `billing_period`.
Mevcut aboneler edition fiyatı değişse de kendi snapshot'larından faturalanır.

## Gerekçe
- Aynı koruma (mevcut abone etkilenmez) daha ucuz yoldan sağlanır.
- Operatör yazım hatasını düzeltmek için yeni edition açmak zorunda kalmaz.
- Fatura/proration hesabı aboneliğin kendi verisinden yapılır — edition'a bağımlı değil.

## Sonuçlar
- (+) K13 çözüldü; katalog temiz kalır.
- (+) Canlı smoke: paket atamada `price=49.9000 USD, period=MONTHLY` snapshot'landığı doğrulandı.
- (−) "Tüm abonelere yeni fiyatı uygula" senaryosu artık **açık bir toplu işlem** gerektirir (F5-B/C adayı).
