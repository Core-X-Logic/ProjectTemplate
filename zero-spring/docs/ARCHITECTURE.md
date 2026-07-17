# B) HEDEF MİMARİ — zero-platform (Java 21 / Spring Boot 3.5)

## 1. Mimari stil kararı: Modulith (önce), mikroservis (gerekirse)

**Karar:** Spring Modulith tabanlı modüler monolit.

**Gerekçe:**
- ASP.NET Zero da tek deployable monolittir; parite hedefi için dağıtık sistem karmaşıklığı (network partition, dağıtık transaction, sürümleme) sıfır değer katar.
- Modulith, modül sınırlarını **derleme/test zamanında zorlar** (`ApplicationModules.verify()`): mikroservise geçiş gerektiğinde modüller zaten sözleşme üzerinden konuşuyor olur — "big ball of mud" riski yapısal olarak engellenir.
- Ölçekleme ihtiyacı önce **yatay replikasyon** ile karşılanır (stateless API + Redis + Postgres). Ölçüm (metrics) belirli bir modülün bağımsız ölçeklenmesini gerektirdiğinde o modül event sözleşmesi zaten hazır olduğu için düşük maliyetle ayrılır.
- **Kırılma kriteri (mikroservise geçiş tetikleyicileri):** (a) bir modülün deploy frekansı diğerlerini blokluyorsa, (b) CPU/IO profili asimetrikse (örn. rapor üretimi), (c) ekip sayısı > 2 takım ve kod sahipliği çakışıyorsa.

## 2. Katmanlar ve sınırlar

Her modül içinde hexagonal düzen:

```
com.mycompanyname.zero
├─ shared/          → paylaşılan çekirdek (OPEN modül): temel entity, hata modeli, TenantContext
├─ config/          → çapraz kesit konfig (JPA auditing, OpenAPI, cache, JWT props)
├─ tenancy/         → tenant yaşam döngüsü + çözümleme + Hibernate filter aktivasyonu
├─ identity/        → kullanıcı/rol/izin + kimlik doğrulama (JWT) + oturum
├─ audit/     (F2)  → audit log, entity history (Envers veya custom listener)
├─ settings/  (F2)  → hiyerarşik ayar (Host → Tenant → User)
├─ jobs/      (F3)  → Quartz (JDBC store, clustered) + job kayıt/izleme
├─ notifications/(F3)→ bildirim tanımı, abonelik, in-app inbox, e-posta kanalı, WebSocket push
├─ files/     (F3)  → depolama soyutlaması (lokal disk / S3-MinIO), BinaryObjects paritesi
└─ localization/(F2)→ MessageSource + DB destekli dinamik çeviri
```

Modül içi düzen: `domain/` (entity + iş kuralı), `repo/` (Spring Data port), `web/` (REST adapter + record DTO), servis sınıfı (application service). Modüller arası çağrı **sadece public API sınıfları** üzerinden (Modulith bunu doğrular); asenkron akışlar `ApplicationEventPublisher` + Modulith event publication registry (transactional outbox, `spring-modulith-starter-jpa` ile event tablosu — F2'de aktive edilir).

## 3. Multi-tenancy yaklaşımı

| Konu | Karar | Gerekçe |
|---|---|---|
| İzolasyon modeli | Paylaşımlı şema + `tenant_id` discriminator | AspNetZero varsayılanıyla parite; operasyonel maliyet en düşük; tenant sayısı binlere ölçeklenir |
| Zorlama | Hibernate `@Filter` (tenantFilter/hostFilter) — servis katmanında AOP ile otomatik aktive | Sorgu başına unutma riskini kapatır; explicit repository imzaları ikinci savunma hattı |
| Derin savunma (F4) | PostgreSQL Row-Level Security (`SET app.tenant_id` + RLS policy) | Uygulama katmanı bypass edilse bile DB izolasyonu korur — AspNetZero'da OLMAYAN iyileştirme |
| Çözümleme | `X-Tenant` header (API) → F4'te subdomain desteği | SPA + API senaryosunda header en net; JWT'deki `tenant` claim'i ile çapraz doğrulanır |
| Host kavramı | `tenant_id IS NULL` = host scope | AspNetZero MayHaveTenant paritesi |
| Tenant-başına DB (opsiyon) | Bilinçli olarak alınmadı | AspNetZero'nun ayrı-connection-string özelliği kullanılacaksa F4'te Hibernate multitenancy DATABASE moduna geçiş yolu açık; şimdi YAGNI |

**Kritik güvenlik kuralı:** JWT `tenant` claim ≠ `X-Tenant` header ise istek 403 alır (F1'de login tenant'ı header'dan; F2'de bu çapraz kontrol filtrede zorlanır — bkz. IMPLEMENTATION-PLAN kabul kriterleri).

## 4. Kimlik ve yetkilendirme

- **Token:** kısa ömürlü access JWT (15 dk, HS512 → F4'te RS256 + JWKS endpoint'i) + tek kullanımlık rotate-eden refresh token (7 gün, DB'de SHA-256 hash).
- **Yetki modeli:** RBAC + izin sabitleri (`users.read` …) — AspNetZero `AppPermissions` paritesi. Spring method security `@PreAuthorize("hasAuthority('users.read')")`. Rol → izin ataması DB'de; token'a düzleştirilmiş izin seti yazılır (F2'de token şişmesini önlemek için Redis permission-cache + sadece rol claim'i alternatifi ölçülerek değerlendirilir).
- **Policy bazlı:** Spring `AuthorizationManager` ile özel policy'ler (örn. "kendi tenant'ının kaydı"), `@PreAuthorize` SpEL + servis içi domain kontrolleri.
- **OIDC (opsiyonel):** `spring-boot-starter-oauth2-client` ile Google/Microsoft harici login F4'te; kurumsal senaryoda Keycloak federasyonu için mimari hazır (resource-server zaten standart Bearer akışı).
- **Lockout:** 5 hatalı deneme → 5 dk kilit (DB alanları), AspNetZero paritesi.
- **Impersonation (F2):** ayrı kısa ömürlü token, `act` (actor) claim'i ile — audit'te gerçek kullanıcı izlenir.

## 5. Veri modeli (çekirdek, F1)

```
tenants(id, name UQ, display_name, active, audit kolonları)
users(id, tenant_id NULL→host, username, email, password_hash, active,
      failed_login_attempts, lockout_end_at, audit) UQ(tenant_id, username) NULLS NOT DISTINCT
roles(id, tenant_id, name, is_static, audit) UQ(tenant_id, name)
role_permissions(role_id, permission)          — izinler string sabit
user_roles(user_id, role_id)
refresh_tokens(id, user_id, token_hash UQ, expires_at, revoked, created_at)
```
F2+: `audit_logs`, `entity_change_sets/entity_property_changes`, `settings(scope, scope_id, key, value)`,
`notifications`, `user_notifications`, `background_job_logs`, `binary_objects`, `dynamic_translations`.
Tüm evrim **Flyway versioned migration** ile; `ddl-auto=validate` (şemanın tek kaynağı SQL).

## 6. Event akışları

- Modül içi: senkron çağrı. Modüller arası yan etki: **domain event** (`UserCreatedEvent`, `TenantRegisteredEvent`…) → Modulith event registry (outbox tablosu) → `@ApplicationModuleListener` (async + yeni tx).
- Örnek: `TenantRegistered` → identity modülü tenant admin kullanıcısını açar; notifications modülü hoş geldin bildirimi üretir. Publisher hiçbirini bilmez.
- **Kafka/RabbitMQ:** F1-F3'te YOK (gerekçe: tek deployable içinde broker, operasyon yükü + eventual consistency maliyeti getirir, değer katmaz). Dış sistem entegrasyonu veya modül ayrışması başladığında Modulith event externalization (`spring-modulith-events-kafka`) ile outbox'tan Kafka'ya köprü — kod değişikliği minimal.

## 7. Gözlemlenebilirlik

| Sinyal | Araç | Not |
|---|---|---|
| Metrics | Micrometer → Prometheus (`/actuator/prometheus`) | JVM, HTTP, Hikari, cache + iş metrikleri (login sayacı, tenant başına istek) |
| Tracing | Micrometer Tracing → OTel bridge → OTLP (F4'te collector + Tempo/Jaeger) | trace-id log'lara MDC ile düşer |
| Logging | Logback; dev=pretty, prod=**JSON** (logstash encoder); alanlar: trace_id, tenant_id, user_id | AspNetZero'daki Log4Net düz dosyasından belirgin iyileştirme |
| Health | Actuator liveness/readiness probe | K8s-hazır |
| Audit | Ayrı `audit` modülü — HTTP istek audit'i (F2) + entity history | Metrikten bağımsız, ticari kayıt |

## 8. Güvenlik varsayılanları

- Stateless API, CSRF kapalı (cookie yok), CORS explicit allowlist (config).
- Secrets sadece env/vault (`JWT_SECRET`, `DB_PASSWORD`); repo'da yalnız belirgin dev-default.
- Bcrypt(12); şifre politikası (uzunluk/karmaşıklık) F2'de setting-tabanlı — AspNetZero paritesi.
- Rate limit (F4): Bucket4j + Redis — login ve refresh endpoint'lerine sıkı limit.
- Security header'ları (F4): HSTS, X-Content-Type-Options, CSP (Swagger hariç).
- OWASP bağımlılık taraması CI'da (`dependency-check` veya GitHub Dependabot + `mvn versions`).

## 9. Frontend stratejisi (özet — karşılaştırma ANALYSIS.md'de)

- **A) Angular devamı:** mevcut ekip bilgisi + Metronic teması + nswag proxy'leri yeniden üretilebilir → en hızlı parite. Backend API'si AspNetZero DTO şemasına birebir değil; proxy'ler OpenAPI'den (`openapi-generator` typescript-angular) türetilir.
- **B) React + Next.js:** daha geniş işe alım havuzu, SSR/edge, shadcn/ui gibi modern bileşen ekosistemi; ama TÜM admin ekranlarının yeniden yazımı = büyük efor.
- **Öneri:** F4'te **B (React/Next.js)** ile minimum admin panel (login, tenant switch, user/role/permission CRUD, audit görüntüleme) — çünkü bu yeniden yazım her durumda proxy üretimi hariç sıfırdan; madem yazılacak, modern stack'e yazılır. Angular'a yatırım sadece mevcut ekip %100 Angular ise mantıklı (o durumda A'ya dön).

## 10. "Neden bu seçimler" — özet karar kaydı (ADR özeti)

1. **Modulith > mikroservis:** parite monolit; sınırlar test ile zorlanıyor; geri dönüşü olmayan karmaşıklık ertelendi.
2. **PostgreSQL > SQL Server:** lisans maliyeti sıfır, RLS ile tenant derin savunması, `NULLS NOT DISTINCT` gibi doğrudan işe yarayan özellikler, bulutta her yerde yönetilen sürüm.
3. **Flyway > Liquibase:** düz SQL, ekip için okunabilirlik; EF migration zihniyetine en yakın.
4. **Quartz (JDBC, clustered) > Hangfire karşılığı arayışı:** Spring yerlisi, replikasyonda tek-tetikleme garantisi; dashboard ihtiyacı F3'te job log tablosu + admin ekranı ile.
5. **Kendi JWT üretimi (Nimbus) > Keycloak zorunluluğu:** AspNetZero'daki gömülü auth paritesi; dış IdP eklemek resource-server mimarisinde ek maliyetsiz.
6. **Redis:** cache + rate limit + (F2) permission cache; F1'de bağımlılık hazır, compose'da var.
7. **Records + manuel mapping > MapStruct (F1):** üretilen kod az; DTO'lar küçük; codegen zinciri kısa. Modül sayısı büyüyünce MapStruct F2'de eklenebilir.
