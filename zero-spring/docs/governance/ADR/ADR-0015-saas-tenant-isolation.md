# ADR-0015: SaaS tablolarında Hibernate `@Filter` kullanılmaz; izolasyon host-only izin + explicit sorgu

- **Durum:** Accepted · **Tarih:** 2026-07-18 · **Faz:** F5-A

## Bağlam
Platform tarafında tenant izolasyonu iki katmanlı (ADR-0003): explicit tenant-scoped sorgular (birincil) +
Hibernate `@Filter` (`tenantFilter`/`hostFilter`, ikincil güvenlik ağı). SaaS tabloları (`editions`,
`subscriptions`, `tenant_features`) bu desene birebir uymuyor: **edition'lar host varlığıdır** (tenant_id yok) ve
**host admin tüm tenant'ların aboneliklerini görebilmelidir**. `hostFilter` (`tenant_id is null`) uygulanırsa
host, hiçbir aboneliği göremez — filtre işlevi tersine çalışır.

## Karar
SaaS entity'lerinde `@Filter` **kullanılmaz**. İzolasyon şu üç katmanla sağlanır:
1. **Yetki:** tüm yönetim uçları `Side.HOST` izinleriyle korunur (`editions.*`, `subscriptions.*`,
   `tenantfeatures.manage`) → tenant kullanıcısı bu uçlara hiç erişemez (403).
2. **Tenant-facing tek uç:** `GET /api/subscriptions/me` — tenant **JWT claim'inden** alınır, path/param/body'den
   asla değil.
3. **Explicit sorgu:** servis katmanındaki tüm okuma/yazmalar `tenantId` parametreli.

## Gerekçe
Host'un çapraz-tenant görünürlüğü SaaS yönetiminin **işlevsel gereği**; filtre bunu engellerdi. Yetki katmanı
(host-only izinler) burada birincil savunmadır ve testle kanıtlanmıştır.

## Sonuçlar
- (+) Canlı smoke kanıtı: tenant kullanıcısı `POST /api/editions` → 403, `PUT /api/tenant-features/{id}` → 403,
  `/me` yalnız kendi aboneliğini döndürür. `SaasAuthorizationIT` (8 test) aynı yüzeyi kapsar.
- (−) **Tasarım borcu:** platform genelindeki "ikincil güvenlik ağı" SaaS'ta yok. Yeni bir SaaS ucu yanlışlıkla
  izinsiz açılırsa filtre yakalamaz. Azaltım: her yeni SaaS ucu için `SaasAuthorizationIT`'ye negatif test
  eklemek **zorunlu**; ArchUnit kuralı (saas controller'larında `@PreAuthorize` zorunluluğu) F5-B adayı.
