# Risk Register + Mitigasyon Takvimi

Skala: Olasılık (L/M/H) × Etki (L/M/H) → Seviye. Durum: `Open` · `Mitigating` · `Closed`.

---

## ⚠️ Şablonu klonluyorsanız — devraldığınız açık kısıtlar

Aşağıdaki kayıtların çoğu bu şablonun **kendi geçmişidir** ve sizi bağlamaz. Ama şunlar
**hâlâ açıktır** ve klonunuza aynen geçer. Kabul edip etmediğinize karar verin:

| ID | Ne | Neden önemli |
|---|---|---|
| **PROD-R21** | `/api/users` **bellekte sayfalıyor** (`@EntityGraph` + `Pageable`, `HHH90003004`) | 5 kullanıcıda görünmez; tek kiracıda 50k kullanıcıda her sayfa isteği tüm seti heap'e çeker. Aynı desen `RoleRepository`'ye **kopyalanmış** durumda — yeni entity'lere taşımayın |
| **PROD-R23** | Branch protection **ücretsiz planda kurulamıyor** (403) | CI zinciri raporlar ama **kırmızı check push'u engellemez**. Blokaj insan disiplininde. `SETUP-NEW-PROJECT.md` §2 |
| **PROD-R27** | Dockerfile'ı **hiçbir gate build etmiyor** | İmajdaki sertleştirmeler (prod profili, heap tavanı, healthcheck) hiçbir otomatik kontrolde doğrulanmıyor |
| **PROD-R6** | Rate limit bucket'ları **JVM-local** | N replika = N × limit. Ayrıca istemci kimliği `X-Forwarded-For`'a dayanır: **proxy'nin bu başlığı ezmesi zorunludur**, kodla garanti edilemez |
| **PROD-R16** | `kid` / anahtar rotasyonu / access-token iptali **yok** | Rotasyon tüm oturumları düşürür; access token 15 dk boyunca iptal edilemez |
| **Issue #1** | `POST /api/tenants` **admin kullanıcı oluşturmuyor** | Açılan kiracıya giriş yapılamaz. Self-registration akışının ön koşulu |
| **R-30** | 31 ham `hasAuthority('...')` literali (`identity`, `audit`, `settings`) | `'users.raed'` derlenir, test geçer, endpoint sonsuza dek 403 döner. Doğru örnek: `saas` modülü |
| **R-31** | `ROLES_MANAGE` izin **ağacında yok** | `AppPermissions.all()` 22, `PermissionDefinitions` 21 döndürüyor. Admin'e veriliyor ama UI'da görünmüyor ve başka role atanamıyor. Hizalama testi yalnız `saas`'ı kapsıyor |
| **R-32** | `Tenant` entity history'sinin **uçtan uca IT'si yok** | `EntityChangeTrackingTest` sınıf referansıyla bağlıyor, ama HTTP seviyesinde kanıt yok |
| **R-33** | Şifre alt sınırı **iki uçta farklı**: `ResetPasswordRequest` min **6**, `ChangePasswordRequest` min **8** | `PasswordPolicy.DEFAULT_REQUIRED_LENGTH = 6`. Yani sıfırlama politikayla uyumlu, **değiştirme onu eziyor**: kiracı politikayı 6'ya ayarlasa bile şifre değiştirme 8 dayatır — kimsenin bakmadığı bir DTO anotasyonundan, yapılandırmayı sessizce geçersiz kılarak. **Düzeltme yönü ürün kararı:** (a) ikisini de `PasswordPolicy`'ye devret, (b) ikisini 6'da hizala, (c) politika varsayılanını 8'e çıkar |
| **R-34** | `GET /api/tenants` **sayfalanmıyor** (`List<TenantDto>`), `PUT /api/tenants/{id}` ve `DELETE` **yok** | Kiracı adı oluşturulduktan sonra değiştirilemiyor. UI bunu kullanıcıya söylüyor (çalışmayan düğme koymak yerine), ama uç eksikliği sürüyor |

Kapatılmış maddeler ve tarihsel kayıt aşağıda; `docs/history/` altındaki arşiv de bu
kararların ham gözlem tabanıdır.

---

| ID | Risk | Olas. | Etki | Seviye | Durum | Mitigasyon | Ne zaman |
|---|---|---|---|---|---|---|---|
| R-01 | ABP örtük tenant filtresi Spring'de kurulmazsa tenant veri sızıntısı | M | H | **Kritik** | **Closed** | Hibernate @Filter + AOP + JWT-claim otoriter (ADR-0003); TenantIsolationIT/TenantEscalationIT + canlı 403 smoke | F1 ✅ |
| R-02 | Zayıf token varsayılanları (HS256, 1g/365g) | H | H | **Kritik** | **Closed** | 15dk access + rotate refresh + reuse kaskadı (ADR-0004) | F1 ✅ |
| R-03 | `SimpleStringCipher` sabit passphrase (enc_auth_token/connstr/settings) | M | H | Yüksek | Open | ETL'de decrypt/re-encrypt; WS auth yeniden tasarım | F6 (ETL) / F3 (WS) |
| R-04 | Çift şifre hash formatı (ABP + Identity v3 PBKDF2) | H | M | Yüksek | Open | Köprü PasswordEncoder → ilk login'de BCrypt re-hash | F6 |
| R-05 | SQL Server→PG tip + Windows→IANA timezone eşleme | H | M | Yüksek | Open | ETL kolon eşleme + TZ dönüşüm scripti + doğrulama raporu | F6 |
| R-06 | Access token TTL boyunca (≤15dk) iptal edilemez | M | M | Orta | Mitigating | Kısa TTL; gerekirse Redis jti denylist | F4 (koşullu) |
| R-07 | ABP API zarfı ile uyumsuzluk → frontend veri katmanı köprüsü | M | M | Orta | **Closed** | Yeni backend RFC9457 ProblemDetail + düz JSON; openapi-typescript typed client (42 path) çalışıyor, contract-gate geçti | F2 ✅ |
| R-08 | Hibernate @Filter findById/lazy-collection'a uygulanmıyor (ikincil savunma boşluğu) | M | M | Orta | Mitigating | Birincil savunma explicit tenant sorguları (kanıtlı); @FilterJoinTable + ArchUnit iyileştirme | F3 |
| R-09 | Tüm admin ekranları React'te sıfırdan yazılıyor (efor) | M | M | Orta | Mitigating | Metronic starter layout/shadcn taşındı; ilk vertical slice uçtan uca kapandı; slice C (impersonation/audit/settings UI) kaldı | F2 (devam) / slice C |
| R-10 | Kaynak kod anomalileri (HSTS yalnız Dev, global 2FA cache, System.Random şifre, GraphQL playground açık) | — | — | Bilgi | **Closed** (taşınmıyor) | Anomaliler bilinçli porte edilmez; secret rotasyonu cutover'da | F6 |
| R-11 | Permission grant hiyerarşik semantiği (parent→child, Host/Tenant side) düz authority'ye çevrilemez | M | M | Orta | Mitigating | PermissionDefinitions ağacı + side modeli DONE (PermissionTreeIT); grant verisi ETL eşlemesi kaldı | F2 (model ✅) / F6 (veri) |
| R-12 | Impersonation act-claim güvenliği (cascade, actor audit) | M | H | Yüksek | **Closed** | Tek kullanımlık token + cascade yasağı + audit; ImpersonationIT geçti | F2 ✅ |
| R-13 | Setting fallback zinciri (isInherited istisnaları, client-visibility whitelist) yanlış kopyalanırsa yanlış değer/secret sızıntısı | M | M | Orta | **Closed** | SettingDefinition.visibleToClient + scope zinciri; SettingsIT geçti | F2 ✅ |
| R-14 | Faz 2 kapsam büyük — tek codegen'de verify-yeşil riski | M | M | Orta | **Closed** | 6 ayrık yazıcı + düzeltme turları + adversaryal review; verify yeşil (52 test) | F2 ✅ |
| R-15 | SaaS/ödeme entegrasyon borçları (Stripe legacy Plans API, Customer.Description eşleşmesi) | M | M | Orta | Open (kapsamda F5) | Prices API + metadata eşleşmesi + webhook idempotency | F5 |
| R-16 | Metronic starter'da çift/çakışan bağımlılık (react-query v3+v5, formik+rhf, Windi+Tailwind) | M | M | Orta | **Closed** | app/'te tekilleştirildi: @tanstack/react-query v5 + rhf+zod + Tailwind4; formik/rq3/windicss/notistack atıldı; vendor ham commit edilmedi (ADR-0008) | F2 ✅ |
| R-17 | Frontend-backend API sözleşme kayması (manuel tip) → runtime hata | M | M | Orta | **Closed** | OpenAPI'den typed client codegen (`gen:api`, build adımı) çalışıyor; openapi-diff CI iyileştirmesi F4 | F2 ✅ |
| R-18 | Notifications ilk slice'ta uçtan uca isteniyor ama backend Faz2 sözleşmesinde yoktu | H | M | Orta | **Closed** | Inbox backend (V3 + service + 4 endpoint + welcome publish); NotificationInboxIT sahiplik izolasyonu; WebSocket F3 | F2 ✅ |
| R-19 | **False-green:** verify yeşil ama boşluk test edilmediği için yeşil | H | H | **Yüksek** | **Closed** | Adversaryal inceleme her faz zorunlu; 11 boşluk için pozitif parity testi; mutasyon testi F3 adayı | F2 ✅ |
| R-20 | change-password şifre karmaşıklık politikasını atlıyor (`aaaaaaaa` kabul); reset ile tutarsız | H | M | **Yüksek** | **Closed** | ProfileService+AccountService tek yol: PasswordPolicyValidator+history; PasswordPolicyIT kanıtlı | F2 ✅ |
| R-21 | Frontend starter config deprecation'ları (tsconfig baseUrl, import.meta.env tipi) | M | L | Düşük | **Closed** | vite-env.d.ts + tsconfig düzeltmeleri; `tsc -b` strict + build yeşil. (Vendor CSS `@media (max-width: var(...))` uyarısı kozmetik, build'i kırmıyor — R-23'e taşındı) | F2 ✅ |
| R-22 | `mvnw` (POSIX) CRLF nedeniyle bash'te kırık — ubuntu CI'da build patlar | H | M | **Yüksek** | Mitigating | mvnw LF'e çevrildi (LF-only doğrulandı) + `.gitattributes` (eol=lf mvnw/*.sh, crlf *.cmd); repo autocrlf=true idi. **Commit + CI koşusu doğrulaması bekliyor** | F2 kapanış (commit) |
| R-23 | Düşük artıklar: `read-all` endpoint'i testsiz; user_notifications.tenant_id dekoratif (user_id izolasyonu); vendor CSS media-query uyarısı | L | L | Düşük | Open | markAllRead testi + tenant_id semantiği + CSS düzeltme F3 | F3 |
| R-24 | Soft-delete + unique(tenant_id, username): silinen kullanıcının username'i tekrar kullanılamıyor (409); ABP'de silinen username yeniden kullanılabilir | L | L | Düşük | Open | Unique index'e `deleted` dahil et (partial unique where deleted=false) veya silmede username'i mühürle; parity kararı | F3 |
| R-25 | Impersonation cascade yasağı frontend'te yalnız UI-block (component); auth.impersonate() programatik çağrı client'ta re-check etmiyor — backend cascade kuralı otoriter (403 canlı kanıtlı) | L | M | Düşük | Mitigating | Backend authoritative (ImpersonationService + smoke 403); istenirse auth.impersonate guard eklenir | F3 (koşullu) |

## F5 (SaaS ticari katman) riskleri — 2026-07-18 eklendi

| ID | Risk | Olas. | Etki | Seviye | Durum | Mitigasyon | Ne zaman |
|---|---|---|---|---|---|---|---|
| F5-R1 | Modulith döngüsü: feature gating `identity→saas`, izin sabitleri `saas→identity` | M | H | **Yüksek** | Mitigating | `saas :: api` named interface; SaaS izinleri `saas` içinde; `tenancy`'ye saas bağımlılığı yok (event) | F5-A |
| F5-R2 | Feature cache tutarsızlığı (edition/tenant değişince stale değer) | M | M | Orta | Open | Redis cache + yazma yollarında explicit evict + IT kanıtı | F5-B |
| F5-R3 | Tenant kendi feature/limitini yükseltebilir | M | H | **Yüksek** | Mitigating | Tüm SaaS yazma uçları `Side.HOST`; `SaasAuthorizationIT` negatif test | F5-A |
| F5-R4 | Para hassasiyeti (double kullanımı) | L | H | Orta | Mitigating | `BigDecimal` + `numeric(19,4)` + zorunlu currency | F5-A |
| F5-R5 | Ay-sonu/timezone kayması (31 Oca + 1 ay) | M | M | Orta | Open | `java.time.Period` + clamp kuralı + birim test | F5-B |
| F5-R6 | Seeder idempotency tuzağı (host admin varsa seed atlanır → edition seed çalışmaz) | H | L | Orta | Mitigating | Edition seed'i ayrı idempotent adım (edition varlığına bakar) | F5-A |
| F5-R7 | Abonelik geçerlilik kapısı her istekte DB'ye gider | M | M | Orta | Open | Cache'li `SubscriptionGuard`, yalnız tenant-scoped uçlarda | F5-B |
| F5-R8 | Kaynak sistemdeki kritik kusurların kopyalanması (istemci-tetikli aktivasyon, webhook 400-retry, Customer.Description eşleştirme) | M | H | **Yüksek** | Mitigating | ADR-0011/0014 ile açıkça yasaklandı; `F5-SAAS-INVENTORY.md` §11 K1-K16 listesi | F5-C |
| F5-R9 | **Yeni izinler mevcut kurulumda statik Admin rollerine eklenmiyor** — seeder "zaten var → atla"; testler temiz DB kullandığı için false-green. Canlı smoke ile yakalandı: host admin 17/22 izin, `/api/editions` 403 | H | H | **Yüksek** | ⚠️ **Kısmi — dev'de Closed, PROD'DA AÇIK** | Dev/canlı doğrulandı (`reconciled to 22 permission(s)`), **ama** uzlaştırma `zero.seed.enabled` bayrağına bağlı ve prod profilinde seed **kapalı** → prod'da hiç çalışmaz. **Düzeltme (P0-D3):** uzlaştırmayı ayrı bayrağa taşı (`zero.seed.reconcile-permissions`, prod'da default **true**) | F5-B hardening |
| F5-R10 | **Tenant create admin bootstrap yok** — `POST /api/tenants` ile açılan tenant'ta `Admin` rolü ve admin kullanıcısı oluşturulmuyor; tenant giriş yapılamaz halde kalıyor ve izin uzlaştırması onu atlıyor (Faz 1'den beri) | H | M | **Yüksek** | Open — [Issue #1](https://github.com/Core-X-Logic/ProjectTemplate/issues/1) | Provisioning'e statik `Admin` rolü + admin kullanıcı ekle (tek transaction, `tenancy` yaprak kalacak şekilde event/listener ile); create→login→`/me` IT'si | Slice C öncesi (self-registration ön koşulu) |

## F6 (veri migration) erken riskleri — F5 tasarımında azaltıldı

| ID | Risk | Seviye | Durum | Not |
|---|---|---|---|---|
| F6-R1 | Implicit→explicit durum türetme hatası (müşteri erişimi haksız kesilir/açılır) | **Yüksek** | Mitigating | Karar tablosu `F5-ETL-IMPACT.md` §2'de sabitlendi |
| F6-R2 | 30-gün → ay dönüşümünde abonelik süresi kayması | **Yüksek** | Mitigating | P4: `current_period_end_at` doğrudan taşınır, yeniden hesaplanmaz |
| F6-R3 | `ExtraProperties` JSON'dan tutar/edition çıkarma | **Yüksek** | Open | F6 |
| F6-R4 | Feature TPH ayrım hatası → tenant override'ın edition'a yazılması | **Yüksek** | Mitigating | P7: ayrı tablolar (`edition_features`/`tenant_features`) |
| F6-R5 | Gateway metadata migration'ı (Stripe `metadata.tenantId`) unutulursa recurring webhook tenant çözemez | **Yüksek** | Open | F6 cutover; P3: `external_ref`/`provider` kolonları F5-A'da hazır |

## F5-B Production Readiness — P0 release blocker'ları (2026-07-18 denetimi)

Kaynak: 4 paralel salt-okuma denetimi (security / data-migration / observability / performance).
Hepsi **kanıtlı** (dosya:satır). `PROD-Rxx` = prod çıkışını bloklayan bulgu.

> **Not (2026-07-18, kapanış turu):** Aşağıdaki tablo **denetim anındaki** durumu kayıt altında tutar;
> `Durum` kolonu tespit anına aittir ve tarihsel kayıt olarak **değiştirilmemiştir**.
> Güncel durum ve kanıtlar için bkz. [Kapanış turu](#f5-b-p0-kapanış-turu-2026-07-18).

| ID | Bulgu | Kanıt | Etki | Seviye | Durum |
|---|---|---|---|---|---|
| PROD-R1 | **JWT secret dev default'u base config'te commit'li** — `SPRING_PROFILES_ACTIVE=prod` set edilmezse uygulama sessizce **repodaki bilinen anahtarla** token imzalar → herkes host-admin token forge edebilir | `application.yml:34` (prod override `application-prod.yml:5` yalnız prod profilinde) | Tam yetki yükseltmesi, sessiz | **KRİTİK** | Open |
| PROD-R2 | Aynı profil-bağımlılığı `SEED_ADMIN_PASSWORD`'de — profil kaçarsa bilinen şifreli host admin seed'lenir | `application.yml:39-40` | Yetki yükseltmesi | **Yüksek** | Open |
| PROD-R3 | **CORS konfigürasyonu hiç yok** — prod'da ayrı origin'den servis edilen SPA hiçbir API çağrısı yapamaz; acele "wildcard" düzeltme baskısı doğurur | backend'de `.cors(...)` yok; `frontend/app/.env.example:1` cross-origin | Release blocker (işlevsiz) + wildcard riski | **Yüksek** | Open |
| PROD-R4 | **HSTS pratikte gönderilmiyor** — `server.forward-headers-strategy` tanımsız; TLS'i sonlandıran proxy arkasında `isSecure()=false` → HSTS sessizce yazılmaz | `application.yml`/`application-prod.yml` | Downgrade/MITM penceresi | **Yüksek** | Open |
| PROD-R5 | CSP / Referrer-Policy / Permissions-Policy header'ları yok (Spring default'ları yalnız nosniff + frame-options veriyor) | `SecurityConfig.java:42-59`, grep 0 eşleşme | XSS azaltımı yok, referrer sızıntısı | Orta | Open |
| PROD-R6 | **Rate limit / brute-force koruması yok** (lockout var ama IP/uç bazlı limit yok) | grep: Bucket4j/RateLimiter → 0 | Login ve SaaS uçlarında kaba kuvvet / kaynak tüketimi | **Yüksek** | Open |
| PROD-R7 | **Prod'da `spring.cache.type=simple`** (Redis override yok) → çok-instance'ta stale feature/limit → **yanlış yetki/limit uygulanır** | `application.yml:12`, prod'da override yok | Yanlış feature gating | **KRİTİK** | Open |
| PROD-R8 | `cache-names` eksik — Slice B `@Cacheable("features")` ekler eklemez dev/test'te 500 | `application.yml:13` vs `CacheConfig.java:33-34` | Boot/runtime hatası | **Yüksek** | Open |
| PROD-R9 | **HikariCP ayarsız** (varsayılan 10 bağlantı / 30 sn timeout) → yük altında cascade failure | yml'de pool bloğu yok | Kararlılık | **Yüksek** | Open |
| PROD-R10 | **Soft-deleted admin → unique violation → boot loop** (seed yeniden oluşturmaya çalışır) | `V1__baseline.sql` unique(tenant_id, username), soft-delete `deleted` kolonu dışarıda | Uygulama açılmaz | **KRİTİK** (R-24 Düşük→Yüksek) | Open |
| PROD-R11 | `nulls not distinct` **PG15+ zorunlu**, sürüm guard'ı yok ve testte kanıtsız | `V1__baseline.sql`, `V2__phase2.sql` | Eski PG'de migration patlar | **Yüksek** | Open |
| PROD-R12 | Migration **dry-run planı release gate değil**; checksum drift kontrolü yok | süreç | Prod migration sürprizi | **Yüksek** | Open (gate CI'ya eklendi) |
| PROD-R13 | Redis SPOF — `CacheErrorHandler` yok; Redis kesintisi tüm platformu 500'e düşürür | `CacheConfig.java` | Kullanılabilirlik | Orta | Open |
| PROD-R14 | `lower(username)` fonksiyonel index yok → login'de tenant içi seq scan | `V1__baseline.sql:28` | Performans | Orta | Open |
| PROD-R15 | Çoklu replika **seed yarışı** (advisory lock yok); `SaasSeeder` idempotency testi yok; ShedLock `usingDbTime()` yok | `DataSeeder`, `SaasSeeder` | Boot yarışı / saat kayması | Orta | Open |
| PROD-R16 | Key rotation yok (`kid` claim'i yok), `audience` doğrulanmıyor, access token revocation yok (15 dk pencere) | `JwtService.java:66`, `SecurityConfig.java:91-96` | Rotasyon = tüm oturumlar düşer | Orta | Open |

### F5-B P0 kapanış turu (2026-07-18)

Test durumu: **326 yeşil** (236 IT + 90 unit); sertleştirme başlarken 133, ilk P0 turu sonunda 168 idi.
*(Bu satır bir süre "168 yeşil (150 IT + 18 unit)" olarak kaldı ve turu kapatan bağımsız incelemede
yanlış olduğu tespit edildi — register denetim kaydı olarak kullanılıyor, eskimiş sayı bir sonraki turu
yanlış yönlendirir. Gerçek ölçüm: `mvnw clean verify`, `BUILD SUCCESS`, 0 fail / 0 error / 0 skip.)*
Her satırın kanıtı, o bulguyu *özellikle* hedefleyen bir testtir — mevcut testlerin yeşil kalması kanıt sayılmaz.

| ID | Durum | Değişiklik | Kanıt (test) |
|---|---|---|---|
| PROD-R1 | **Closed** | `application.yml`'de `zero.jwt.secret` **default'suz** (`${JWT_SECRET}`); dev/test anahtarları kendi profil dosyalarına taşındı. `JwtSecretValidator`: sızmış default **her profilde** reddedilir, repodaki tüm anahtarlar `prod`'da reddedilir | `JwtSecretValidatorTest` (5 test) |
| PROD-R2 | **Closed** | Base config `${SEED_ADMIN_PASSWORD:}` (boş); `DataSeeder`'daki fail-fast **profil bağımsız** — boş/dev-default şifre `prod` aktif olmasa da reddedilir | `SeedHardeningIT.aBlankSeedPasswordIsRefusedWithoutTheProdProfile`, `...theCommittedDevDefaultPasswordIsRefusedOutsideDevAndTest` |
| PROD-R3 | **Closed** | `CorsConfigurationSource` + `.cors(...)`; origin listesi config'ten, base'de **boş** (fail-closed), prod'da default'suz `${CORS_ALLOWED_ORIGINS}`; `allowCredentials=false` | `CorsPolicyIT` (4 test) |
| PROD-R4 | **Closed** | `server.forward-headers-strategy: framework` + HSTS (1 yıl, includeSubDomains, preload) | `SecurityHeadersIT.hstsIsWrittenWhenTheProxyReportsATlsRequest` (X-Forwarded-Proto ile proxy taklidi) |
| PROD-R5 | **Closed** | CSP (`default-src 'none'`, prod), Referrer-Policy, Permissions-Policy, frameOptions deny | `SecurityHeadersIT.everyResponseCarriesTheHardeningHeaders` |
| PROD-R6 | **Mitigating** | Bucket4j token bucket: IP **ve** kullanıcı adı boyutunda, 4 kimliksiz uçta, 429 + ProblemDetail + Retry-After | `RateLimitIT` (6 test) — **artık risk:** bucket'lar instance-local, N replika = N x limit; bkz. aşağıdaki not |
| PROD-R7 | **Closed** | Zaten kapalıydı (`application-prod.yml` `cache.type=redis`) | — |
| PROD-R8 | **Closed** | `cache-names` gerçek kullanımla hizalandı; kullanılmayan `permission-tree` **silindi** (izin ağacı cache'e hiç uğramıyor) | `PermissionTreeIT`, `SettingsIT`, `FeatureResolutionIT` (mevcut) |
| PROD-R9 | **Closed** | Hikari pool ayarları (max/min-idle/connection-timeout/max-lifetime/leak-detection) base + prod | — (konfigürasyon; davranış testi yok) |
| PROD-R10 | **Closed** | V6: `uq_users_tenant_username` → **partial unique index** (`where deleted = false`), `nulls not distinct` korunarak | `SoftDeletedUsernameReuseIT` (3 test) |
| PROD-R11 | **Closed** | `PostgresVersionGuard` (Flyway `BEFORE_MIGRATE`, V1'den önce) + V6 başında `DO` bloğu. V1/V2'ye **dokunulmadı** (checksum) | `MigrationGuardIT` (3 test) |
| PROD-R12 | **Closed** | **CI `migration-drift` gate'i** (`ci.yml`, GATE 4): önceki sürümün migration seti uygulanır (= mevcut kurulum), sonra bu commit'in seti onun üstüne konur → `validate` (checksum drift), dolu şema üzerine `migrate`, ikinci `migrate` no-op (idempotency), ve jar'ın `ddl-auto=validate` ile yükseltilmiş şemaya karşı boot etmesi | `ci.yml` GATE 4; `MigrationGuardIT` (3), `PostgresVersionGuardTest` |
| PROD-R13 | **Closed** | `CacheConfig implements CachingConfigurer` + `CacheErrorHandler`: Redis hatası 500 yerine cache bypass + WARN | — (hata yolu; enjeksiyon testi yok) |
| PROD-R14 | **Closed** | V6: `ix_users_tenant_lower_username` fonksiyonel index | `SoftDeletedUsernameReuseIT.theHardeningIndexesExist` |
| PROD-R15 | **Closed** | (a) `pg_advisory_xact_lock` — `DataSeeder` + `SaasSeeder`, ortak anahtar; (b) idempotency IT; (c) ShedLock `usingDbTime()` **+ V6'da `shedlock` kolon tipi düzeltmesi** (aşağıdaki nota bakın) | `SaasSeederIdempotencyIT` (2 test), `ShedLockIT` (3 test) |
| PROD-R16 | **Mitigating** | `audience` claim üretiliyor **ve** doğrulanıyor (`JwtAudienceValidator`) | `JwtAudienceIT` (4 test) — `kid`/rotasyon ve access-token revocation **hâlâ açık**, bkz. not |
| F5-R9 | **Closed** | İzin uzlaştırması `zero.seed.reconcile-permissions` bayrağına taşındı (default true, prod dahil); `seed.enabled=false` iken de çalışır | `SeedHardeningIT.reconciliationRunsWhenSeedingIsDisabled`, `...reconciliationCanBeTurnedOffOnItsOwnFlag` |

#### Kapanış turunda ortaya çıkan yeni bulgu: ShedLock `usingDbTime()` + `timestamptz` uyumsuzluğu

`usingDbTime()` açıldığında `ShedLockIT` kırmızıya döndü ve nedeni testin kendisi değildi.
ShedLock'un PostgreSQL server-time SQL'i `timezone('utc', CURRENT_TIMESTAMP)` üretir — bu bir
**`timestamp` (tz'siz)** UTC duvar saatidir. `V5__shedlock.sql` ise kolonları proje konvansiyonuna
uyarak `timestamptz` tanımlamıştı. Tz'siz bir değeri `timestamptz` kolona yazmak, PostgreSQL'in onu
**yazan oturumun** (yani o node'un JVM'inin) zaman diliminde yorumlaması demektir. Ölçüldü: bir
`Europe/Istanbul` JVM her kilidi **3 saat geçmişe** yazıyordu. Tek node'da karşılaştırmalar kendi
içinde tutarlı olduğu için sessiz kalır; **farklı zaman dilimlerindeki iki node** ise birbirinden
farklı instant yazar ve karşılıklı dışlama sessizce çalışmaz — yani `usingDbTime()`'ın çözmesi
beklenen sorunun ta kendisi geri gelir.

Düzeltme V6'da: `shedlock.lock_until` / `locked_at` → `timestamp` (tz'siz), `at time zone 'utc'`
ile veri koruyarak. Bu iki kolon, projenin "her yerde `timestamptz`" kuralına **bilinçli ve
belgelenmiş** istisnadır; gerekçe hem `V6__hardening.sql` hem `SchedulingConfig` içinde yazılıdır.
`ShedLockIT` artık karşılaştırmaları veritabanı içinde yapar (JVM saatiyle kıyas yok).

#### Kapanmayan / kabul edilen artık riskler

- **PROD-R6 (rate limit) — çok-instance:** Bucket'lar `ConcurrentHashMap`'te, JVM-local. N replika
  toplamda N x limit'e izin verir. Sınırsız bir sel yerine limitin küçük bir katına indiği için
  koruma anlamlıdır ve yeni altyapı gerektirmez. Paylaşımlı sayaç = Bucket4j Redis/Hazelcast
  backend'i; anahtar türetimi (`RateLimitFilter.bucketFor`) bunu tek noktada değiştirilebilir
  bırakacak şekilde yazıldı. Ayrıca istemci kimliği `X-Forwarded-For`'a dayanır — bu, HSTS ile
  **aynı** güven sınırıdır: istemcinin gönderdiği `X-Forwarded-*` başlıklarını ezen bir proxy arkasında
  çalışmak zorunludur.
- **PROD-R16 (`kid` / key rotation):** Yapılmadı. Gerekçe: `kid` tek başına rotasyonu çözmez —
  anlamlı olması için decoder'ın **aynı anda birden çok anahtarı** kabul etmesi (eski + yeni),
  yani çok anahtarlı bir `JWKSource` ve anahtarların konfigürasyondan bir set olarak okunması gerekir.
  Bu, tek anahtarlı `zero.jwt.secret` sözleşmesini değiştiren bir tasarım işidir ve "faz dışı yeni
  özellik ekleme" kısıtına girer. `audience` doğrulaması bu turda kapatıldı; `kid` + çok anahtarlı
  decoder ve access-token revocation (15 dk pencere) **açık** kalır.
- **PROD-R9 / PROD-R13:** Kodda kapalı, ancak davranışsal testi yok (pool tükenmesi ve Redis kesintisi
  enjekte etmeyi gerektirir). Konfigürasyon ve hata yolu kod incelemesiyle doğrulandı.

### Adversaryal turlar B–F ve kapanış turu (2026-07-18)

> **Kayıt düzeltmesi.** Aşağıdaki 30 bulgu bir süre yalnızca kod yorumlarında ve commit mesajlarında
> yaşadı; register'da satırları yoktu. Bu, register'ın denetim kaydı olma işlevini bozar — bu yüzden
> geriye dönük olarak buraya alındılar. Hepsi kapalı; kanıt sütunu ya test adı ya canlı ölçümdür.
> Her tur, bir öncekinin düzeltmesini **saldırgan gözüyle yeniden inceleyerek** açıldı.

| Tur | Bulgu | Durum | Kanıt |
|---|---|---|---|
| B1 | Yüzde-kodlu yol (`/api/auth/%6Cogin`) throttle'ı atlıyor; context-path kırılması | **Closed** | `ThrottledPathMatcherTest`, `ContextPathRateLimitIT` |
| B2 | Aşırı gövde → username çıkarımı atlanıyor → bucket bypass (20 KB pad) | **Closed** | `RateLimitBypassIT`; canlı 20 KB login → **413** |
| B3 | `X-Forwarded-For`'un **en solu** okunuyor → çağıran kendi bucket'ını seçiyor | **Closed** | `ClientAddressResolver` (sağdan `trusted-proxy-count`), `RateLimitBypassIT` |
| B4 | `zero.seed.enabled` default `true` → profil kaçarsa bilinen admin seed'lenir | **Closed** | `SeedProfileDefaultTest`, `SeedHardeningIT` |
| B5 | `PostgresVersionGuard` `SQLException`'ı yutuyordu → **fail-open** | **Closed** | `PostgresVersionGuardTest` |
| B6 | `/v3/api-docs` her profilde anonim → 54 route + DTO keşfi | **Closed** | `ApiDocsExposureIT`, `ProdApiDocsExposureIT` |
| B7 | `maven-wrapper.properties` **BOM'lu** → `./mvnw` POSIX'te kırık → CI hiç build edemezdi | **Closed** | `MavenWrapperEncodingTest` (R-22 de kapandı) |
| B8 | CORS property doğrulaması yok | **Closed** | `CorsPropertiesValidationTest` |
| C1 | Limiter'ın format envanteri yanlış (`+json` son eki) | **Closed** | `RateLimitMediaTypeFailClosedIT`, `RequestBodyFormatsTest` |
| C2 | Username çıkarımı fazla katı (`isTextual()`) → sayısal username bucket'tan kaçıyor | **Closed** | `RateLimitMediaTypeFailClosedIT` |
| C3 | 405/415/406 → `handleUnexpected` → **500 + stack trace** | **Closed** | `HttpErrorContractIT`, `ClientErrorLogBudgetIT` |
| C4 | Reddedilen istek IP bucket'ını harcamıyordu → ücretsiz ret = sınırsız hız | **Closed** | `RateLimitBypassIT:188` |
| C5 | api-docs kapısı `if (!production)` → **profilsiz boot'ta fail-open** (canlı 200) | **Closed** | `DefaultProfileApiDocsExposureIT` |
| C6 | `trusted-proxy-count` dev'de prod şeklini taklit ediyordu | **Closed** | `DevProfileSecurityIT` |
| C7 | **Test kalitesi:** tüm rate-limit testleri header'ı aynı kurduğu için D1 boyunca yeşil kaldı | **Closed** | `RateLimitContentTypeBypassIT:38` (R-19 false-green sınıfı) |
| D1 | Media-type allowlist: çağıranın seçtiği `Content-Type` 16 KB sınırını kapatıyor (`application/yaml`, springdoc üzerinden) | **Closed** | `RateLimitMediaTypeFailClosedIT`; allowlist → **fail-closed** kural |
| D2 | Limiter'ın parse edemediği gövde ölçülmeden geçiyordu | **Closed** | `RateLimitMediaTypeFailClosedIT:71` |
| D3 | `@ExceptionHandler(Exception.class)` kendi status'unu taşıyan **tüm** framework exception'larının önünde → 500 + trace | **Closed** | `FrameworkExceptionContractIT` — tek tek isim yerine **sınıf** kapatıldı (`ErrorResponse` 4xx kuralı) |
| D4 | `max-body-bytes` yalnız throttled yollarda | **Closed → F1** | kalıcı çözüm F1 |
| D5 | Media-type yazımlarını elle saymak yetmedi (üçüncü tekrar) | **Closed** | türetilmiş envanter |
| E1 | `?sort=;drop` → 500 + 233 frame, ~29 KB log/istek; **yetkisiz ama kimlikli** herkes | **Closed** | `ClientErrorLogBudgetIT:191`, `GlobalExceptionHandlerSortTest` — 3 ayrı exception şekli |
| E2 | Reddedilen sort property'si çağırana echo ediliyordu | **Closed** | `GlobalExceptionHandlerSortTest:202` |
| E3 | Maven incremental compile stale `.class` → CI'da false green/red | **Closed** | `ci.yml` `clean verify` / `clean package` |
| E4 | Geçerli token tutan çağıran ERROR satırı üretebiliyordu (gerçek arızayı gürültüye gömme) | **Closed** | `ClientErrorLogBudgetIT:157`; canlı: tüm smoke boyunca **0 ERROR** |
| **F1** | **Gövde sınırı yalnız 5 anonim yolda.** `@RequestBody` binding `@PreAuthorize`'dan **önce** koştuğu için sıfır izinli kullanıcı 1.5 MB gönderiyor, 403'ü gövde tamamen deserialize edildikten **sonra** alıyordu — allocation boyutu çağıranın seçimi | **Closed** | `RequestBodyLimitIT` (11), `RequestBodyLimitLayeringIT` (2), `BoundedBodyReaderTest` (6); canlı 1.5 MB **ve chunked** → 413 |

**F1'in kendi implementasyonunda bulunan iki hata** (her biri kasten bozularak kanıtlandı):
(a) `instanceof CachedBodyHttpServletRequest` kısa devresi "sıkı kural kazanır"ı tasarım özelliği
olmaktan çıkarıp default değerlerin tesadüfüne çeviriyordu — limitler ters çevrilerek
`RequestBodyLimitLayeringIT` ile kilitlendi; (b) `maxBodyBytes + 1` `Integer.MAX_VALUE`'da negatife
taşıp **boş gövde** döndürüyordu (her chunked isteği sessizce boşaltan yanlış-konfigürasyon).

### Kapanış turunda açılan yeni maddeler

| ID | Bulgu | Kaynak | Etki | Şiddet | Durum |
|---|---|---|---|---|---|
| PROD-R17 | `/actuator/metrics` ve `/actuator/prometheus` **yetki istemiyordu**: yalnız `anyRequest().authenticated()` altındaydı → sıfır izinli tenant kullanıcısı heap/JVM durumu, route isimleri, istek sayaçları ve `spring.security.filterchains.*` (hangi korumaların devrede olduğu) okuyabiliyordu. Base config'te expose edildiği ve prod override'ı olmadığı için **prod davranışı** | `SecurityConfig`, `application.yml` | Keşif yüzeyi (yetki yükseltmesi değil) | Orta | **Closed** — `/actuator/**` → `settings.host.manage` (host-only). `ActuatorExposureIT` (5). Negatif kanıt: kural kaldırılınca sıfır izinli kullanıcı **200 + tam metrik listesi** |
| PROD-R18 | `REDIS_PORT` env'i **hiç okunmuyordu** (`port: 6379` literal); runbook'ta zorunlu değişken olarak listeliydi. Prod cache'i Redis olduğu için en çok muhtaç ortam ayarlayamayan ortamdı | `application.yml` | Yönetilen Redis'e (Azure 6380/TLS) bağlanılamaz | Yüksek (deployment) | **Closed** — `${REDIS_PORT:6379}` |
| PROD-R19 | SMTP auth desteklenmiyordu: `mail.smtp.auth: false` sabit, `MAIL_USERNAME`/`PASSWORD` okunmuyordu → SES/SendGrid/Postmark imkânsız. Üstelik `MAIL_HOST` boşken `LoggingEmailSender`'a **sessizce** düşülüyor, yani şifre sıfırlama kimsenin görmediği bir şekilde ölüyordu | `application.yml` | Şifre sıfırlama / e-posta doğrulama prod'da çalışmaz | Yüksek (fonksiyonel) | **Closed** — username/password + `MAIL_SMTP_AUTH` / `MAIL_SMTP_STARTTLS`. *Kalan:* `MAIL_HOST` boşken fail-fast değil (bilinçli, freeze) |
| PROD-R20 | Imajda profil, heap sınırı ve healthcheck yoktu; entrypoint exec-form olduğu için `JAVA_OPTS` de genişlemiyordu | `backend/Dockerfile` | Yanlış config'le boot / OOM-kill / trafiğe erken açılma | Orta | **Closed** — `SPRING_PROFILES_ACTIVE=prod`, `MaxRAMPercentage=75`, readiness `HEALTHCHECK`, `exec` shell-form (SIGTERM PID 1'e ulaşsın) |
| PROD-R21 | `/api/users` **SQL'de sayfalamıyor**: `@EntityGraph("roles")` + `Page<User>` → Hibernate `HHH90003004`, tüm silinmemiş kullanıcılar rollerle heap'e çekilip Java'da diliniyor | `UserRepository:36-71` | 50k kullanıcılı tenant'ta her sayfa isteği bellek/latency uçurumu; sayfa boyutu koruma olmaktan çıkıyor | Orta | **Open** — feature freeze; düzeltme: iki aşamalı sorgu (id sayfası → roller) veya `@BatchSize`. Canlı ölçümle tespit edildi (5 kullanıcıda görünmez) |

- **PROD-R12:** Kapandı — CI'da `migration-drift` gate'i. *(Register bir süre "gate CI'ya eklendi"
  diyordu; **gate yoktu**. İddia doğrulanmadan kapalı yazılmıştı, bu tur gerçekten yazıldı.)*

### CI bağlama turu — "kontrol var ama onu okuyan şey oraya bakmıyor" (2026-07-19)

> **Bu turun bulunuş şekli kayda değer.** GO verildikten *sonra*, release adımının ilk işi olarak
> "CI gerçekten yeşil mi?" diye sorulunca ortaya çıktı. Tek komut yetti: `gh api .../actions/runs`
> → `total_count: 0`. Yedi tur boyunca ci.yml'in *içeriği* incelendi, *konumu* bir kez sorulmadı.

| ID | Bulgu | Kanıt | Şiddet | Durum |
|---|---|---|---|---|
| PROD-R22 | **CI hiç koşmadı.** `ci.yml` `zero-spring/.github/workflows/` altındaydı; GitHub Actions yalnız **repo kökündeki** `.github/workflows/`'u okur. Actions repo'da açıktı (`enabled: true`) — yani kapalı olduğu için değil, dosya hiç kaydedilmediği için. Tüm release-gate zinciri (build → test → typed-client-drift → migration-drift → live-smoke → security-checks → release) inert | `actions/runs total_count=0`, `actions/workflows` boş | **Kritik (süreç)** | **Closed** — kök `.github/workflows/ci.yml`; `defaults.run.working-directory: zero-spring` + 28 yolun iki ayrı kurala göre yeniden yazımı (adım `working-directory` ve action `with:` girdileri workspace'e göre; `run:` içi göreli yollar PWD'ye göre). **Kanıt ilk gerçek koşuda tamamlanır** |
| PROD-R23 | **Branch protection kurulamıyor** → kırmızı check hiçbir merge/push'u engellemez. `needs:` zinciri yalnız *workflow içi* akışı sıralar | `branches/main/protection` → **403 "Upgrade to GitHub Pro or make this repository public"**; `rulesets` → aynı 403; repo `private: true`, org planı ücretsiz | **Yüksek** | **Open — kodla kapatılamaz.** Seçenekler: (a) GitHub Team/Pro planı, (b) repo'yu public yapmak, (c) blokajı insan disiplinine bırakmak (bugünkü durum). ci.yml başlığındaki aksi yöndeki cümle düzeltildi |
| PROD-R24 | **gitleaks üç katmanlı fail-open.** `-v "${PWD}:/repo"` mount ediyordu; `defaults` yüzünden `PWD` = `zero-spring` ve orada `.git` **yok** (repo kökünde). `detect` git-geçmişi modudur → her koşuda hata. Hata üç yerde yutuluyordu: `continue-on-error`, SARIF üretilmediği için `if-no-files-found: ignore`, ve adımın advisory olması. `fetch-depth: 0`'ın maliyeti ödenip faydası hiç alınmadan **yeşil** | Denetim: `ls -d zero-spring/.git` → yok; `git rev-parse --show-toplevel` → üst dizin | Orta | **Closed** — `${GITHUB_WORKSPACE}` mount + rapor yolu birlikte taşındı |
| PROD-R25 | Bloklayıcı secret grep'i `.` (= `zero-spring`) tarıyordu → **kök `.github/workflows/ci.yml` kendi taramasının dışında**. Workflow dosyaları credential gömmenin klasik yeri | `-- "${pattern}" .` | Düşük | **Closed** — `"${GITHUB_WORKSPACE}/.github"` kapsama eklendi |
| PROD-R26 | `application-prod.yml` kontrolü `if [ -f ]` ile sarılıydı, **`else` dalı yoktu** → dosya taşınırsa kontrol sessizce atlanır, job yeşil kalır | `ci.yml` secret scan | Düşük | **Closed** — `else` → `::error::` + `FAILURES++` |
| PROD-R27 | **Dockerfile'ı hiçbir otomasyon build etmiyor.** `docker-compose.yml`'de `build:` yok, CI'da `docker build` yok; tek referans RELEASE-RUNBOOK. Yani PROD-R20'nin sertleştirmeleri (prod profili, heap tavanı, HEALTHCHECK) hiçbir kapıda doğrulanmıyor | `grep -rn "docker build"` → yalnız runbook | Orta | **Open** — bilinçli sıralama: ilk CI koşusunun **yeşil olduğu kanıtlanmadan** yavaş ve denenmemiş bir adım eklemek, "yol yeniden yazımı mı bozuk, docker build mi bozuk" ayrımını imkânsızlaştırır. Baseline yeşil olunca `release` gate'ine eklenecek |
| PROD-R28 | `dependabot.yml`, `CODEOWNERS`, `renovate.json` **hiç yok** (yanlış yerde değil — mevcut değil). `npm audit --audit-level=high` kapısı var ama bağımlılığı güncelleyecek otomasyon yok; CODEOWNERS yokluğu PROD-R23 ile birleşince zorunlu inceleme sıfır | `git ls-files` → 0 eşleşme | Düşük | **Open** — eksik kontrol, ölü kontrol değil |

### İlk CI koşusunun bulduğu — PROD-R29 (2026-07-19)

> CI ilk kez koştu ve **ilk koşusunda ürün kusuru buldu.** `backend` gate'i düştü, sonraki beş gate
> `skipped` oldu — yani "hata sonrakileri bloke eder" mekanizması da aynı anda ilk kez kanıtlandı.

Üç test **503 SERVICE_UNAVAILABLE** ile düştü (401/403 değil — güvenlik kuralı doğruydu):
`ProdApiDocsExposureIT.healthRemainsAnonymousInProduction`,
`DefaultProfileApiDocsExposureIT.healthAndLoginRemainAnonymous`,
`ActuatorExposureIT.theProbesStayAnonymous`.
Kök neden: `MailHealthIndicator` → `Couldn't connect to host, port: localhost, 1025` → toplam health DOWN.

| ID | Bulgu | Şiddet | Durum |
|---|---|---|---|
| PROD-R29a | **Trafiği kesmemesi gereken bağımlılık kesiyordu.** Mail health indicator her çağrıda gerçek SMTP bağlantısı açıyor; 10 sn'lik bir probe relay'e günde ~8.600 bağlantı demek — SES/SendGrid bunu throttle eder ya da IP'yi engeller, yani **kontrolün kendisi arızayı üretir**. E-posta istek servis etmek için gerekli değil, ama `/actuator/health` onun yüzünden 503 dönüyordu | Yüksek | **Closed** — `management.health.mail.enabled: false`; mail erişilebilirliği runbook §3.6 smoke'u ile doğrulanıyor |
| PROD-R29b | **Trafiği kesmesi gereken bağımlılık kesmiyordu.** Spring Boot'un varsayılan readiness grubu yalnızca `readinessState` — yani **veritabanı erişilemezken pod READY raporlar**, trafik alır ve her isteğe 500 döner. Suite'te hiçbir şey grubun içeriğini iddia etmediği için görünmüyordu | Yüksek | **Closed** — `readiness.include: readinessState,db`. Redis bilerek dışarıda (PROD-R13 CacheErrorHandler kesintide bypass ediyor, uygulama servis etmeye devam ediyor) |
| PROD-R29c | **Test kalitesi:** üç test `/actuator/health` 200 iddia ediyordu ve yalnızca geliştiricinin makinesinde mailpit ayakta olduğu için yeşildi. Testler yanlış değildi — geliştiricinin `docker-compose`'unu ölçüyorlardı. R-19'un aynası: false-green değil, **environment-dependent green** | Orta | **Closed** — `HealthProbeContractIT` (4): mail **kasten ölü porta** (`spring.mail.port=1`) yönlendirilmiş durumda health 200 olmalı; ayrıca readiness/liveness grup üyelikleri doğrudan `HealthEndpointGroups` üzerinden iddia ediliyor |

**Neden grup üyeliği ayrıca test ediliyor:** yalnızca "endpoint 200 dönüyor" demek, biri
`readiness.include`'ı varsayılana geri alsa da, `mail`'i gruba eklese de yeşil kalırdı — DB erişilebilir
olduğu sürece iki durum birbirinden ayırt edilemez. Grup bir **konfigürasyon kararı**, o yüzden
konfigürasyon olarak test ediliyor. `liveness`'ın `db` içermediği de iddia ediliyor: veritabanı
kesintisi JVM'i öldürmek için sebep değildir, aksi hâlde kesinti bir crash-loop'a dönüşür.

### CI koşuları 2-3'ün bulduğu — PROD-R30 / PROD-R31 (2026-07-19)

CI koştukça her koşu bir öncekinin göremediği katmanı açtı. Koşu 3'te `backend` ✅ oldu ve
`typed-client-drift` **kendi asıl işinde** düştü — yani gate ilk gerçek icrasında var olma
sebebini yakaladı.

| ID | Bulgu | Kanıt | Şiddet | Durum |
|---|---|---|---|---|
| PROD-R31 | **Typed client bayattı.** `POST /api/subscriptions/{tenantId}/change-edition` ile `ChangeEditionRequest` / `EditionChangeDto` şemaları backend'de vardı, commit'li `schema.d.ts`'te **yoktu**. Frontend bayat tiplere karşı sorunsuz derleniyordu; hata ancak üretimde, çağrı yapıldığında çıkardı | CI `diff -u` çıktısı; yeniden üretimde 86 satır ekleme | Orta | **Closed** — `npm run gen:api` ile yeniden üretildi; frontend build ✓, 90/90 test ✓ |
| PROD-R30 | **Gate'in kendisi flaky'ydi.** springdoc, Spring'in `Page`/`Pageable` arayüzlerini reflection ile geziyor ve `getDeclaredMethods()` sırası JVM spesifikasyonunda garanti değil. Ölçüldü: **aynı jar, iki ayrı boot** → `PageUserDto` içinde `totalPages`/`totalElements`/`first`/`last` yer değiştirdi. Gate byte-byte `diff` yaptığı için hiçbir şey değişmeden rastgele kırmızıya dönerdi | İki JVM boot'unun hash karşılaştırması: önce farklı, düzeltmeden sonra **aynı** | Orta | **Closed** — `springdoc.writer-with-order-by-keys: true`; determinizm iki ayrı JVM ile yeniden ölçülerek doğrulandı |

**Neden kaynak deterministik yapıldı, karşılaştırma gevşetilmedi:** flaky bir release gate,
olmayan gate'ten **kötüdür**. "Yeniden koştur" refleksini öğretir; o refleks de gate'in yakalamak
için var olduğu gerçek drift'in (PROD-R31 — tam da bu koşuda yakalanan) görmezden gelinmesini
öğretir. Diff'i "sıralamayı yok say" diye gevşetmek, gate'i zayıflatarak semptomu gizlerdi.

### CI koşusu 4 — zincir uçtan uca YEŞİL, ve gitleaks ilk kez konuştu (PROD-R33)

Koşu 4'te **sekiz job da geçti** (`build → backend/frontend → typed-client-drift →
migration-drift → live-smoke → security-checks → release`). "Yeşil" ile "gerçekten doğruladı"
aynı şey olmadığı için her gate'in *vakum yeşili* olmadığı ayrıca log'dan doğrulandı:

| Gate | Gerçekten ne yaptığının kanıtı |
|---|---|
| `migration-drift` | `oldset`'ten **V1..V6 çıktı** (`have_base=true` dalı), önceki set uygulandı (`now at version v6`), `Successfully validated 6 migrations` (checksum drift kontrolü koştu), ikinci migrate `No migration necessary` (idempotent), jar yükseltilmiş şemaya karşı boot etti |
| `security-checks` | `Secret pattern scan clean`, `npm audit: found 0 vulnerabilities`, gitleaks **20 commit** taradı |
| `live-smoke` | backend log artifact'ı üretildi (7.440 bayt) |

| ID | Bulgu | Şiddet | Durum |
|---|---|---|---|
| PROD-R33 | **gitleaks çalışır çalışmaz 3 bulgu verdi.** Üçü de test-only JWT imza anahtarı (`application-test.yml:12`, `DefaultProfileApiDocsExposureIT:42`, `ProdApiDocsExposureIT:36`). Gerçek kimlik bilgisi değil — `JwtSecretValidator` repoda commit'li her anahtarı prod'da reddediyor (`JwtSecretValidatorTest`, 5) — ama **kalıcı** bulgu bırakmak taramayı işe yaramaz kılar: 3 bilinen bulguyu her koşuda gören, 4.'yü fark etmez | Düşük (gürültü) / Orta (dikkat) | **Closed** — kök `.gitleaks.toml`, tam base64 değerleriyle **yol bazlı DEĞİL** (o dosyalara gerçek bir secret eklenirse yine yakalanır). Ölçüldü: 3 → **`no leaks found`** |
| PROD-R34 | **gitleaks yanlış config sözdizimini SESSİZCE yok sayıyor.** v8.18.4 tekil `[allowlist]` okur; çoğul `[[allowlists]]` (sonraki sürümlerin biçimi) hata vermeden yok sayılır ve config hiç verilmemiş gibi davranır. Yalnızca bulgu sayısı ölçüldüğü için fark edildi | Düşük | **Closed** — tekil biçim + `--config` açıkça geçiliyor (otomatik keşfe güvenilmiyor); gerekçe hem `.gitleaks.toml` hem `ci.yml` içinde yazılı. Sürüm yükseltmesinde yeniden doğrulanmalı |
| PROD-R35 | gitleaks **advisory** bırakılmıştı (`continue-on-error`). Tarama artık çalıştığına ve geçmiş temiz olduğuna göre, advisory kalması gelecekteki **gerçek** bir sızıntının da yok sayılması demekti — PROD-R24'ün üç katmanlı fail-open'ının bilerek yapılmış hâli | Orta | **Closed** — **bloklayıcı** yapıldı; hata mesajı "dosyadan silmek yetmez, rotasyon şart" uyarısını taşıyor |

### Actions maliyeti — PROD-R32

| ID | Bulgu | Şiddet | Durum |
|---|---|---|---|
| PROD-R32 | Zincir 8 job açıyor, üçü Postgres kaldırıp jar boot ediyor; koşu ~7,7 dk duvar saati. Depo private ve planın Actions dakikası sınırlı. Doküman commit'leri kod commit'lerinden sık ve her biri tam Testcontainers suite'ini tetikliyordu | Orta (süreç) | **Mitigating** — (a) `paths-ignore`: `**/*.md`, `zero-spring/docs/**` (doğrulandı: `docs/` altında `.md` dışı dosya yok); (b) `workflow_dispatch` ile elle tam koşu; (c) **`zero-spring/scripts/ci-local.sh`** — gate'leri lokalde, tek kullanımlık Postgres ile ve `REDIS_PORT=1` ile (CI'daki gerçek durum) koşturur. *Kesin kota `admin:org` scope'u olmadan doğrulanamadı; timing API `billable=0` diyor ama bu **teyit sayılmadı**.* |

> `paths-ignore` bir **güvenlik kapısı değil**: listelenen yollar CI'ın hiç okumadığı yollardır.
> Buraya CI'ın davranışını etkileyen bir yol eklenirse gate sessizce atlanır ve yeşil görünür.
> Uyarı `ci.yml` içinde de yazılı.

**Doğrulanan iyi durumlar (bu turda ölçüldü):** `.gitattributes` `zero-spring/` altında ama alt-dizinden
aşağı özyinelemeli uygulandığı için **etkili** — `git check-attr` ile doğrulandı (`mvnw: eol=lf`,
`mvnw.cmd: eol=crlf`), BOM içerik olarak da temiz (R-22 gerçekten kapalı);
`package-lock.json` commit'li ve ignore edilmiyor (`npm ci` sağlam); `.gitignore` CI'ın ürettiği
artifact'ları sabote etmiyor (`upload-artifact` `.gitignore` okumaz); repoda çakışan ikinci bir
workflow yok; `Asp.NET Zero/` tamamen untracked, CI açısından yok hükmünde.

**Yanlış çıkan bir hipotez (kayıt).** `migration-drift` içindeki `git archive <BASE> backend/src/...`
yolunun taşımadan sonra `zero-spring/` öneki alması gerektiğini düşünmüştüm. **Yanlış:** `git archive`
pathspec'i cwd'ye göre çözer ve arşiv girdileri de cwd-görelidir; ampirik olarak doğrulandı
(`cd zero-spring && git archive HEAD backend/... | tar -t` → `backend/...` ile başlıyor,
`--strip-components=6` doğru). Önek eklenseydi `pathspec did not match` → `have_base=false` →
`::warning::` → **gate hiçbir şey doğrulamadan yeşil** dönerdi. Yani "düzeltme" tam olarak korkulan
sessiz-yeşil modunu üretecekti.

**Doğrulanan iyi durumlar (aksiyon gerekmez):** gerçek secret sızıntısı **yok** (gitleaks desenleri 0 eşleşme);
JWT algoritma HS512 **pinlenmiş** + issuer doğrulaması zorunlu + secret uzunluğu boot'ta fail-fast;
refresh token SHA-256 hash + atomik rotasyon + reuse'da aile revoke; BCrypt(12); migration'larda **DROP/RENAME yok**,
default'suz NOT NULL **yok**; SaaS entity'lerinde JPA association yerine düz FK → lazy N+1 yüzeyi yok;
`SubscriptionService` liste sorguları batch (`findAllById`) → N+1 yok; SaaS index'leri yeterli.

## Mitigasyon takvimi (özet)

- **F1 ✅:** R-01, R-02 Closed; R-10 taşınmama kararı.
- **F2 ✅ (Closed):** R-07, R-12, R-13, R-14, R-16, R-17, R-18, R-19, R-20, R-21.
- **F2 kısmi — R-11:** permission model kapandı (PermissionTreeIT); genel durum **Mitigating** (grant verisi ETL → F6).
- **F2 kapanış (commit):** R-22 (mvnw LF — commit + CI koşusuyla doğrula).
- **F2 devam / slice C:** R-09 (React ekranları — impersonation/audit/settings UI), R-08 (@FilterJoinTable/ArchUnit), R-23 (düşük artıklar).
- **F3:** R-03 (WS auth), R-06 koşullu jti denylist, mutasyon testi.
- **F5:** R-15 (SaaS ticari katman).
- **F6:** R-03/R-04/R-05/R-11 veri tarafı (ETL); secret rotasyonu (R-10) cutover.
