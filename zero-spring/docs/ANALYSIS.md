# A) ANALİZ RAPORU — ASP.NET Zero Envanteri ve Spring Parite Matrisi

> **Kaynak ve yöntem:** Bu rapor, 11 paralel analiz ajanının (10 alt sistem envanteri + 1 eksiklik
> denetçisi/critic) kod tabanı taraması üzerine yazılmıştır. İncelenen sürümler: **ASP.NET Zero
> v14.1.0 (abp-zero-template)**, **ABP Framework 10.1.0**, **.NET 9 / EF Core 9**, **SQL Server**,
> **Angular 19.1.x**. Hedef mimari kararları için bkz. `ARCHITECTURE.md`, faz planı için
> `IMPLEMENTATION-PLAN.md`.
>
> **Gizlilik:** Raporda hiçbir secret DEĞERİ yer almaz; yalnızca konfigürasyon ANAHTAR adları ve
> dosya yolları verilir.
>
> **Yol kısaltmaları:** Kök `Asp.NET Zero/aspnet-core`. Proje önekleri kısaltılmıştır:
> `[Core]` = `src/MyCompanyName.AbpZeroTemplate.Core`, `[Core.Shared]`, `[App]` = `…Application`,
> `[App.Shared]`, `[App.Client]`, `[EF]` = `…EntityFrameworkCore`, `[Web.Host]`, `[Web.Core]`,
> `[Web.Mvc]`, `[Web.Public]`, `[GraphQL]`, `[Migrator]`, `[Maui]`, `[NG]` = `angular`.

---

## 1. Mevcut çözümün modül envanteri

### 1.1 Backend proje listesi ve rolleri

| Proje | Rol |
|---|---|
| `Core` | Domain katmanı: entity'ler, manager'lar (Tenant/User/Impersonation/Chat…), setting/permission/feature/notification tanımları, LDAP, passwordless, QR login |
| `Core.Shared` | İstemcilerle paylaşılan sabitler: `AppPermissions` (72 izin), enum'lar, `AbpZeroTemplateConsts` |
| `Application` | AppService katmanı (~40 servis): CRUD, Excel import/export, ödeme, dashboard; ABP bunları otomatik REST API'ye çevirir |
| `Application.Shared` | DTO'lar + `I*AppService` arayüzleri (40 dosya) — istemci sözleşme katmanı |
| `Application.Client` | Flurl tabanlı HTTP proxy istemcisi (MAUI/Console için) — **critic bulgusu, ilk envanterde atlanmıştı** |
| `EntityFrameworkCore` | DbContext (~48 ABP + 12 açık DbSet), 51 migration, seed altyapısı, repository base |
| `Web.Core` | Ortak web katmanı: `TokenAuthController`, JWT handler'lar, OpenIddict, SignalR hub'ları, Swagger filtreleri, tenant middleware |
| `Web.Host` | Angular'a hizmet veren API host'u: Startup/pipeline, CORS, health check, Hangfire (kapalı), GraphQL endpoint |
| `Web.Mvc` | Alternatif jQuery/Metronic tam admin UI — **29 controller + Views (critic bulgusu: envanterde UI sayfa dökümü yoktu)** |
| `Web.Public` | Ayrı landing sitesi; accessToken handoff ile oturum devralma (`Web.Public/Controllers/AccountController.cs`) |
| `GraphQL` | Opsiyonel salt-okunur GraphQL API (users/roles/OU sorguları) |
| `Migrator` | Konsol migration aracı: host DB + tenant DB'lerini sırayla migrate eder |
| `Maui` | .NET MAUI Blazor Hybrid mobil uygulama (login, tenant seçimi, user CRUD, dashboard) |
| `ConsoleApiClient` | IdentityServer4 password-flow örnek istemcisi (deprecated; gerçek test değil) |
| `Test.Base`, `Tests`, `GraphQL.Tests` | xUnit + Abp.TestBase + in-memory SQLite entegrasyon testleri (~101 Fact/Theory, fiilen ~120-150 case) |
| `AspNetZeroRadTool/` | RAD/Power Tools kod üreteci: `config.json` + 550'den fazla şablon — **critic bulgusu** |
| `angular/` | Angular 19 SPA (ayrıntı §5) |

### 1.2 Alt sistem özetleri (envanter ajanlarının bulguları)

**S0 — Kimlik doğrulama + yetkilendirme**
- Şifre, 2FA (Email/SMS/TOTP), 6 harici sağlayıcı, LDAP, passwordless (SMS/e-posta kod), QR login, impersonation, delegation, linked accounts — tamamı tek `TokenAuthController` etrafında.
- JWT **stateless değil**: her istekte `TokenValidityKey` (cache→DB) + SecurityStamp doğrulanır; logout access+refresh anahtarlarını siler.
- HS256 simetrik anahtar; access 1 gün / refresh 365 gün (`AppConsts.cs` sabitleri).
- Permission ağacı 72 sabit, Host/Tenant tarafı ayrımıyla (`MultiTenancySides`).
- Anahtar dosyalar: `[Web.Core]/Controllers/TokenAuthController.cs`, `[Web.Core]/Authentication/JwtBearer/AbpZeroTemplateAsyncJwtSecurityTokenHandler.cs`, `[Core.Shared]/Authorization/AppPermissions.cs`

**S1 — Multi-tenancy + Edition/Feature + Abonelik/Ödeme**
- Subdomain tabanlı tenant çözümleme (`DomainTenantCheckMiddleware`); hibrit izolasyon: shared DB + ABP filtreleri, opsiyonel tenant-başına şifreli connection string.
- Tam SaaS ticari katman: `SubscribableEdition` (aylık/yıllık fiyat, trial), feature gating (`MaxUserCount`, Chat), Stripe (recurring) + PayPal (tek seferlik), proration'lı upgrade, fatura, abonelik bitiş worker'ları.
- Tenant provisioning 2 ayrı transaction'da (atomik değil); Paid kayıtta tenant webhook onayına kadar pasif.
- Anahtar dosyalar: `[Core]/MultiTenancy/TenantManager.cs`, `[Core]/MultiTenancy/Payments/Stripe/StripeGatewayManager.cs`, `[App]/MultiTenancy/TenantRegistrationAppService.cs`

**S2 — Application katmanı (AppService envanteri)**
- ~40 AppService: user/role/OU/tenant/edition CRUD, audit, chat, notifications, webhooks, dynamic properties, settings, dashboard, ödeme.
- MiniExcel tabanlı ortak Excel export/import altyapısı; kullanıcı toplu import background job + geçersiz kayıt raporu (`InvalidUserExporter`).
- DTO doğrulama: DataAnnotations (71 dosyada 161 kullanım) + ABP `ICustomValidate`/`IShouldNormalize` (birebir Bean Validation'a çevrilemez).
- Anahtar dosyalar: `[App]/Authorization/Users/UserAppService.cs`, `[App]/CustomDtoMapper.cs`, `[App]/DataExporting/Excel/MiniExcel/MiniExcelExcelExporterBase.cs`

**S3 — Veri katmanı (EF Core / SQL Server)**
- ~48 ABP tablosu (`Abp*`) + 12 uygulama DbSet'i (`App*`); 51 migration (IdentityServer4→OpenIddict geçişi dahil); 126 audit/soft-delete kolonu.
- Seed: 15 dil, Standard edition, host admin (**çift hash formatı**: host admin eski ABP hash'i, tenant admin ASP.NET Identity v3 PBKDF2; bilinen şablon varsayılan şifresi seed'de hardcoded).
- Entity History altyapısı şemada var ama `IsEnabled=false` (kapalı).
- Anahtar dosyalar: `[EF]/EntityFrameworkCore/AbpZeroTemplateDbContext.cs`, `[EF]/Migrations/AbpZeroTemplateDbContextModelSnapshot.cs`, `[EF]/Migrations/Seed/SeedHelper.cs`

**S4 — Background job + bildirim + SignalR/Chat**
- ABP varsayılan job manager (`AbpBackgroundJobs` tablosu); **Hangfire kodda hazır ama varsayılan KAPALI** (`WebConsts.HangfireDashboardEnabled=false`).
- 5+ periyodik worker (abonelik bitişi 1 saat, audit temizliği 3 dk, şifre süresi 1 gün…) — hem Web.Host hem Web.Mvc'de kayıtlı → çoklu instance'ta çift çalışma riski.
- Bildirim: tanım/abonelik/inbox + 3 kanal (in-app SignalR, e-posta, SMS — son ikisi `UseOnlyIfRequestedAsTarget=true`); mass notification OU hedeflemeli.
- Chat tenant'lar-arası, mesaj her iki tarafta ayrı satır (`SharedMessageId` ile eşleşir); 3 SignalR hub: `/signalr`, `/signalr-chat`, `signalr-qr-login` (baştaki `/` eksik — mevcut bug).
- Anahtar dosyalar: `[App]/Notifications/NotificationAppService.cs`, `[Core]/Chat/ChatMessageManager.cs`, `[Web.Core]/Chat/SignalR/ChatHub.cs`

**S5 — Localization + Settings + Timing**
- 15 dil, gömülü XML sözlükler (~1300+ anahtar) + DB override katmanı (`AbpLanguages`/`AbpLanguageTexts`, tenant bazlı).
- `AppSettingProvider` 1337 satır; scope zinciri User→Tenant→Application→appsettings default; `isInherited:false` ve `isEncrypted:true` istisnaları; 13 tema × ~7-17 ayar anahtar patlaması.
- **Timezone değerleri Windows ID formatında** (TimeZoneConverter); `Clock.Provider` açıkça UTC'ye set edilmemiş.
- Anahtar dosyalar: `[Core]/Configuration/AppSettingProvider.cs`, `[Core]/Timing/TimeZoneService.cs`, `[App]/Localization/LanguageAppService.cs`

**S6 — Web.Host kompozisyonu**
- Pipeline: UseAbp → tenant middleware → CORS → RateLimiter (yalnız passwordless) → JWT → OpenIddict → SignalR → GraphQL; **HSTS yalnızca Development dalında (anomali)**.
- Redis modülü tanımlı ama `UseRedis` çağrısı **yorum satırında** → fiilen in-memory cache.
- Health check (`/health` + HealthChecksUI), Swagger (özel filtrelerle), Log4Net, plugin klasöründen modül yükleme.
- Anahtar dosyalar: `[Web.Host]/Startup/Startup.cs`, `[Web.Core]/AbpZeroTemplateWebCoreModule.cs`, `[Web.Core]/Common/WebConsts.cs`

**S7 — Angular Frontend** (ayrıntı §5)
- Angular 19.1.x, NgModule+standalone hibrit; Metronic repo içine gömülü, 13 tema; nswag ile tek dev `service-proxies.ts`; `abp.js` global sözleşmesi; NgRx yok.
- Anahtar dosyalar: `[NG]/package.json`, `[NG]/nswag/service.config.nswag`, `[NG]/src/AppPreBootstrap.ts`

**S8 — DevOps + Test**
- Docker compose setleri (mssql+redis, migrator, mvc/ng dağıtımları), Helm chart'lar (SQL Server pod olarak — prod'a uygun değil), OWASP ZAP context'leri.
- **CI/CD pipeline hiç yok** — tüm build/deploy lokal PowerShell scriptleri; compose/Helm'de düz metin parolalar.
- Testler in-memory SQLite üstünde ABP pipeline'ına bağlı entegrasyon testleri — Spring'e birebir port edilemez.
- Anahtar dosyalar: `docker/infrastructure/docker-compose.infrastructure.yml`, `build/build-with-ng.ps1`, `etc/k8s/helm-chart/abpzerotemplate-angular/values.yaml`

**S9 — İleri/yan özellikler**
- GraphQL (playground prod'da açık — risk), Webhooks (yalnız `TestWebhook` tanımlı), DynamicEntityProperties (User kayıtlı), BinaryObject (dosyalar DB'de `byte[]`), GDPR veri toplama (3 provider), UiCustomization, Web.Public token handoff, Migrator, MAUI.
- Anahtar dosyalar: `[Core]/Storage/BinaryObject.cs`, `[Core]/Webhooks/AppWebhookDefinitionProvider.cs`, `[Migrator]/MultiTenantMigrateExecuter.cs`

---

## 2. Özellik matrisi (Mevcut → Spring karşılığı)

Faz kolonu `IMPLEMENTATION-PLAN.md`'ye, Spring karşılıkları `ARCHITECTURE.md` kararlarına bağlıdır.
`Ops` = Opsiyonel: faz planında yer almaz; kapsama alınması §3.3'teki netleştirme sorularının
cevabına bağlıdır.

### 2.1 Kimlik doğrulama ve oturum

| Özellik | AspNetZero gerçekleşmesi (kanıt) | Spring hedef karşılığı | Faz | Parite notu |
|---|---|---|---|---|
| Şifre ile login (+reCAPTCHA) | `TokenAuthController.Authenticate`, `[App]/Authorization/LogInManager.cs` | Spring Security + BCrypt(12), `/api/auth/login` | F1 | reCAPTCHA F4; UA-whitelist bypass'ı (`WebConsts.ReCaptchaIgnoreWhiteList`) taşınmaz |
| JWT access + refresh | HS256, access 1g / refresh 365g (`[App.Shared]/AppConsts.cs`) | Nimbus JWT: 15 dk access (HS512→F4 RS256+JWKS), 7g rotate refresh (DB'de SHA-256 hash) | F1 | **Bilinçli iyileştirme** — süreler kısaltılır (§3.1-R2) |
| Logout + token iptali | `TokenValidityKey` cache+DB her istekte; `AbpZeroTemplateAsyncJwtSecurityTokenHandler.cs` | Refresh revoke (DB) + kısa access TTL; anlık access iptali gerekirse Redis denylist | F1 | Davranış farkı: mevcutta iptal anlık, hedefte ≤15 dk gecikmeli |
| Security stamp / tek eşzamanlı oturum | `JwtSecurityStampHandler.cs`, `App.UserManagement.AllowOneConcurrentLoginPerUser` | Redis'te kullanıcı başına session-version claim karşılaştırması | Ops | Planda yok; özellik kullanılıyorsa F2'ye eklenmeli |
| 2FA — Email/SMS/TOTP | `[Core]/Authentication/TwoFactor/Google/GoogleAuthenticatorProvider.cs`, `SendTwoFactorAuthCode` | MFA state machine + java-otp; kodlar Redis TTL; remember-device ayrı kısa JWT | F4 | Planda açık kalem yok → F4 hardening'e eklenmesi önerilir; mevcut global provider-cache hatası (§3.1-R10) taşınmaz |
| Harici sağlayıcılar (Google/FB/Twitter/MS/OIDC/WsFed) | `[Web.Host]/Startup/AuthConfigurer.cs`; tenant-bazlı ClientId/Secret sağlayıcıları | `spring-boot-starter-oauth2-client`; tenant-bazlı dinamik `ClientRegistrationRepository` | F4 | WsFederation'ın doğrudan karşılığı yok → SAML2/OIDC'ye taşıma |
| LDAP / Active Directory | `[Core]/Authorization/Ldap/AppLdapAuthenticationSource.cs` | `spring-security-ldap` / AD provider | Ops | Kullanım doğrulanmalı (Soru-5) |
| Passwordless login (email/SMS kod) | `[Core]/Authorization/PasswordlessLogin/PasswordlessLoginManager.cs`; IP limit 5/60sn | send/verify endpoint çifti + Redis TTL; Spring Security one-time-token (6.4+) | Ops | Kullanım doğrulanmalı |
| QR ile login | `[Core]/Authorization/QrLogin/QrLoginManager.cs` + SignalR hub | WebSocket/STOMP + Redis connection eşleme | Ops | Kullanım doğrulanmalı |
| Lockout | ABP settings + `User.IsLockoutEnabled`; Unlock izni | `failed_login_attempts` + `lockout_end_at` (F1 şeması, ARCHITECTURE §4) | F1 | 5 deneme / 5 dk paritesi |
| Şifre politikaları | Complexity settings (DB), `RecentPassword`, expiration worker, `ShouldChangePasswordOnNextLogin` | Passay + `password_history` + Quartz job + flag | F2 | Plan F2-m1 |
| Session timeout + lock screen | `App.UserManagement.SessionTimeOut.*` (`[Core]/Configuration/AppSettings.cs`) | Settings modülü üzerinden; UI zamanlayıcısı frontend'te | F2 | UI parçası F4 |
| Impersonation (host→tenant, back) | `[Core]/Authorization/Impersonation/ImpersonationManager.cs`; tek kullanımlık cache token | Kısa ömürlü token + `act` (actor) claim | F2 | Plan F2-m6; audit'te gerçek kullanıcı izlenir |
| User delegation (yetki devri) | `[Core]/Authorization/Delegation/UserDelegation.cs`; token exp delegasyon bitişine kırpılır | `user_delegation` tablosu + JWT filter kontrolü | Ops | Planda yok; kullanılıyorsa F2 impersonation ile birlikte |
| Linked accounts (hesap geçişi) | `[Core]/Authorization/Users/UserLinkManager.cs`, `switchAccountToken` | Kısa ömürlü switch-token endpoint'i | Ops | Kullanım doğrulanmalı |
| OpenIddict OAuth2/OIDC sunucusu | `[Web.Core]/OpenIddict/OpenIddictRegistrar.cs`; password + auth code flow | Spring Authorization Server (password grant YOK — OAuth 2.1) | Ops | İstemciler auth code + PKCE'ye taşınmalı; token/consent verisi taşınamaz |
| SSO köprüsü (SignInToken) | `TokenAuthController.AddSingleSignInParametersToReturnUrl`, `User.SetSignInToken` | One-time token endpoint'i | Ops | Web.Public kapsam kararına bağlı |
| Self-servis kayıt / şifre reset / e-posta aktivasyon | `[App]/Authorization/Accounts/AccountAppService.cs` | Token'lı reset + e-posta doğrulama | F2 | Plan F2-m1 |
| SignalR `enc_auth_token` (WS auth) | `SimpleStringCipher` + sabit passphrase, query string (`GetEncryptedAccessToken`) | WebSocket handshake'te Authorization header veya kısa ömürlü ticket | F3 | **Birebir taşınmaz** — mevcut zafiyet düzeltilir (§3.1-R3) |

### 2.2 Yetkilendirme ve kullanıcı yönetimi

| Özellik | AspNetZero gerçekleşmesi (kanıt) | Spring hedef karşılığı | Faz | Parite notu |
|---|---|---|---|---|
| Permission ağacı (72 sabit, Host/Tenant ayrımı) | `[Core.Shared]/Authorization/AppPermissions.cs`, `[Core]/Authorization/AppAuthorizationProvider.cs` | Authority sabitleri + `@PreAuthorize` (F1); `/api/permissions` tree endpoint (F2) | F1 | Hiyerarşik grant semantiği (parent→child) custom evaluator gerektirir |
| Rol yönetimi (klonlama, varsayılan rol) | `[App]/Authorization/Roles/RoleAppService.cs` | Rol CRUD + izin atama API'si | F2 | Plan F2-m2 |
| Kullanıcı yönetimi (CRUD, izin/rol/OU atama, unlock) | `[App]/Authorization/Users/UserAppService.cs` | User CRUD + soft delete (`@SQLRestriction`) | F2 | Plan F2-m1 |
| Organization Units ağacı | `[App]/Organizations/OrganizationUnitAppService.cs` | Closure table `ou_ancestors` | F2 | Plan F2-m3 |
| Profil yönetimi | `[App]/Authorization/Users/Profile/ProfileAppService.cs` | Profil endpoint'leri + settings | F2 | Foto akışı F3 (files) |
| Excel export (user/audit/chat) | MiniExcel base sınıflar; `UserListExcelExporter.cs` | Apache POI | F2 | Plan F2-m1 |
| Excel toplu kullanıcı import + geçersiz kayıt raporu | `ImportUsersToExcelJob.cs` + `InvalidUserExporter.cs` (job + bildirim zinciri) | Quartz async job + POI + hata raporu dosyası + bildirim | F3 | Zincirli davranış birebir korunmazsa sessiz veri kaybı |

### 2.3 Multi-tenancy ve SaaS ticari katman

| Özellik | AspNetZero gerçekleşmesi (kanıt) | Spring hedef karşılığı | Faz | Parite notu |
|---|---|---|---|---|
| Tenant çözümleme | Subdomain (`[Web.Core]/MultiTenancy/DomainTenantCheckMiddleware.cs`; NG tarafında subdomain→query→cookie) | `X-Tenant` header (F1) → subdomain desteği (F4) — ARCHITECTURE §3 | F1 | JWT `tenant` claim ↔ header çapraz doğrulaması F2 (plan F2-m7) |
| Veri izolasyonu (shared DB + filtre) | ABP `IMustHaveTenant`/`IMayHaveTenant` örtük filtreleri; `[EF]/…/AbpZeroTemplateDbContext.cs` | Hibernate `@Filter` + AOP otomatik aktivasyon; F4'te Postgres RLS derin savunma | F1 | **En kritik migration riski** (§3.1-R1); RLS AspNetZero'da olmayan iyileştirme |
| Tenant-başına ayrı DB | `Tenant.ConnectionString` (SimpleStringCipher ile şifreli), `IDbPerTenantConnectionStringResolver` | Bilinçli alınmadı (YAGNI, ARCHITECTURE §3); ihtiyaçta F4'te DATABASE moduna yol açık | Ops | Kullanan tenant var mı? (Soru-8) |
| Tenant CRUD + provisioning (admin, statik roller, seed) | `TenantManager.CreateWithAdminUserAsync` (2 ayrı UOW), `[App]/MultiTenancy/TenantAppService.cs` | `TenantRegistered` event → tenant admin oluşturma (Modulith outbox, plan F2-m8) | F1 | Temel CRUD F1; event akışı F2; atomiklik iyileştirilir |
| Tenant self-registration (Free/Trial/Paid) | `[App]/MultiTenancy/TenantRegistrationAppService.cs`; Paid'de tenant pasif başlar | Kayıt endpoint'i + ödeme onayı akışı | Ops | SaaS kararına bağlı (Soru-2) |
| Edition + feature gating | `SubscribableEdition.cs`, `AppFeatureProvider.cs` (MaxUserCount, Chat), `FeatureValueStore.cs` | plan+feature tabloları + `@RequiresFeature` AOP + cache | Ops | Planda yok — SaaS kararına bağlı |
| Abonelik yaşam döngüsü (trial, upgrade/proration, bitiş worker'ları) | `SubscriptionAppService.cs`, `SubscriptionExpirationCheckWorker.cs` (saatlik) | `@Scheduled` + ShedLock; proration hesabı domain servisinde | Ops | `PaymentPeriodType` enum değeri gün sayısı olarak kullanılıyor (30/365) — Java'da açık `dayCount` alanı |
| Stripe/PayPal + webhook | `StripeGatewayManager.cs` (legacy Plans API), `StripeControllerBase.cs` (imza doğrulama), `PayPalGatewayManager.cs` | stripe-java (Prices API) + `Webhook.constructEvent`; PayPal Orders Capture | Ops | Tenant eşleşmesi `Customer.Description`=tenancyName — kırılgan, metadata'ya taşınmalı |
| Faturalama | `[Core]/MultiTenancy/Accounting/` + `InvoiceAppService` | JPA + DB sequence tabanlı `InvoiceNumberGenerator` | Ops | |
| Host/Tenant dashboard | `HostDashboardAppService.cs`, `TenantDashboardAppService.cs`; NG'de gridster + 13+ widget | REST metrik endpoint'leri + Next.js dashboard | F4 | Plan F4-m1 |

### 2.4 Platform servisleri

| Özellik | AspNetZero gerçekleşmesi (kanıt) | Spring hedef karşılığı | Faz | Parite notu |
|---|---|---|---|---|
| Audit log (listeleme, Excel, retention+backup) | `[App]/Auditing/AuditLogAppService.cs`; `ExpiredAuditLogDeleterWorker` (3 dk) silmeden önce backup çağırır | HTTP audit interceptor → `audit_logs`; retention Quartz job (F3) | F2 | Plan F2-m4; backup adımı atlanırsa uyum riski |
| Entity history | `[Core]/EntityHistory/EntityHistoryHelper.cs` (OU/Role/Tenant); **varsayılan KAPALI** (`[EF]` modülü satır 46) | Hibernate Envers `@Audited` | F2 | Canlıda kapalıysa kapsam daraltılabilir (Soru-6) |
| Settings (Host→Tenant→User zinciri) | `AppSettingProvider.cs` (1337 satır); `isInherited:false`, `isEncrypted:true` istisnaları | `settings(scope, scope_id, key, value)` + `SettingDefinition` registry + Redis cache | F2 | Plan F2-m5; fallback semantiği birebir kopyalanmalı (§3.1-R13) |
| Localization (15 dil XML + DB override, dil CRUD) | `[Core]/Localization/AbpZeroTemplate/*.xml` (~1300+ anahtar), `LanguageAppService.cs` | `MessageSource` köprüsü + DB destekli dinamik çeviri | F3 | Plan F3-m5 (ARCHITECTURE modül etiketi F2 — statik bundle erken, DB CRUD F3); tenant çeviri override katmanı kaybedilmemeli |
| Timezone yönetimi | Windows TZ ID'leri (`TimeZoneService.cs`, TimeZoneConverter) | `java.time ZoneId` (IANA); settings üzerinden fallback | F2 | ETL'de Windows→IANA dönüşümü zorunlu, yoksa `ZoneId.of()` patlar |
| Background job altyapısı | ABP job manager (`AbpBackgroundJobs`); Hangfire hazır ama **kapalı** (`WebConsts.cs`) | Quartz JDBC store (clustered) + job log + retry | F3 | Plan F3-m1; parite hedefi Hangfire değil fiili ABP job store |
| Periyodik worker'lar (5+) | Subscription/audit/şifre worker'ları; Web.Host **ve** Web.Mvc'de çift kayıt | `@Scheduled` + ShedLock (tek çalıştırma garantisi) | F3 | Mevcut çift-çalışma riski yapısal olarak çözülür |
| Notifications (tanım, abonelik, inbox, mass) | `AppNotifier.cs`, `NotificationAppService.cs`; izin bağımlı tanımlar; OU hedefli mass notification | `notifications` + `user_notifications` + kanal SPI | F3 | Plan F3-m2; `UseOnlyIfRequestedAsTarget` semantiği birebir — yoksa toplu e-posta/SMS patlaması |
| E-posta/SMS kanalları | `EmailRealTimeNotifier.cs`, `SmsRealTimeNotifier.cs` (Twilio); SMTP ayarları **DB'de** | Dinamik `JavaMailSender` resolver + Twilio SDK | F3 | DEBUG'da NullEmailSender davranışı korunmalı |
| SignalR gerçek zamanlı katman (3 hub) | `[Web.Host]/Startup/Startup.cs:252-254` | Spring WebSocket + STOMP + `SimpMessagingTemplate`; online takip Redis registry | F3 | Protokol değişir → frontend SignalR istemcisi tamamen değişir (§5) |
| Chat + friendships | `ChatMessage.cs` (çift satır, `SharedMessageId`), `FriendshipManager.cs`; tenant'lar-arası; XSS sanitizer | STOMP + aynı çift-satır modeli + OWASP Java HTML Sanitizer | Ops | Plan F3-m4 "opsiyonel — kullanım kararına bağlı" (Soru-1); tek-satır modele geçiş read-state'i bozar |
| Dosya saklama (BinaryObjects) | `[Core]/Storage/BinaryObject.cs` — dosyalar DB'de `byte[]`; `TempFileCacheManager` | `StorageProvider` SPI (Local + S3/MinIO) + `binary_objects` metadata; indirme token'ı | F3 | Plan F3-m3; DB-blob → S3 veri taşıma ETL kalemi |
| Profil fotoğrafı (+Gravatar seçeneği) | `ProfileAppService.cs:299-492`; `User.ProfilePictureId` → BinaryObject | Storage servisi + kısa ömürlü imzalı URL | F3 | Base64 endpoint yerine presigned URL — bilinçli iyileştirme |

### 2.5 Entegrasyon ve yan özellikler

| Özellik | AspNetZero gerçekleşmesi (kanıt) | Spring hedef karşılığı | Faz | Parite notu |
|---|---|---|---|---|
| Webhooks (abonelik, olay, yeniden gönderim) | `AppWebhookDefinitionProvider.cs` — **yalnız `TestWebhook` tanımlı**; `[App]/WebHooks/*.cs` | Subscription/event/attempt tabloları + HMAC imzalı POST + Spring Retry (veya Svix) | Ops | Fiilen iş webhook'u yok — kapsam gerçek kullanıma göre |
| Dynamic entity properties | `AppDynamicEntityPropertyDefinitionProvider.cs` (User kayıtlı; 4 input tipi); 5 AppService | PostgreSQL JSONB + şema doğrulama veya EAV — hazır karşılık YOK | Ops | En pahalı parite kalemi; kullanım yoksa atlanmalı (Soru-3) |
| GDPR / kişisel veri toplama | `UserCollectedDataPrepareJob` + 3 provider (`[App]/Gdpr/`, Chat/Profil/Foto) | Export job + dosya teslimi + bildirim | Ops | F4'e alınması önerilir; tenant silme soft-delete — hard delete GDPR tasarımı ayrıca gerekli |
| GraphQL API (salt-okunur) | `[GraphQL]/Queries/*.cs`; `WebConsts.GraphQL.Enabled=true`, playground prod'da açık | Spring for GraphQL veya hiç taşımama | Ops | Tüketen istemci var mı? (Soru-3); playground açıklığı mevcut risk |
| Health checks | `[Web.Core]/HealthCheck/AbpZeroHealthCheck.cs` + HealthChecksUI | Actuator liveness/readiness + custom `HealthIndicator` | F1 | HealthChecksUI karşılığı yok → Grafana (ARCHITECTURE §7) |
| Swagger/OpenAPI | `[Web.Core]/Swagger/*` özel filtreler | springdoc-openapi + `OperationCustomizer` | F1 | F1 kapsamında |
| Rate limiting | Yalnız passwordless login (IP 5/60sn) | Bucket4j + Redis — login/refresh sıkı limit | F4 | Kapsam genişletme = bilinçli iyileştirme |
| CORS | `Startup.cs:80-98` — `App:CorsOrigins`, wildcard subdomain | `CorsConfigurationSource` explicit allowlist | F1 | |
| Redis cache | Modül tanımlı ama `UseRedis` **yorum satırında** (`AbpZeroTemplateWebCoreModule.cs:83-89`) | Spring Data Redis baştan aktif | F1 | Mevcutta fiilen in-memory — çok-instance tutarsızlık riski Spring'de kapatılır |
| UI customization (13 tema, dark mode) | `UiCustomizationSettingsAppService.cs`; `themeN.App.UiManagement.*` ayarları | Tema JSON endpoint'i; Next.js'te tek tasarım sistemi | Ops | 13 tema paritesi önerilmez — frontend kararına bağlı |
| Dashboard customization (widget yerleşimi) | `DashboardCustomizationAppService.cs`; JSON setting olarak | `user_dashboard` JSON saklama | Ops | |
| Web.Public landing sitesi | `[Web.Public]/Controllers/AccountController.cs` — accessToken query-string handoff | Statik site/Next.js; handoff → one-time token | Ops | Mevcut handoff güvenlik açısından birebir kopyalanmamalı |
| MAUI mobil uygulama | `[Maui]/Pages/*` (login, tenant, user CRUD, dashboard) | Kapsam dışı önerisi; REST API zaten yeterli | Ops | Soru-4 |
| Migrator (host + tenant DB'leri) | `[Migrator]/MultiTenantMigrateExecuter.cs` | Flyway versioned migration; multi-tenant için programatik `Flyway.migrate()` | F1 | Tarihsel 51 migration taşınmaz → tek `V1__baseline.sql` |
| Plugin yükleme (`wwwroot/Plugins`) | `Startup.cs:166` | Doğrudan karşılık yok; ihtiyaçta Java SPI | Ops | Kullanım beklenmiyor |
| Cache yönetimi (maintenance ekranı) | `[App]/Caching/CachingAppService.cs` | Actuator caches / custom evict endpoint | Ops | Küçük admin paritesi |
| Web log görüntüleme | `[App]/Logging/WebLogAppService.cs` | Loki/Grafana (JSON log) — dosya indirme endpoint'i taşınmaz | F4 | Observability ile karşılanır (ARCHITECTURE §7) |

### 2.6 Critic (eksiklik denetçisi) bulguları — matrise dahil edilen gap'ler

| Özellik (GAP) | Kanıt | Spring hedef karşılığı | Faz | Parite notu |
|---|---|---|---|---|
| MVC (jQuery/Metronic) tam admin UI — 29 controller + Views | `[Web.Mvc]/Areas/AppAreaName/Controllers/` (ör. `UsersController.cs`) | Taşınmaz — tek SPA hedefi | Ops | Envanterde UI dökümü yoktu; iki frontend'ten hangisi canlıda? (Soru-4) |
| AspNetZeroRadTool (RAD kod üreteci, 550+ şablon) | `aspnet-core/AspNetZeroRadTool/` (`config.json`, `FileTemplates/`) | Birebir karşılık yok; OpenAPI codegen + Maven archetype/şablon modülü | Ops | Ekip RAD'a bağımlıysa üretkenlik kaybı planlanmalı (Soru-7) |
| Tenant görünüm özelleştirme (light/dark logo, custom CSS) | `TenantSettingsAppService.cs:1282-1321`, `[Web.Core]/Controllers/TenantCustomizationController.cs`, `Tenant.CustomCssId` | Files modülü (logo BinaryObject) + settings | F3 | Envanterde atlanmıştı — files kapsamına eklendi |
| Application.Client proxy kütüphanesi (Flurl) | `[App.Client]/ApiClient/AbpApiClient.cs`, `AccessTokenManager.cs` | Gereksiz — OpenAPI'den istemci üretimi | Ops | MAUI kapsam kararına bağlı |
| Chat'te dosya/resim paylaşımı | `[Web.Core]/Controllers/ChatControllerBase.cs:27` (`UploadFile`) | STOMP + storage entegrasyonu | Ops | Chat kapsamına bağlı (Soru-1) |
| Bildirim Inbox sayfası | `[NG]/src/app/shared/layout/nav/app-navigation.service.ts:100`, `app-routing.module.ts:17` | In-app inbox API (F3) + UI (F4) | F3 | Plan F3-m2 inbox'ı zaten içeriyor — UI unutulmamalı |
| Şablonlu e-posta altyapısı | `[Core]/Authorization/Users/UserEmailer.cs:31`, `[Core]/Net/Emailing/EmailTemplateProvider.cs:14` | Thymeleaf/FreeMarker HTML şablonları + `JavaMailSender` | F2 | F2'deki e-posta doğrulama/şifre reset'in ön koşulu |
| BrowserCacheCleanerController | `[Web.Core]/Controllers/BrowserCacheCleanerController.cs` | Taşınmaz (SPA cache-busting build ile çözülür) | Ops | Küçük yardımcı endpoint |

**Critic doğrulama notları (verification_notes):** "Security report" özelliği kodda YOK (grep 0 sonuç).
Login denemelerinin Excel export'u kodda yok (`UserLoginAppService` yalnız listeleme içerir); "invalid
user download" ise Excel import akışının parçası olarak VAR. Bilinen özellik listesindeki diğer tüm
maddeler (OU, delegation, session timeout, 2FA, chat, webhooks, dynamic properties, entity history,
audit, dashboard'lar, subscription, invoice, UI customization, notifications, Excel, LDAP, OpenIddict,
health checks, GDPR, account linking) hem kodda hem envanterde çapraz doğrulandı.

**Faz dağılımı (74 satır):** F1: **13** · F2: **14** · F3: **12** · F4: **5** · Opsiyonel: **30**.
Opsiyonel kalemlerin büyüklüğü, kapsamın §3.3 sorularıyla daraltılmasının neden kritik olduğunu gösterir.

---

## 3. Riskler ve bilinmeyenler

### 3.1 Teknik riskler

1. **ABP'nin örtük davranışları (en kritik risk):** tenant data filter (`IMustHaveTenant`/`IMayHaveTenant`),
   soft-delete filtresi, audit kolonu doldurma, otomatik `UnitOfWork` transaction'ları ve
   `SetTenantId` framework içinde görünmez çalışır. Spring'de her biri açıkça kurulmazsa sonuç
   **tenant verisi sızıntısıdır**. Mitigasyon: Hibernate `@Filter` + AOP + `TenantIsolationIT`
   (plan F1 kabul kriteri) + F4'te Postgres RLS.
2. **Zayıf mevcut token varsayılanları:** HS256 simetrik anahtar appsettings'te; access 1 gün,
   refresh 365 gün (`[App.Shared]/AppConsts.cs`). Spring'de bilinçli iyileştirme: 15 dk access,
   7 gün rotate refresh, F4'te RS256+JWKS (ARCHITECTURE §4). Karşı-risk: mevcut sistem her istekte
   DB/cache doğruladığı için iptal anlıktır; Spring'de anlık iptal isteniyorsa Redis denylist eklenmeli.
3. **`SimpleStringCipher` + kodda sabit passphrase** üç yerde: `enc_auth_token` (SignalR query-string),
   `Tenant.ConnectionString`, `isEncrypted` setting'ler (tüm harici login secret'ları). ETL'de
   decrypt/re-encrypt gerekir; WebSocket auth modeli yeniden tasarlanır, birebir kopyalanmaz.
4. **Çift şifre hash formatı:** host admin eski ABP hash'i, diğer kullanıcılar ASP.NET Identity v3
   PBKDF2 (`[EF]/Migrations/Seed/`). Kullanıcılar taşınacaksa Java'da PBKDF2-Identity-v3 çözen köprü
   `PasswordEncoder` (ilk başarılı login'de BCrypt'e re-hash) yazılmalı; aksi durumda toplu şifre reset.
5. **SQL Server → PostgreSQL tip eşleme:** IDENTITY→identity/sequence, `nvarchar`→`text/varchar`,
   `datetime2`→`timestamptz`; **timezone değerleri Windows ID** (IANA'ya dönüştürülmeli);
   `Clock.Provider` açıkça UTC değil → DB'deki DateTime'ların gerçek zone'u ETL öncesi denetlenmeli.
6. **API yüzeyi elle yeniden yazılır:** ABP dynamic web api tüm AppService'leri `/api/services/app/*`
   olarak otomatik üretir; ABP response zarfı `{result, success, error}` Angular interceptor'larının
   beklentisidir. Spring'de controller katmanı + RFC 9457 ProblemDetail sözleşmesi → **mevcut Angular
   istemci birebir çalışmaz**; nswag proxy'leri springdoc + `openapi-generator` (typescript) ile
   yeniden üretilir, `API_BASE_URL` token'ı korunur.
7. **SignalR → STOMP protokol değişimi:** 3 hub endpoint'i istemcilere gömülü; `@microsoft/signalr`
   yerine STOMP istemcisi → chat/bildirim/QR frontend katmanı yeniden yazılır.
8. **Metronic lisansı ve build zinciri:** tema repo içine gömülü (npm değil), 13 tema × RTL, ayrı
   gulp bundle adımı (`bundles.json`). Yeni frontend'te Metronic lisansının devam edip etmeyeceği
   ticari karar; `abp-ng2-module` ABP'siz backend ile çalışmaz.
9. **Zamanlanmış işlerde tek-instance varsayımı:** worker'lar hem Web.Host hem Web.Mvc'de kayıtlı;
   replikasyonda çift çalışır. Spring'de Quartz clustering + ShedLock zorunlu (plan F3 kabul kriteri).
10. **Mevcut kod anomalileri (bilinçli olarak taşınmayacaklar):** HSTS yalnız Development dalında
    (`Startup.cs:179-183`); 2FA provider seçimi kullanıcıya-özel olmayan global cache anahtarında
    (eşzamanlı login'de yanlış provider riski); `CreateRandomPassword` kriptografik olmayan
    `System.Random` ile; reCAPTCHA User-Agent whitelist bypass'ı; GraphQL playground prod'da açık;
    repoda gerçek görünümlü anahtarlar (Recaptcha key'leri, `tempkey.jwk/rsa`) → **migration öncesi rotasyon**.
11. **Permission grant semantiği:** izinler DB'de string isimle (`AbpPermissions`); hiyerarşik grant
    (parent → child'lar) ve Host/Tenant `MultiTenancySides` ayrımı Spring'in düz authority modeline
    birebir çevrilemez → custom evaluator + grant verisi ETL eşlemesi.
12. **OpenIddict password grant** OAuth 2.1'de kaldırıldı; Spring Authorization Server desteklemez.
    OAuth istemcileri (varsa) auth code + PKCE'ye taşınmalı; mevcut token/consent verileri taşınamaz.
13. **Setting fallback zinciri semantiği:** User→Tenant→Application→appsettings default; ama
    `isInherited:false` istisnaları ve `SettingScopes.All` kalemleri var. Birebir kopyalanmazsa
    sessizce yanlış değerler döner; client-visibility whitelist kaçırılırsa secret sızar.
14. **Stripe entegrasyon borçları:** legacy Plans API (Prices'a geçilmeli); tenant eşleşmesi
    `Customer.Description` konvansiyonuyla; webhook idempotency yalnız durum makinesi guard'ında —
    davranışlar bilinçli korunmalı/iyileştirilmeli.
15. **CI/CD yok, secrets düz metin:** tüm otomasyon lokal ps1; compose/Helm'de SA ve sertifika
    parolaları gömülü. Spring tarafında pipeline + secret yönetimi sıfırdan kurulur (plan F1'de CI var).

### 3.2 Bilinmeyenler

- Canlı ortam konfigürasyonu görünmüyor: Hangfire/Redis/EntityHistory/GraphQL bayraklarının
  üretimdeki gerçek değerleri (kod varsayılanları: Hangfire kapalı, Redis kapalı, EntityHistory
  kapalı, GraphQL açık).
- Üretim veri hacmi (kullanıcı, audit, chat, `AppBinaryObjects` blob boyutu) → ETL süre/pencere planı.
- Tenant-başına ayrı DB kullanan tenant olup olmadığı.
- OpenIddict'i kullanan harici OAuth istemcisi olup olmadığı.
- Metronic ve ASP.NET Zero lisanslarının durumu/yenileme takvimi.
- Angular UI mı MVC UI mı canlıda? (İkisi de tam teşekküllü.)

### 3.3 Netleştirme soruları (kullanıcıya)

1. **Chat/friendships** (ve chat'te dosya paylaşımı) üretimde fiilen kullanılıyor mu? (F3-m4 kapsam kararı)
2. **SaaS ticari katman** — edition/subscription/Stripe/PayPal/fatura — aktif mi? Hangi gateway, recurring var mı? (En büyük Opsiyonel blok)
3. **GraphQL** ve **dynamic entity properties**'i tüketen gerçek istemci/senaryo var mı, yoksa kapsam dışı bırakılabilir mi?
4. **MAUI mobil**, **Web.Public landing** ve **Web.Mvc admin UI** üretimde mi? Spring geçişinde hangileri kapsama girecek?
5. **LDAP/ActiveDirectory, WsFederation, OpenIddict OAuth sunucusu, passwordless/QR login, user delegation, linked accounts** — hangileri gerçekten kullanılıyor?
6. **Mevcut üretim verisi migrate edilecek mi?** (kullanıcı+şifre hash'leri, audit geçmişi, chat, dosyalar) Yaklaşık hacim nedir? Entity history canlıda açık mı?
7. **Hedef bulut/altyapı** nedir (K8s? managed PostgreSQL/Redis?) ve ekip RAD tool'a (kod üreteci) bağımlı mı?
8. **Tenant çözümleme:** subdomain modeli üretimde kullanılıyor mu, yoksa `X-Tenant` header (F1) yeterli mi? Tenant-başına ayrı DB kullanılıyor mu?

### 3.4 Varsayımlar

- **Varsayım:** Üretim konfigürasyonu şablon varsayılanlarına yakındır (Hangfire kapalı → ABP job
  store aktif; Redis kapalı → in-memory cache; EntityHistory kapalı).
- **Varsayım:** API sözleşmesi birebir korunmayacaktır; frontend yeni OpenAPI sözleşmesine göre
  üretilen istemcilerle çalışacaktır (bkz. plan "API sözleşmesi" kuralı).
- **Varsayım:** Hedef DB PostgreSQL'dir (ARCHITECTURE §10-2); SQL Server'da kalma opsiyonu ETL'yi
  basitleştirir ama RLS/maliyet avantajları kaybedilir.
- **Varsayım:** Tek üretim monoliti ve tek paylaşımlı DB vardır; tenant-başına DB fiilen kullanılmamaktadır.
- **Varsayım:** Aktif frontend Angular'dır; Web.Mvc ve MAUI üretimde birincil değildir.
- **Varsayım:** Cutover için kısa (saatler mertebesinde) bakım penceresi kabul edilebilir.
- **Varsayım:** Mevcut kullanıcılar taşınacaktır ve şifre hash köprüsü (Identity v3 PBKDF2 →
  BCrypt re-hash) yazılacaktır; kabul edilmezse toplu şifre reset iletişimi planlanır.
- **Varsayım:** 13 Metronic tema paritesi hedeflenmez; tek tasarım sistemi yeterlidir.

---

## 4. Migration stratejisi

### 4.1 Karar

**Greenfield parity build (paralel geliştirme) + sonda tek seferlik ETL + big-bang cutover.**
Mevcut .NET sistemi üretimde değiştirilmez; Spring tarafı `IMPLEMENTATION-PLAN.md`'deki 4 fazla
fonksiyonel pariteye getirilir; veri, cutover penceresinde SQL Server → PostgreSQL tek seferlik
ETL ile taşınır (plan F4-m6: kolon eşleme scripti + satır sayısı ve örneklem hash doğrulaması).

### 4.2 Neden alternatifler elendi

| Alternatif | Neden elendi |
|---|---|
| **Strangler / incremental API-split** (facade arkasında endpoint endpoint devretme) | (a) Tek monolit + **ortak DB şeması ABP'ye sıkı bağlı** — `Abp*` tabloları, örtük filtreler ve `UnitOfWork` semantiği iki farklı ORM/framework'ün aynı şemaya eşzamanlı yazmasını güvensiz kılar; (b) **API sözleşmesi birebir korunmayacak** (dynamic `/api/services/app/*` + ABP response zarfı → yeni REST tasarımı), dolayısıyla facade'ın "şeffaf devretme" ön koşulu yok; (c) auth/token modelleri farklı (TokenValidityKey vs rotate-refresh) → oturum köprüsü başlı başına proje olur. Strangler'ın maliyeti burada değer üretmez. |
| **Big-bang rewrite-in-place** (mevcut repo/sistem üzerinde dönüştürme) | Dil/runtime tamamen değişiyor — "yerinde" dönüştürülecek ortak kod yok; mevcut sistemin feature-freeze altında uzun süre dondurulması iş riskini büyütür; ara teslim/geri bildirim imkânı yoktur. |
| **Hibrit çift-yazma (dual-write)** | İki şemaya eşzamanlı yazım tutarlılık ve idempotency yükü getirir; ABP'nin örtük davranışları (audit kolonları, tenant atama) çift-yazmada replike edilemezdi. |

Greenfield parity'nin ek gerekçesi: Faz planı her fazda **çalışan, test edilebilir dikey dilimler**
üretir (F1 kabul kriterleri IT'lerle kanıtlı); mevcut sistem referans (oracle) olarak canlı kalır ve
parite testleri iki sistem karşılaştırılarak yazılabilir.

### 4.3 Cutover planı taslağı

1. **T-4 hafta — Parite dondurma:** .NET tarafında feature freeze; kapsamdaki tüm özellikler için
   parite kontrol listesi kapanır (faz kabul kriterleri + §2 matrisi).
2. **T-3 hafta — ETL provaları:** staging'de tam ETL (şema eşleme + `SimpleStringCipher` decrypt +
   Windows→IANA TZ dönüşümü + hash köprüsü işaretleme + BinaryObject→S3 taşıma); doğrulama raporu:
   tablo bazında satır sayısı + örneklem hash + iş kuralı sondaları (ör. aktif tenant sayısı,
   permission grant toplamları).
3. **T-2 hafta — Kabul:** kritik akış senaryoları (login → tenant switch → CRUD → audit) iki sistemde
   karşılaştırmalı smoke; yük testi (p95 login < 300ms hedefi, plan F4); ZAP taraması temiz.
4. **T-0 — Cutover penceresi:** (a) .NET tarafında yazma trafiği durdurulur (maintenance mode);
   (b) final delta ETL + doğrulama raporu; (c) DNS/ingress yeni sisteme çevrilir; (d) smoke suite;
   (e) trafik açılır. Not: OAuth/OIDC istemcileri ve harici webhook URL'leri (Stripe) bu pencerede
   yeniden yapılandırılır.
5. **T+2 hafta — Hypercare:** yoğun izleme (Grafana/alerting), hata bütçesi, eski sistem standby.

### 4.4 Geri dönüş (rollback) stratejisi

- **Karar noktası:** cutover sonrası ilk N gün (öneri: 14) "rollback penceresi"dir; eski .NET sistemi
  ve SQL Server **read-only/standby** tutulur, kapatılmaz.
- **Tetikleyiciler:** kritik akışta (login, tenant izolasyonu, ödeme webhook'u) çözülemeyen P1;
  veri bütünlüğü ihlali; SLA'yı aşan performans regresyonu.
- **Mekanizma:** DNS/ingress eski sisteme geri çevrilir (dakikalar); pencere içinde yeni sistemde
  üretilen delta veri için ters-ETL scripti hazır bulundurulur (kapsamı bilinçli dar: kullanıcı/rol
  değişiklikleri + audit; ödeme hareketleri manuel mutabakat).
- **Riski küçülten kural:** rollback penceresi boyunca geri taşınması maliyetli özellikler (ör. yeni
  şifre politikası zorlaması, toplu veri düzenlemeleri) devreye alınmaz.
- Şema evriminde de aynı felsefe: Flyway'de undo yok — **forward-fix** stratejisi (plan "fazlar arası
  kurallar").

---

## 5. Frontend değerlendirmesi

### 5.1 Envanterdeki Angular bulguları (S7)

- **Sürüm/mimari:** Angular 19.1.x (`@angular/core ^19.1.7`), NgModule tabanlı + standalone karışık;
  3 ana modül (root/account/app) + tüm admin sayfaları lazy; ~20+ admin rotası, route `data.permission`
  ile ABP izin kontrolü. NgRx yok — durum `AppSessionService` + `abp.js` global objesinde.
- **Tema:** Metronic **asset olarak gömülü** (npm paketi değil), 13 tema varyantı + RTL; Angular CLI
  dışında ayrı **gulp bundle** adımı (`bundles.json`) — CI'da unutulursa stiller kırılır; lisanslı ürün.
- **API bağı:** nswag → tek dev `service-proxies.ts` (`nswag/service.config.nswag`,
  `{controller}ServiceProxy`, Luxon, `API_BASE_URL` token'ı); ABP response zarfı `{result, success}`
  interceptor'larda varsayılıyor; `abp-ng2-module` 12 (TokenService, PermissionChecker) **ABP'siz
  backend ile çalışmaz**.
- **Kimlik/gerçek zamanlı:** MSAL 4 + angular-oauth2-oidc 19; `@microsoft/signalr` 8 (chat, bildirim);
  refresh token yalnız rememberMe'de; `enc_auth_token` localforage'da (XSS'e açık saklama).
- **UI yığını:** PrimeNG 19, ngx-bootstrap 19, ngx-charts + chart.js, gridster2, image-cropper,
  sweetalert2, quill, FullCalendar; yarn + Karma/Jasmine + ESLint 9.

### 5.2 Seçenek karşılaştırması

| Kriter | A) Angular devamı | B) React/Next.js yeniden yazım |
|---|---|---|
| Pariteye ulaşma hızı | Görünürde hızlı; ama `abp-ng2-module`, `abp.js` sözleşmesi, ABP response zarfı ve SignalR istemcisi sökülüp yeniden yazılmalı — "devam" fiilen büyük refactor | Sıfırdan ama temiz sözleşme (OpenAPI client + STOMP); parite kapsamı bilinçli daraltılabilir (minimum admin panel) |
| Backend bağımlılığı | ABP'ye özgü katmanlar her sayfaya sızmış durumda | Yok — yeni API sözleşmesine doğar |
| Tema/lisans | Metronic lisansı + gulp pipeline devam eder; 13 tema bakım yükü | Tek modern tasarım sistemi (ör. shadcn/ui); lisans bağımlılığı kalkar |
| İşe alım/ekosistem | Angular havuzu daha dar | Daha geniş havuz; SSR/edge desteği |
| Mevcut ekip bilgisi | Ekip %100 Angular ise ciddi avantaj | Öğrenme eğrisi maliyeti |
| Riskler | ABP söküm refactor'unun gizli maliyeti; hibrit NgModule mirası | Tüm admin ekranlarının yeniden yazımı = en büyük efor kalemi |
| Proxy üretimi | `openapi-generator` typescript-angular ile nswag akışı korunur | `openapi-generator` typescript-fetch/axios |

### 5.3 Öneri

**B — React/Next.js** (ARCHITECTURE §9 ile tutarlı). Gerekçe: Angular tarafında "devam" seçeneği
gerçekte devam değildir — `abp-ng2-module`/`abp.js`/response-zarfı/SignalR katmanları her durumda
sökülüp yeniden yazılacaktır; madem çekirdek istemci katmanı yeniden yazılıyor, bu yatırım modern ve
lisans bağımsız bir stack'e yapılmalıdır. F4'te **minimum admin panel** hedeflenir: login + tenant
switch, dashboard, user/role/OU CRUD, audit görüntüleme, settings, bildirim inbox'ı (plan F4-m1).
13 tema ve gridster dashboard özelleştirmesi parite kapsamına alınmaz (Opsiyonel).
**Koşullu istisna:** ekip %100 Angular ise ve işe alım planı yoksa A'ya dönülür; bu durumda da
`abp-ng2-module` yerine ince bir soyutlama katmanı yazılması zorunludur.
