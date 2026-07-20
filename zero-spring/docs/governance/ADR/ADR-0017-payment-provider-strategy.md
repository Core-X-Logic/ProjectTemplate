# ADR-0017: Ödeme sağlayıcı stratejisi — TR pazarı: PayTR + iyzico; Stripe uykuda

- **Durum:** Accepted · **Tarih:** 2026-07-20

## Bağlam
Ürünün mevcut hedef pazarı Türkiye. Yerel kart tahsilatında fiili seçenekler PayTR ve iyzico'dur;
Stripe Türkiye'de yerleşik satıcılara doğrudan hizmet vermez. P2-A diliminde kurulan
`BillingProvider` SPI'ı (ADR-0010) tek sağlayıcı varsayımıyla bağlanmıştı
(`ObjectProvider<BillingProvider>`); birden fazla sağlayıcı, id ile anahtarlanan bir kayıt defteri
(`BillingProviderRegistry`) gerektirir.

## Karar
1. **Aktif kapsam: PayTR (bu dilim, P2'-A) + iyzico (SONRAKİ dilim).** Her sağlayıcı kendi
   `zero.billing.<provider>.enabled` bayrağı arkasında ayrı bean; webhook'lar sağlayıcı başına
   **ayrı ve tam (exact) path** (`/api/billing/webhook/stripe`, `/api/billing/webhook/paytr`) —
   wildcard grant yok, dört anonim-uç kaydı (permitAll + `@EndpointPolicy` + Rule 5 listesi +
   `zero.ratelimit.paths`) sağlayıcı başına ayrı ayrı yapılır.
2. **Stripe = UYKUDA adaptör.** `StripeBillingProvider` gelecekteki küresel pazar için yer tutucu
   olarak kalır; üzerinde YENİ davranış geliştirilmez, yeni Stripe testi yazılmaz. Mevcut Stripe-yolu
   testleri paylaşılan çekirdeğin (dedup + tek-transaction + PAID koruması) regresyon bekçisidir.
   Bu dilimde Stripe koduna dokunuş yalnız mekaniktir (registry entegrasyonu).
3. **PayPal kapsam dışı** — açıkça istenmedikçe eklenmez.
4. **Para çekirdekte minor-unit-güvenli kalır** (`BigDecimal` snapshot; dönüşüm YALNIZ adaptör
   kenarında). Gerekçe üç ayrı tutar biçimidir ve üçü de aynı sayının farklı yazımıdır:
   - PayTR iFrame API: **kuruş TAMSAYI** (`9.99` → `999`);
   - PayTR Direkt API: **ondalık string** (`"9.99"`);
   - iyzico: **ondalık** (`9.99`).
   Biçim dönüşümü çekirdeğe sızarsa, bir sağlayıcıda doğru olan diğerinde 100 kat fatura demektir
   (Stripe'ın zero-decimal riski PROD-R39 ile aynı sınıf). `PayTRBillingProvider.toKurus`
   `longValueExact` ile kuruş-altı kesiri gürültülü patlatır.

## PayTR'a özgü sözleşmeler (ölçülen/uygulanan)
- **"OK" gövde sözleşmesi — tahsilatın kendisi.** PayTR bildirimi yalnız **literal düz-metin
  `OK`** gövdesiyle kapanır; başka HERHANGİ bir gövde (JSON sarmalayıcı, ProblemDetail, `ok\n`)
  "teslimat başarısız" okunur ve **para esnafa AKTARILMAZ**. Mükerrer teslimat da `OK` alır (dedup
  isabeti dahil). Geçersiz hash ise bilerek `OK` DEĞİL, 400'dür: doğrulanmamış bildirimi onaylamak,
  kimsenin ödediğini kanıtlamadığı parayı onaylamaktır. Byte-eşitliği `PayTRWebhookIT` mutasyon
  kanıtıyla sabitlenmiştir (`"ok\n"` mutasyonu → kırmızı).
- **Event id yok.** Dedup anahtarı `merchant_oid + ":" + status` (dokümana göre yalnız İLK bildirim
  bağlayıcı). Çelişen ikinci status kendi satırını alır; durum tutarlılığını `PAID` koruması ve
  `NOT_PAID→FAILED` kuralı sağlar (geç "failed" tahsil edilmiş aktivasyonu GERİ ALMAZ).
- **İki HMAC formülü, tuz pozisyonu FARKLI:** bildirimde `merchant_oid + salt + status +
  total_amount`; token'da mesaj SONUNA eklenir. Offline vektörlerle sabit
  (`PayTRTokenRequestTest`, bağımsız Python HMAC ile üretilmiş beklenen değerler).
- **Taşıma biçimi form-urlencoded** olduğundan throttle katmanı formata alan-bazlı kimlik okuma
  öğrendi (`RequestBodyFormats.isAccountable` + `extractFormUsername`, D1 kuralı gereği aynı
  commit'te) ve gövde-önbelleği sarmalayıcısı form POST'larda parametre API'sini de önbellekten
  yanıtlar (`CachedBodyHttpServletRequest` — aksi ölçüldü: 400 "Required request body is missing").

## Sonuçlar
- (+) Yeni sağlayıcı = yeni adaptör + kayıt defteri girdisi; çekirdek (dedup, tek-transaction,
  sunucu-otoriter aktivasyon) sağlayıcıdan bağımsız kaldı ve Stripe testleri bunu bekçiliyor.
- (+) Checkout isteği `provider` alanıyla sağlayıcı seçer; tek sağlayıcılı kurulumda alan atlanabilir.
- (−) PayTR retry takvimi belgesizdir → mutabakat runbook'u (§3.9) PayTR paneline genişletildi
  (PROD-R41); iyzico dilimi ve sorguyla-mutabakat job'u backlog'da (PROD-R43).
- (−) get-token canlı çağrısı test edilmez (PROD-R37 deseni) ve alıcı kimlik alanları henüz
  modellenmedi (PROD-R44).
