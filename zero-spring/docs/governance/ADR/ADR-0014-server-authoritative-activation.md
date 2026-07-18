# ADR-0014: Server-authoritative aktivasyon (istemci çağrısı durum değiştirmez)

- **Durum:** Accepted · **Tarih:** 2026-07-18 · **Faz:** F5-C (kural F5-A'da sabitlendi)

## Bağlam
ASP.NET Zero'da ödeme akışının **son adımı istemciye bağlı**: webhook yalnız ödemeyi `Paid` yapar;
`Paid → Completed` geçişi ve **asıl edition yükseltmesi** tarayıcının `UpgradeSucceed(paymentId)` çağrısıyla
gerçekleşir. Kullanıcı ödeme sonrası tarayıcıyı kapatırsa: para tahsil edilir, **paket yükselmez**, fatura
kesilemez (`Completed` şartı). Telafi eden arka plan işi yok — `NotCompletedYesterdayPayment` yalnız `NotPaid`
kayıtları hedefler. PayPal tarafında webhook hiç yok, dolayısıyla capture bile kaçabilir.

## Karar
Abonelik durumu **yalnız sunucu tarafında** değişir:
1. **Birincil:** webhook (`ADR-0011` idempotency ile).
2. **Yedek:** reconciliation job — `PENDING_PAYMENT`/askıda kalan ödemeleri sağlayıcıdan
   `BillingProvider.fetchStatus()` ile doğrular ve tamamlar.

İstemcinin "ödeme başarılı döndüm" çağrısı **hiçbir durum değiştirmez**; yalnız UI yönlendirmesi içindir.

## Gerekçe
Ödeme alınıp hizmetin açılmaması, bir SaaS'ta en pahalı hata sınıfıdır (para iadesi + destek + güven kaybı).
İstemci ağı/sekmesi güvenilir bir taşıyıcı değildir.

## Sonuçlar
- (+) K1 kapandı; tarayıcı kapansa da abonelik açılır.
- (+) Webhook kaybında reconciliation ikinci savunma hattı.
- (−) Reconciliation job ek altyapı (ShedLock ile tek-çalıştırma) gerektirir — F5-C kapsamında.
