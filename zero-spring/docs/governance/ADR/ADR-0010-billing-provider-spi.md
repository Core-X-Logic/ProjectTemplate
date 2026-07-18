# ADR-0010: `BillingProvider` SPI + registry; gerçek gateway ikinci slice'a

- **Durum:** Accepted · **Tarih:** 2026-07-18 · **Faz:** F5-A (SPI) / F5-C (Stripe)

## Bağlam
ASP.NET Zero'da **ortak bir payment gateway arayüzü yok**. `StripeGatewayManager` ve `PayPalGatewayManager`
birbirinden bağımsız somut sınıflar; `CancelSubscription` yalnız Stripe'ta var. Soyutlama sadece
`IPaymentGatewayConfiguration` (IsActive/SupportsRecurring/GatewayType) ve bir enum düzeyinde. Yeni bir
sağlayıcı eklemek 7 ayrı katmanda elle iş gerektiriyor.

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
Spring `List<BillingProvider>` → `BillingProviderRegistry` (`key()` ile). Slice A'da yalnız
`ManualBillingProvider` (host admin manuel onay); **Stripe Slice C'de** (Prices API, `metadata.tenantId`).

## Gerekçe
- Yeni sağlayıcı = tek sınıf; kaynak sistemdeki 7-katmanlı iş ortadan kalkar.
- Gerçek gateway riskini (webhook, imza, para akışı) ikinci slice'a erteleyerek Slice A'yı ödeme
  bağımlılığı olmadan kapatılabilir kılar — kullanıcı sözleşmesinin açık isteği.
- `fetchStatus` reconciliation için zorunlu (ADR-0014).

## Sonuçlar
- (+) Test edilebilirlik: sağlayıcı mock'lanabilir, iş mantığı gateway'siz doğrulanır.
- (−) Slice A'da gerçek para akışı yok; `PENDING_PAYMENT` yalnız manuel onayla çözülür (kabul).
