# C) UYGULAMA PLANI — 4 Faz (iteratif)

Strateji: **incremental / strangler değil, greenfield parity build** — mevcut .NET sistemi üretimde
değiştirilmiyor; Spring tarafı fonksiyonel pariteye ulaşana kadar paralel geliştirilir, veri
migration'ı en sonda tek seferlik ETL ile yapılır (gerekçe ANALYSIS.md §4).

---

## Kapsam kararları (2026-07-17 onaylandı)

Netleştirme sorularının (ANALYSIS §3.3) yanıtları kapsamı aşağıdaki gibi sabitledi:

| Karar | Sonuç | Fazlara etkisi |
|---|---|---|
| **Frontend** | **Angular devam** (React değil) | Faz 4 frontend = Angular; `abp-ng2-module` yerine ince ABP-bağımsız soyutlama katmanı + `openapi-generator` typescript-angular proxy'leri |
| **SaaS ticari katman** | **Tam parite** (edition/feature/subscription/Stripe/PayPal/fatura) | **Yeni Faz 5** olarak eklendi (büyük blok; F2-F4 platform paritesinin üstüne) |
| **Veri migration** | **Evet, migrate edilecek** | ETL Faz 6'da: şifre hash köprüsü (Identity v3 PBKDF2 → BCrypt re-hash), Windows→IANA TZ, `SimpleStringCipher` decrypt/re-encrypt, BinaryObject→S3 |
| **Chat + friendships** | **Kullanımda** | Faz 3 kapsamına alındı (opsiyonel değil) |
| **Kapsam DIŞI** | LDAP + harici OAuth login, GraphQL, dynamic entity properties, MAUI, Web.Mvc admin UI, Web.Public landing | Bu kalemler üretilmez; auth yalnız şifre-tabanlı (2FA güvenlik önerisi olarak F4'te açık kalır) |

Revize faz haritası: **F2** User/Role/OU/Audit/Settings/e-posta · **F3** Jobs/Notifications/Files/**Chat** ·
**F4** Angular frontend + hardening · **F5** SaaS ticari katman · **F6** Veri migration ETL + cutover.

---

## Faz 1 — İskelet + Auth + Tenant temeli  ✅ (bu repo'da üretildi)

**Kapsam:** Maven projesi (Boot 3.5, Java 21, Modulith), Postgres+Flyway V1, JWT access/refresh
(rotation), RBAC izin sabitleri + method security, tenant çözümleme (X-Tenant) + Hibernate
filter izolasyonu, seed (host admin + default tenant), OpenAPI, docker-compose, CI, Testcontainers IT'leri.

**Kabul kriterleri:**
- [ ] `mvnw verify` yeşil (unit + IT, Testcontainers Postgres)
- [ ] `ApplicationModules.verify()` geçiyor (modül sınırı ihlali yok)
- [ ] Host admin login → me → refresh rotation → eski refresh 401 akışı IT ile kanıtlı
- [ ] Tenant token'ı ile başka tenant verisi OKUNAMIYOR (TenantIsolationIT)
- [ ] İzinsiz endpoint 403, token'sız 401, ProblemDetail (RFC 9457) gövdesiyle
- [ ] Swagger UI çalışıyor; secret'lar yalnız env

**Test kriterleri:** IT coverage: auth akışları %100 senaryo, tenant izolasyonu pozitif+negatif senaryo.

## Faz 2 — User/Role/Permission tam parite + Audit + Settings

**Görevler:**
1. User yönetimi tam: profil, e-posta doğrulama, şifre reset (token'lı), şifre politikası (setting-tabanlı), soft delete (`deleted_at` + Hibernate `@SQLRestriction`), Excel export (Apache POI).
2. Role ekranı paritesi: dinamik izin ağacı endpoint'i (`/api/permissions` tree), rol klonlama, varsayılan rol.
3. Organization Units (ABP OU ağacı: closure table `ou_ancestors`), kullanıcı-OU ataması.
4. Audit modülü: HTTP audit interceptor (süre, kullanıcı, tenant, exception) → `audit_logs`; entity history (Hibernate Envers **veya** custom `PostUpdateEventListener` — karar: Envers, standart) → değişiklik görüntüleme API'si.
5. Settings modülü: `settings(scope[HOST|TENANT|USER], scope_id, key, value)` + tip güvenli erişim (`SettingDefinition` registry) + cache (Redis) + değişiklikte cache evict.
6. Impersonation (`act` claim) + "login as tenant" host akışı. *(Koşullu ek: user delegation — ANALYSIS Soru-5 cevabına bağlı.)*
7. JWT `tenant` claim ↔ `X-Tenant` çapraz doğrulama filtresi.
8. Modulith event registry (JPA outbox) aktivasyonu; `TenantRegistered → tenant admin oluştur` event akışına geçiş.
9. Şablonlu e-posta altyapısı (Thymeleaf HTML şablonları + `JavaMailSender`) — e-posta doğrulama/şifre reset'in ön koşulu (critic gap #7).
10. *(Koşullu: tek eşzamanlı oturum / session-version claim — mevcut `JwtSecurityStampHandler` paritesi, kullanılıyorsa.)*

**Kabul:** AspNetZero'daki Users/Roles/OU/Audit/Settings ekranlarının API paritesi birebir listelenip
her endpoint IT ile; Envers history'de eski/yeni değer + kim + ne zaman; impersonation audit'te actor görünür.
**Test:** IT'ler + permission matrix testi (her endpoint × her izin kombinasyonu — parametrize).

## Faz 3 — Jobs + Notifications + Files

**Görevler:**
1. Quartz JDBC store (clustered) + Flyway ile Quartz şeması; `JobDefinition` registry; job log tablosu + retry politikası; örnek job'lar: kullanıcı listesi Excel (async + bildirimle teslim), expired token temizliği, audit log retention.
2. Notifications: tanım registry'si, abonelik, `notifications` + `user_notifications`, kanal SPI (in-app, e-posta/SMTP — Mailpit ile test); WebSocket (STOMP) push + online kullanıcı takibi (Redis).
3. Files: `StorageProvider` SPI (Local + S3/MinIO), `binary_objects` metadata tablosu, profil fotoğrafı akışı, indirme token'ı (kısa ömürlü imzalı URL), tenant logo/custom CSS saklama (critic gap #3).
4. **Chat + friendships (kapsamda — onaylandı):** STOMP + `friendships` + `chat_messages` (çift-satır `SharedMessageId` modeli korunur), OWASP Java HTML Sanitizer, chat dosya paylaşımı (files modülü ile), online takip Redis.
5. Localization: DB destekli dinamik çeviri + `MessageSource` köprüsü; dil CRUD API'si; tenant çeviri override katmanı.

**Kabul:** job'lar cluster'da tek çalışır (2 instance IT ile), bildirim in-app + e-posta çift kanal teslim,
dosya upload/download tenant-izole, i18n runtime dil ekleme, chat mesaj + read-state iki tarafta tutarlı.
**Test:** Awaitility ile async doğrulama; MinIO + Mailpit Testcontainers.

## Faz 4 — Frontend parite (Angular) + Hardening

**Görevler:**
1. **Frontend = Angular (onaylandı):** mevcut Angular 19 kod tabanından login + tenant switch, dashboard, user/role/OU CRUD, audit görüntüleme, settings, bildirim inbox'ı; `abp-ng2-module` yerine ince ABP-bağımsız oturum/izin soyutlaması; `openapi-generator` typescript-angular ile proxy üretimi (`API_BASE_URL` korunur); SignalR istemcisi → STOMP istemcisine geçiş.
2. Güvenlik sertleştirme: RS256 + JWKS, rate limit (Bucket4j+Redis), security header'ları, Postgres RLS (tenant derin savunma), secret vault, OWASP ZAP baseline CI'da; **2FA (TOTP/e-posta — öneri, kapsam dışı auth ama güvenlik için)**, reCAPTCHA.
3. Observability tamamlama: OTel collector compose'a, Grafana dashboard'ları, alert kuralları örneği.
4. Performans: Gatling yük testi (login, listeleme), N+1 avı (Hypersistence Utils assert), HikariCP tuning, index gözden geçirme.
5. Deployment: Helm chart / compose-prod; blue/green notları; DB migration prod runbook'u.

**Kabul:** Angular admin paneli uçtan uca demo (tenant aç → kullanıcı aç → rol ata → login → audit gör);
ZAP taramasında high yok; p95 login < 300ms @ 100 RPS (lokal referans donanım).

## Faz 5 — SaaS ticari katman (tam parite — onaylandı)

**Görevler:**
1. Edition + feature gating: `editions`, `edition_features`, `tenant_feature_values`; `@RequiresFeature` AOP + feature cache; `MaxUserCount` gibi limit zorlaması.
2. Abonelik yaşam döngüsü: trial, aktif/expired durum makinesi, upgrade/downgrade + proration; `PaymentPeriodType` yerine açık `day_count`; bitiş kontrol job'u (Quartz + ShedLock).
3. Ödeme: Stripe (stripe-java, **Prices API**, `Webhook.constructEvent` imza doğrulama, idempotency key), PayPal (Orders Capture); tenant eşleşmesi `Customer.metadata` (Description konvansiyonu değil).
4. Faturalama: `invoices` + DB sequence tabanlı numara üreteci; PDF üretimi.
5. Tenant self-registration (Free/Trial/Paid): Paid'de tenant ödeme onayına kadar pasif; `TenantRegistered` event akışı.

**Kabul:** edition değişimi feature erişimini anlık değiştirir; Stripe webhook idempotent + imza doğrulanır;
trial→paid geçişi + proration doğru; fatura numarası benzersiz + ardışık.
**Test:** Stripe test-mode webhook simülasyonu (WireMock/Testcontainers), abonelik durum makinesi IT'leri.

## Faz 6 — Veri migration ETL + Cutover

**Görevler:**
1. SQL Server → PostgreSQL kolon eşleme scripti (araç: özel Spring Batch job veya standalone Java ETL).
2. Şifre hash köprüsü: ASP.NET Identity v3 PBKDF2 çözen `PasswordEncoder` (ilk başarılı login'de BCrypt re-hash); host admin eski ABP hash formatı ayrı köprü.
3. Dönüşümler: Windows→IANA timezone, `SimpleStringCipher` decrypt/re-encrypt (connection string + isEncrypted setting'ler), `AppBinaryObjects` DB-blob → S3/MinIO.
4. Permission grant eşlemesi: `AbpPermissions` string grant → yeni authority modeli (hiyerarşik + Host/Tenant side).
5. Doğrulama raporu: tablo bazında satır sayısı + örneklem hash + iş kuralı sondaları; cutover + rollback runbook (ANALYSIS §4.3-4.4).

**Kabul:** staging tam ETL provası doğrulama raporuyla temiz; kritik akış iki sistemde karşılaştırmalı smoke geçer;
rollback penceresi + ters-ETL scripti hazır.
**Test:** ETL idempotency + doğrulama-raporu birim testleri; taşınan örnek tenant ile uçtan uca login/izolasyon IT.

---

## Fazlar arası kurallar

- Her faz sonunda: `mvnw verify` yeşil + Modulith verify + coverage eşiği (QUALITY-GATES.md) + demo.
- Her modül PR'ı: migration geri alınabilirlik notu (Flyway undo YOK — forward-fix stratejisi, gerekçesiyle).
- API sözleşmesi: OpenAPI diff CI'da kırıcı değişiklikte fail (openapi-diff).
