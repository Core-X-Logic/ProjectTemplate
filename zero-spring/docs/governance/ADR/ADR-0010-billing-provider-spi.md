# ADR-0010: `BillingProvider` SPI + registry

- **Durum:** Accepted · **Tarih:** 2026-07-18

## Bağlam
Ödeme sağlayıcıları ortak bir arayüz olmadan entegre edilirse (her sağlayıcı için bağımsız somut bir
sınıf, aralarında yalnız bir enum düzeyinde soyutlama), her yeni sağlayıcı birden çok katmanda elle iş
gerektirir ve yetenekler sağlayıcıya göre asimetrik kalır — biri iptali destekler, diğeri desteklemez.
Ayrıca iş mantığı gerçek bir gateway olmadan test edilemez hale gelir.

## Karar
Tek arayüz:
```java
interface BillingProvider {
    String key(); boolean supportsRecurring();
    CheckoutSession createCheckout(CheckoutRequest r);
    PaymentStatus fetchStatus(String externalPaymentId);
    void cancelSubscription(String externalSubscriptionId);
}
```
Spring `List<BillingProvider>` → `BillingProviderRegistry` (`key()` ile). Şablonda yalnız
`ManualBillingProvider` (host admin manuel onay) vardır; gerçek bir gateway (ör. Stripe) bu arayüzü
uygulayan tek bir sınıf olarak eklenir.

## Gerekçe
- Yeni sağlayıcı = tek sınıf, çok-katmanlı elle iş yok.
- Abonelik iş mantığı (durum geçişleri, proration, feature çözümü) gerçek para akışı olmadan
  kurulabilir ve test edilebilir; gateway riski (webhook, imza, tahsilat) ayrı bir adıma bırakılır.
- `fetchStatus` reconciliation için zorunlu (ADR-0014).

## Sonuçlar
- (+) Test edilebilirlik: sağlayıcı mock'lanabilir, iş mantığı gateway'siz doğrulanır.
- (−) Şablonda gerçek para akışı yok; `PENDING_PAYMENT` yalnız manuel onayla çözülür (kabul).
