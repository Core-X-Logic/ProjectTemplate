# ADR-0003: Tenant izolasyonu — shared-DB + Hibernate @Filter + JWT-claim otoriter

- **Durum:** Accepted
- **Tarih:** 2026-07-17

## Bağlam

Çok kiracılı bir sistemde en pahalı hata sınıfı **tenant verisi sızıntısıdır**: sessizdir, geç fark
edilir ve tek bir unutulmuş `where` yeterlidir. İzolasyonun nerede zorlandığı (framework'ün örtük
davranışı mı, açık kod mu) ve **hangi girdinin otorite** olduğu açıkça seçilmelidir.

## Karar

1. **Depolama:** paylaşımlı şema + `tenant_id` discriminator (`tenant_id IS NULL` = host scope).
2. **Zorlama (birincil):** servis katmanı explicit tenant-scoped repository sorguları (`TenantContext`).
3. **Zorlama (ikincil savunma):** Hibernate `@Filter(tenantFilter/hostFilter)` + AOP (`HibernateTenantFilterAspect`,
   `@Order` ile transaction içinde) otomatik aktivasyon.
4. **Otorite:** authenticated isteklerde tenant = **JWT `tenant` claim** (`AuthenticatedTenantFilter`).
   `X-Tenant` header ile claim uyuşmazlığı / eksik header / yabancı tenant → **403**. Header yalnız
   login/refresh (henüz token yok) için tenant belirler.
5. **Derin savunma (opsiyonel, henüz kurulu değil):** PostgreSQL Row-Level Security (`SET app.tenant_id` + policy).

## Gerekçe

- Header'ı otorite yapmak (ilk taslak) privilege-escalation açığıydı (tenant token'ıyla host erişimi);
  JWT-claim otoriter yapılınca kapandı — `TenantEscalationIT` ile kanıtlı.
- `@Filter` + AOP tek başına yeterli değil (`findById`/lazy-collection'a uygulanmaz) → birincil savunma
  explicit sorgular; aspect güvenlik ağı.

## Sonuçlar

- (+) Çok katmanlı izolasyon; testle kanıtlı (`TenantIsolationIT`, `TenantEscalationIT`).
- (−) `@Filter` `findById`'ye uygulanmaz — bu yüzden birincil savunma explicit sorgulardır; yeni bir
  repository metodu eklerken tenant scope'u elle vermek **zorunludur**.
- (−) Tenant-başına ayrı veritabanı gerekirse ayrı bir karar gerekir (şu an YAGNI).
