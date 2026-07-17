# ADR-0003: Tenant izolasyonu — shared-DB + Hibernate @Filter + JWT-claim otoriter

- **Durum:** Accepted
- **Tarih:** 2026-07-17
- **Faz:** F1 (F4'te RLS ile derinleşir)

## Bağlam

ABP `IMustHaveTenant`/`IMayHaveTenant` örtük filtreleriyle tenant izolasyonu sağlıyor. Bu görünmez
davranış Spring'de açıkça kurulmazsa **tenant verisi sızıntısı** riski var (RISK-REGISTER R-01, en kritik).

## Karar

1. **Depolama:** paylaşımlı şema + `tenant_id` discriminator (`tenant_id IS NULL` = host scope).
2. **Zorlama (birincil):** servis katmanı explicit tenant-scoped repository sorguları (`TenantContext`).
3. **Zorlama (ikincil savunma):** Hibernate `@Filter(tenantFilter/hostFilter)` + AOP (`HibernateTenantFilterAspect`,
   `@Order` ile transaction içinde) otomatik aktivasyon.
4. **Otorite:** authenticated isteklerde tenant = **JWT `tenant` claim** (`AuthenticatedTenantFilter`).
   `X-Tenant` header ile claim uyuşmazlığı / eksik header / yabancı tenant → **403**. Header yalnız
   login/refresh (henüz token yok) için tenant belirler.
5. **F4 derin savunma:** PostgreSQL Row-Level Security (`SET app.tenant_id` + policy).

## Gerekçe

- Header'ı otorite yapmak (ilk taslak) privilege-escalation açığıydı (tenant token'ıyla host erişimi);
  JWT-claim otoriter yapılınca kapandı — `TenantEscalationIT` ile kanıtlı.
- `@Filter` + AOP tek başına yeterli değil (`findById`/lazy-collection'a uygulanmaz) → birincil savunma
  explicit sorgular; aspect güvenlik ağı.

## Sonuçlar

- (+) Çok katmanlı izolasyon; kanıtlanmış (F1 IT: TenantIsolationIT 4/4, TenantEscalationIT 3/3).
- (−) `@Filter`'ın `findById`'ye uygulanmaması artık risk (R-08, F2'de `@FilterJoinTable`/ArchUnit ile azaltılır).
- (−) Tenant-başına ayrı DB kullanan tenant varsa ayrı karar gerekir (şu an YAGNI; F4'te DATABASE moduna yol açık).
