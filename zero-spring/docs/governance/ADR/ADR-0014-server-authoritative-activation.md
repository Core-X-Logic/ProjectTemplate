# ADR-0014: Server-authoritative aktivasyon (istemci çağrısı durum değiştirmez)

- **Durum:** Accepted · **Tarih:** 2026-07-18

## Bağlam
Ödeme akışlarında sık görülen bir kurulum, son adımı istemciye bağlar: sağlayıcı ödemeyi onaylar, ama
**asıl paket yükseltmesi** tarayıcının dönüş çağrısıyla ("ödeme başarılı, beni yükselt") gerçekleşir.
Kullanıcı ödeme sonrası sekmeyi kapatırsa sonuç şudur: para tahsil edilmiştir, **hizmet açılmamıştır**.
Telafi eden bir arka plan işi yoksa bu durum kendiliğinden düzelmez ve yalnızca müşteri şikâyet
ettiğinde fark edilir.

## Karar
Abonelik durumu **yalnız sunucu tarafında** değişir:
1. **Birincil:** webhook (`ADR-0011` idempotency ile).
2. **Yedek:** reconciliation job — `PENDING_PAYMENT`/askıda kalan ödemeleri sağlayıcıdan
   `BillingProvider.fetchStatus()` ile doğrular ve tamamlar (ADR-0010).

İstemcinin "ödeme başarılı döndüm" çağrısı **hiçbir durum değiştirmez**; yalnız UI yönlendirmesi içindir.

## Gerekçe
Ödeme alınıp hizmetin açılmaması, bir SaaS'ta en pahalı hata sınıfıdır (para iadesi + destek + güven kaybı).
İstemci ağı/sekmesi güvenilir bir taşıyıcı değildir.

## Sonuçlar
- (+) Tarayıcı kapansa da abonelik açılır.
- (+) Webhook kaybında reconciliation ikinci savunma hattı.
- (−) Reconciliation job ek altyapı gerektirir: çok-instance'ta tek çalışması için ShedLock (bkz. `SubscriptionLifecycleJob`).
