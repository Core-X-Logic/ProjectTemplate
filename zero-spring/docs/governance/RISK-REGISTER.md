# Risk Register + Mitigasyon Takvimi

Skala: Olasılık (L/M/H) × Etki (L/M/H) → Seviye. Durum: `Open` · `Mitigating` · `Closed`.

---

## ⚠️ Şablonu klonluyorsanız — devraldığınız açık kısıtlar

Aşağıdaki kayıtların çoğu bu şablonun **kendi geçmişidir** ve sizi bağlamaz. Ama şunlar
**hâlâ açıktır** ve klonunuza aynen geçer. Kabul edip etmediğinize karar verin:

| ID | Ne | Neden önemli |
|---|---|---|
| ~~**PROD-R21**~~ | ~~`/api/users` bellekte sayfalıyor~~ | ✅ **KAPANDI (Q-03).** İki aşamalı sorguya geçildi (id sayfası → fetch join), sıra geri yükleniyor. `PagedListingIsNotSlicedInMemoryIT` (7) + `UserServiceOrderRestorationTest` (5). ArchUnit Rule 1 donmuş sayısı 6 → 0; ayrıca test profilinde `hibernate.query.fail_on_pagination_over_collection_fetch=true` ile `join fetch` şekli de kapatıldı |
| **PROD-R23** | Branch protection **ücretsiz planda kurulamıyor** (403) | CI zinciri raporlar ama **kırmızı check push'u engellemez**. Blokaj insan disiplininde. `SETUP-NEW-PROJECT.md` §2 |
| **PROD-R27** | Dockerfile'ı **hiçbir gate build etmiyor** | İmajdaki sertleştirmeler (prod profili, heap tavanı, healthcheck) hiçbir otomatik kontrolde doğrulanmıyor |
| **PROD-R6** | Rate limit bucket'ları **JVM-local** | N replika = N × limit. Ayrıca istemci kimliği `X-Forwarded-For`'a dayanır: **proxy'nin bu başlığı ezmesi zorunludur**, kodla garanti edilemez |
| **PROD-R16** | `kid` / anahtar rotasyonu / access-token iptali **yok** | Rotasyon tüm oturumları düşürür; access token 15 dk boyunca iptal edilemez |
| **Issue #1** | `POST /api/tenants` **admin kullanıcı oluşturmuyor** | Açılan kiracıya giriş yapılamaz. Self-registration akışının ön koşulu. **Karar (2026-07-20, kapanış):** bilinen kusur olarak devrediliyor — **yapısal kapanışı bloklamıyor**, ürün onboarding'ini blokluyor. Kapatma: Dalga 6'nın ilk dikey dilim adaylarından; herhangi bir klon tenant-onboarding ile canlıya çıkmadan önce **zorunlu**. Doğrulama (o zaman): IT — tenant create → yeni admin ile login mutlu yolu, negatif kanıt eski kodda (login imkânsız), artı canlı smoke |
| ~~**R-30**~~ | ~~31 ham `hasAuthority('...')` literali~~ | ✅ **KAPANDI (Q-02).** 7 dosyada 31 literal sabite taşındı, **hiçbir değer değişmedi** (üçlü kilit korundu). Modül döngüsü nedeniyle `audit`/`settings`/`tenancy` kendi sabit sınıflarını taşıyor (`SaasPermissions` deseni), `PermissionRegistryAlignmentTest` çoğaltmayı güvenli kılıyor. ArchUnit Rule 3: 31 → 0 |
| ~~**R-31**~~ | ~~`ROLES_MANAGE` izin ağacında yok~~ | ✅ **KAPANDI (Q-02).** Gerçek bulgu: `roles.manage` seeder tarafından **her Admin rolüne veriliyordu** ama ağaçta, iki mesaj paketinde, hiçbir `@PreAuthorize`'da ve frontend'de yoktu — hiçbir şeyi korumayan, görünmez ve geri alınamayan bir grant. Ağaca eklemek yerine **kaldırıldı**. Veri tarafı `V7__drop_dead_roles_manage_permission.sql` ile temizlendi (aşağıya bakın) |
| ~~**R-35**~~ | ~~Excel export **sınırsız**~~ | ✅ **KAPANDI (W5-3).** İki export yolu da (`/api/users/export`, `/api/audit-logs/export`) ortak `BoundedExport` üzerinden `maxRows+1` çekiyor; `maxRows+1` dönerse **reddediyor** (400 ProblemDetail), **kesmiyor** — sessizce kısaltılmış bir export, tam görünen ve olmayan bir dosyadır. `zero.export.max-rows` varsayılan 10 000, `@PostConstruct` ile boot'ta doğrulanıyor. **Kritik nokta aşağıda: R-41'e bakın** |
| **R-41** ⚠️ **yeni — ölçülerek bulundu** | Bir export'un sınırı **SQL'de mi Java'da mı** uygulandığını davranış testleri **göremez** | W5-3'ün ilk turunda `Pageable`'ı yok sayan bir fetcher (tüm satırları oku, sonra Java'da kes) **dışarıdan birebir aynı** davrandı: limitte 200, bir üstünde 400. **Dört testin dördü de yeşil kaldı, BUILD SUCCESS.** Yani ret davranışı kilitliydi, görevin var olma nedeni olan **tahsis** davranışı değil. `PagedListingIsNotSlicedInMemoryIT` de göremez — koleksiyon fetch olmadığı için `HHH90003004` hiç yayınlanmaz. **Kapatıldı:** `ExportRowBoundIT` artık `org.hibernate.SQL`'e `ListAppender` bağlayıp sorgunun satır limiti taşıdığını assert ediyor, önünde bir vacuity guard ile (sıfır statement yakalanırsa test "aşağıdaki assertion hiçbir şeyi belgelemezdi" diyerek düşer). Mutation ile kanıtlandı: **her iki export'ta ayrı ayrı RED**. **Kalan:** assertion "bir limit var" der, "limit tam olarak `maxRows+1`" demez — `org.hibernate.SQL` bind parametrelerini basmadığı için. Farklı bir limit uygulayan fetcher hâlâ geçer |
| **R-42** ⚠️ **yeni** | **Kimliği doğrulanmış** uçlarda hiçbir hız sınırı yok — export dâhil | `RateLimitFilter` yalnız **5 anonim** yolu kapsıyor (`zero.ratelimit.paths`). Config'in kendi gerekçesi authenticated yolları *"already bounded by holding a valid token"* diye dışlıyor. Bu **logout için doğru** (ucuz), **export için değil**: yetkili tek principal `/api/audit-logs/export`'u eşzamanlı ve sınırsız çağırabilir. W5-3 **satır** eksenini kapattı, **hız** eksenini kapatmadı — ikisi bağımsız. Düzeltmek per-path bucket gerektirir = mimari genişletme, Dalga 5'te bilinçle **yapılmadı** |
| **R-43** | `GET /api/organization-units` sınırsız (`findAllByOrderByCodeAsc()`) | **Kabul edildi.** UI ağacı bütün istiyor, satır sayısı organizasyon yapısıyla sınırlı (yüzler), `memberCounts(ids)` **tek batch** sorgu — N+1 yok. Audit tablosu gibi zamanla tek yönlü büyüyen bir şey değil. Sayfalamak özelliği kırardı |
| **R-36** | `RoleService.toDto` rol başına bir `countByRolesId` COUNT'u atıyor (N+1) | Mevcut durum, Q-03'te kapsam dışı bırakıldı |
| **R-37** ⚠️ **düzeltildi** | ~~`audit.domain` ve `notification.domain` paketlerinin `package-info.java`'sı yok~~ → Gerçek kapsam **8 paket / 12 entity**: `audit.domain` (3), `identity.ou` (1), `identity.password` (1), `notification.domain` (1), `saas.edition` (2), `saas.feature` (1), `saas.subscription` (2), `settings.domain` (1) | **Kaydın ilk hâli iki yönden yanlıştı.** (a) Kapsam 3× dar yazılmıştı. (b) Daha önemlisi: bunlar **modül kökü değil**, `allowedDependencies` beyan eden 5 modülün **internal alt paketleri**. Yani Rule 4'ün gerekçesi (*"sınır beyan etmeyen modül"*) bu 12'nin **hiçbirine** uymuyor. Gerekçenin geçerli olduğu yer `config`/`seed`/`shared` ve orada entity olmadığı için kural oraya **hiç bakmıyor**. Kural ayrıca **ters işaretli**: `identity.domain` kuralı geçiyor çünkü package-info'su `@NamedInterface` ile paketi **dışa açıyor**. Çözüm: boş dosya eklemek **değil** (kuralı susturur, Modulith semantiği değişmez), kuralı yeniden formüle etmek — **W5-1** · ✅ **KAPANDI (W5-1).** Kural `entitiesLiveUnderADeclaredModuleRoot()` olarak yeniden yazıldı (ADR-0016): entity'den modül köküne **yukarı yürüyor**, `@ApplicationModule` beyanı arıyor, kaynağı `withoutComments()` okuyor, sıfır entity görmeyi **kendisi ihlal** sayıyor. Donmuş 12 → **0**, `package-info.java` sayısı **15 → 15** (tek dosya eklenmedi). Belirleyici negatif kanıt: temiz klonda `saas/package-info.java` silindiğinde **eski kural 6/6 yeşil, BUILD SUCCESS** — koruduğunu iddia ettiği şey yokken sessiz kaldı; yeni kural aynı silmede **5 ihlalle kırmızı** |
| ~~**R-39**~~ | ~~`logout` refresh token sahipliğini doğrulamıyor~~ | ✅ **KAPANDI (W5-2b).** Sahiplik kontrolü eklendi; **statü kodu bilerek 204'te bırakıldı** — 403/404 dönmek iki durumu ayırt edilebilir kılar ve ucu bir *varlık oracle*'ına çevirir: statü farkı "bu string canlı bir refresh token'dır" bilgisini onaylar. Ayrım operatörün göreceği WARN satırına taşındı. `SessionOwnershipIT.logoutRejectsARefreshTokenBelongingToAnotherUser` — **iki gerçek kullanıcı, iki gerçek token**; yük taşıyan iddia saldırganın aldığı statü değil, **kurbanın token'ının hâlâ redeem edilebilmesi** |
| ~~**R-40**~~ | ~~Impersonation ticket'i redeem edene bağlı değil~~ | ✅ **KAPANDI (W5-2b).** `consume(String, Long callerUserId)` — aktör bir **parametre**, ticket'tan okunan alan değil; çağıran, ait olması gereken principal'ı adlandırmadan `Ticket` elde edemiyor, yani bağ çağrı yerinde **unutulamaz**. Yanlış aktör ticket'ı **yakmıyor** (aksi hâlde sızan ticket, meşru hand-off'a DoS'a dönerdi). Bilinmeyen/süresi dolmuş/yabancı üçü de ayırt edilemez. `SessionOwnershipIT.impersonationTicketRejectsRedemptionByAnotherActor` — ikinci aktör **farklı tenant'tan** |
| **R-38** | **Modulith, string ile çözülen kenarı göremiyor** — orijinal örnek kapatıldı, **sınıf açık ve ölçüldü** | `@FilterDef` `identity.domain`'deyken `audit`/`notification` entity'leri ona ad ile bağlanıyordu ve `ModularityTests` **geçti**. Tanım `shared.domain`'e taşınarak kapatıldı. Alt-tür tablosu aşağıda |

**R-38 alt-türleri** — ölçüt: bağ string ile mi çözülüyor · modül sınırını aşıyor mu · Modulith görür mü · kırılma sessiz mi:

| Alt-tür | Bulgu | Sınır aşıyor | Modulith görür | Kırılma | Aksiyon |
|---|---|---|---|---|---|
| ~~**A — URL yol dizeleri**~~ ✅ | ~~7 literal / 4 dosya / 3 modül~~ → gerçek kapsam **5 kenar / 3 modül**: `SecurityConfig:104` (identity→localization), `SubscriptionAccessCheck:34,35,36` (saas→identity ×2, saas→localization), `AuditLogInterceptor:87` (audit→identity — ilk kayıtta **yoktu**) | Evet | Hayır | Sessiz | ✅ **KAPANDI (W5-4).** Detay ve kalan risk aşağıda |
| **B — i18n anahtarları** | Her iki bundle 100 anahtar; yalnız 45 `Permission.*` korunuyor → **55 korumasız**. Tüketici `getMessage(key, null, key, locale)` yazıyor: eksik anahtar kullanıcıya **ham anahtar** olarak gider. Eşitliği bugün yalnızca bir **yorum satırı** talep ediyor | Evet | Hayır | Sessiz | **W5-5** |
| **C — Setting adları** | 10 tüketici literali; `SettingDefinitions.` sabit referansı `settings` dışında **sıfır**. `SmtpEmailSender` `catch (RuntimeException)` ile `@Value` fallback'ine **sessizce** düşüyor | Evet | Kenarı evet, içeriği hayır | 8 gürültülü / **2 sessiz** | **W5-6** |
| **D — Hibernate filtre adları** | 15 nokta; `AccountService` sabitler mevcutken **ham literal** yazıyor | Evet | Hayır | Sessiz | **W5-6** |
| **E/F/G — cache · ShedLock · `@Value`** | 4+1 · 2 · 13+4 | Kısmen / hayır | Kısmen | Muhtemelen gürültülü (**doğrulanamadı**) | kabul |
| **I — frontend izin dizeleri** | 132 eşleşme, merkezî sabit yok | Modulith kapsamı dışı | Hayır | Sessiz | ayrı dalga |
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

**Dalga 5'te ölçülen ve temiz çıkan yüzeyler (aksiyon gerekmez):**

- **Log-flood yok.** `src/main/java` genelinde satır/öğe başına log **yok** — tüm `for`/`while`/`forEach`
  gövdelerinin taranmasında tek isabet `RequestBodyFormats:161`, o da tek seferlik açılış uyarısı.
  Export döngülerinin ikisi de log basmıyor. `permitAll` yollarda saldırgan-kontrollü interpolasyon
  yok: `AccountService:66` bilinçli olarak **disclosure'suz** yazıyor.
- **Sayfalanan uçların tamamı sınırlı.** `spring.data.web.pageable.max-page-size: 100`
  (`application.yml:62`); `src/main`'de `@PageableDefault` ve `Pageable.unpaged()` **yok**.
  `AuditLogService`'in `PageRequest` yeniden kurduğu dört nokta `pageable.getPageSize()`'ı koruyor,
  yani resolver'ın 100 tavanını **miras alıyor**, atlamıyor.
- **İstek gövdeleri sınırlı.** `zero.request.max-body-bytes` 1 MB (`/api/**`), `zero.ratelimit.max-body-bytes`
  16 KB (5 anonim yol). Ayrı bloklar olması bilinçli — F1'i üreten şey, 16 KB'nin *rate limiter'ın
  bir özelliği* olması ve yalnız limiter'ın koştuğu yerde geçerli olmasıydı.

### R-38A — erişim kararları URL **string**'ine bağlı, hiçbir araç göremiyor (2026-07-19)

Beş erişim kararı, **başka bir modülün** URL yüzeyini string olarak adlandırıyordu. Bağ bir string
olduğu için derleyici, Modulith ve bytecode ArchUnit'in **üçü de kördü**: `/api/localization`
yeniden adlandırıldığında `permitAll("/api/localization/**")` sessizce eşleşmeyi bırakır, tüm
gate'ler yeşil kalır ve login ekranı, login formunu çizebilmek için gereken sözlüğü artık
toplamaya çalıştığı kimlik bilgisinin arkasında bulur.

| ID | Bulgu | Şiddet | Durum |
|---|---|---|---|
| R-38A-1 | `AuditLogInterceptor:87` `identity`'nin iki login yolunu hardcode ediyordu; `audit` modülünün `allowedDependencies = {"shared"}` beyanı **yanlıştı** | Orta | **Closed — kenar SİLİNDİ.** Konteyner `preHandle`'a `HandlerMethod`'u zaten veriyor; muafiyet artık `@EndpointPolicy(AUDIT_EXEMPT)` olarak handler'ın kendi beyanı. `audit` içinde başka modülün hiçbir yolu kalmadı. `AuditExemptionIT` (2) |
| R-38A-2 | `SecurityConfig:104` (`identity` → `localization`) ve `SubscriptionAccessCheck:34-36` (`saas` → `identity`, `localization`) — dört kenar daha | Orta | **Mitigating — bilinçli olarak beyan EDİLMEDİ.** `saas -> identity` beyanı, bir string sorununu çözmek için `saas`'a `identity`'nin **her tipini** açardı ve yeniden adlandırmada yine kırmızıya dönmezdi. Bunun yerine iki yönlü mutabakat: `SecurityPathBindingIT` (6), `SubscriptionExemptPathBindingIT` (4), Rule 6 (donmamış, sıfırdan zorluyor) |
| R-38A-3 | `application.yml:125` **yanlış bir iddia taşıyordu**: throttle listesinin `SecurityConfig` ile karşılaştırıldığını söylüyordu; `RateLimitMediaTypeFailClosedIT:513` aynı beş yolu hardcode ediyor ve `SecurityConfig`'i hiç okumuyor | Düşük (gürültü) / Orta (yanlış güven) | **Closed** — iddia artık **doğru**: `SecurityPathBindingIT.everyAnonymousBodyHandlerIsThrottled` gereken kümeyi `@RequestBody` alan ANONYMOUS handler'lardan **türetiyor**, listeden kopyalamıyor |
| R-38A-4 | `ArchitectureRules:66-68` "SecurityConfig kaynaktır, bu liste yalnızca sonucu kaydeder" diyordu; bu iddiayı **hiç kimse** doğrulamıyordu | Düşük | **Closed** — `theIntentionallyAnonymousSetEqualsTheAnnotatedSet` (surefire) + `everyAnonymousClaimIsGrantedByAPermitAllMatcher` (failsafe) zinciri kapatıyor |
| **R-38A-5** | **`zero.saas.subscription-gate.exempt-paths` = `/api/**` yazan bir operatör, abonelik kapısını TAMAMEN devre dışı bırakır, temiz boot eder ve tek bir WARN satırı alır.** Startup doğrulayıcısı yalnızca **çözülebilirliği** kontrol eder, **genişliği** değil | **Orta — AÇIK** | **Open (bilinçli).** Override, canlı bir olayda operatörün elindeki kaçış kapağı; ölümcül yapmak onu geri alırdı. Yazım hatası **boot'u reddediyor** (`SubscriptionExemptPathsStartupCheck`, 4 birim testi), genişletme **WARN** ile ve kapsadığı talep etmeyen rotaların adıyla loglanıyor. Kapatılmadı, **görünür** kılındı |

**Yük taşıyıcı katman kaynak metni DEĞİL, çalışan filtre zinciri.** Bu, üç ardışık denetim turunda
**ölçülerek** öğrenildi: metinsel parser'ın her kapatılan yazımı bir sonrakini doğurdu.

| Tur | Kaçış | Ölçülen sonuç |
|---|---|---|
| 1 | `String[]` sabiti: `.requestMatchers(PARTNER_PATHS).permitAll()` | 137/271 **yeşil**, tenancy admin yüzeyi `permitAll`'da |
| 2 | Nokta ile ad arasında satır sonu: `.` ⏎ `requestMatchers(...)` | 138/271 **yeşil** — tarama bitişik token arıyordu |
| 3 | `.requestMatchers(...)` — javac unicode'u lexing'den **önce** çözer | **yeşil**; iki dedektör de nokta göremediği için *hemfikir* oldu, uyuşmazlık guard'ı ateşlenemedi |
| 3 | `SecurityConfig` **dışında** kusursuz okunabilir bir literal | **yeşil** — form ve sahiplik kontrollerini geçiyor ama grant kümesine hiç girmiyor |

**Sonuç:** akıcı bir DSL'in kaynak metnini taramak sızdırmaz hâle getirilemez. Metinsel kurallar
**korunuyor** — dosya ve satır adı verdikleri için hızlı geri bildirim olarak değerliler — ama
garanti artık `FilterChainReachabilityIT`'de: her maplenmiş pattern'e, o pattern'in **maplemediği**
bir HTTP metodu, kimlik bilgisiz gönderiliyor. `401` ⟹ zincir kapalı, `401 dışı` ⟹ açık; açıksa her
handler `@EndpointPolicy(ANONYMOUS)` taşımak **zorunda**.

Ayrımın kendisi de negatif kanıtla kuruldu — aynı istek, iki konfigürasyon:

```
KAPALI zincir | PATCH /api/tenants | 401
AÇIK   zincir | PATCH /api/tenants | 405 allow=GET,POST
KAPALI zincir | GET   /api/tenants | 401
AÇIK   zincir | GET   /api/tenants | 401     ← naif GET işe yaramaz: aynı statü, zıt anlam
```

Yan etkisizlik **iddia edilmedi, iki bağımsız yolla kanıtlandı**: dispatcher maplenmemiş metotta
hiç `HandlerMethod` üretmiyor, ve audit tablosu altı probda **sıfır** satır artıyor (servis edilen
tek bir GET onu 1 artırıyor — yani ölçüm aleti canlı). `TRACE` ve CORS preflight `OPTIONS`
**kullanılmıyor**: ikisi de her iki konfigürasyonda aynı cevabı veriyor, yani prob değiller.

Bu katman, iki metinsel katmanın da geçirdiği **dört kaçışı** yakaladı: unicode escape,
`SecurityConfig` dışı grant, ikinci `SecurityFilterChain`, ve `WebSecurityCustomizer.ignoring()`
(sonuncusu yolu zincirden tamamen çıkarır — prob *neden*ini değil, **cevap verip vermediğini**
sorduğu için yine de düşüyor).

| ID | Bulgu | Şiddet | Durum |
|---|---|---|---|
| **R-38A-6** | Prob, pattern'in **maplemediği** bir metot atıyor. İkinci bir filter chain üzerinde `securityMatcher` + **metot-kapsamlı** grant (`requestMatchers(HttpMethod.GET, ...)`) bu yüzden görünmüyor | **Düşük — AÇIK** | **Open (kayıtlı).** Kazayla oluşmaz: ikinci filter chain + `securityMatcher` + metot-kapsamlı matcher, üçü de kasıtlı. Gerçek maruziyet için dördüncüsü gerekli — `@PreAuthorize`'sız bir handler — ki **Rule 5 onu build'de engelliyor**. Kapatma yolu biliniyor ve ucuz: bilinen tek `SecurityFilterChain` dışında bean olmadığını assert etmek (E5/E6/E10'u kökünden keser) ya da pattern başına **her maplenmiş metodu** problamak — ikincisi handler'ları çalıştırır, yani yan etkisizliği kaybettirir |

> `429` **yeşil sayılmıyor:** `RateLimitFilter`, `AuthorizationFilter`'ın **önünde** oturuyor ve
> zincir karar vermeden cevap verebilir. Throttle'a takılan bir prob koşuyu **INCONCLUSIVE**
> yapıyor, "açık" değil. Aksi hâlde kapalı ama throttle'lı bir yol açık okunurdu.

> Rule 6 **donmuyor** (`FreezingArchRule` yok): bugün sıfır ihlalde, dolayısıyla ham kontrol
> ediliyor. Yeni bir kuralı dondurmak, ilk koşuda bulduğu neyse onu sessizce borç defterine yazar —
> bu depoda tam olarak geri alınması gereken hamle. Donmuş depo **beş dosya, hepsi sıfır** kalıyor.

## P2-A (Stripe billing dilimi) riskleri — 2026-07-20 eklendi

Dilim: webhook intake + idempotency + sunucu-otoriter aktivasyon (`saas.billing` alt paketi, V8,
`/api/billing/*`). Kapanan kaynak-sistem hataları: mükerrer webhook → 400 → sonsuz retry
(dedup `UNIQUE (provider, event_id)` + `on conflict do nothing`; negatif kanıt: dedup mutasyonla
kapatıldığında `BillingWebhookIT.duplicateDeliveryIsAcknowledgedWithoutReprocessing` **409'la
kırmızı**), ve aktivasyonun tarayıcı redirect'ine bağlanması (aktivasyon artık webhook
transaction'ının içinde; `subscription_events.actor = stripe-webhook` ile kanıtlı).

| ID | Bulgu | Şiddet | Durum |
|---|---|---|---|
| PROD-R36 | **Webhook, ortak anonim throttle'ın altında — ve iki arıza modu AYNI DEĞİL.** Anonim + `@RequestBody` olduğu için `zero.ratelimit.paths`'e girmesi gate tarafından ZORUNLU (`everyAnonymousBodyHandlerIsThrottled` türetiyor); Stripe teslimatları IP başına kapasiteyi (prod varsayılanı 10/dk) ve 16 KB gövde sınırını login uçlarıyla paylaşır. **429 = sınırlı gecikme:** kova dolduğunda retry başarır, kayıp yok. **413 = KALICI, İZSİZ KAYIP:** 16 KB üstü bir event gövdesi DETERMİNİSTİKTİR — her retry'da aynı 413'ü alır, Stripe'ın retry takvimi tükenir; ve `RateLimitFilter` handler'dan ÖNCE reddettiği için payload `webhook_events`'e hiç yazılmaz. Sonuç: para tahsil edilmiş, payment `NOT_PAID`'de takılı, sunucu tarafında SIFIR iz. Tek görünür yer Stripe dashboard'unun failed-webhooks listesi | Orta | **Open (kayıtlı).** Kapanış yolu: webhook path'ine özel gövde sınırı (gate'i zayıflatmadan). O gelene kadar mitigasyon OPERASYONEL: RELEASE-RUNBOOK §3.9 mutabakatı — Stripe dashboard failed-webhooks listesi, `NOT_PAID`'de takılı `payments` satırlarıyla periyodik karşılaştırılır |
| PROD-R37 | **`createCheckoutSession`'ın canlı HTTP çağrısı hiçbir otomatik testte koşmuyor.** SPI arkasında bilerek ince; IT'ler onu kayıt eden bir sahteyle değiştiriyor (imza doğrulaması ise GERÇEK `StripeBillingProvider` koduyla test ediliyor — offline HMAC). Gerçek anahtar/hesap/parametre hataları ilk kez canlıda görünür | Orta | **Open (bilinçli — dilim sözleşmesi).** Kapanış yolu: RELEASE-RUNBOOK'a Stripe test-mode checkout smoke'u |
| PROD-R38 | **`RECURRING_PAYMENT_SUCCEEDED` saklanıyor ama işlenmiyor.** `invoice.paid`/`subscription_cycle` doğru eşleniyor, `IGNORED` + 200 ile kaydediliyor; dönem uzatma sonraki dilim. Payload'lar `webhook_events`'te backfill için duruyor — yenileme gelirse abonelik uzamaz, kayıt kaybolmaz | Düşük | **Open (planlı)** |
| PROD-R39 | **Zero-decimal para birimleri.** `StripeBillingProvider.minorUnits` ×100 çevirir; JPY/KRW tarzı bir para birimi 100 kat fazla faturalanırdı. Katalog bugün USD/EUR tarzı satıyor; sub-cent tutar `longValueExact` ile gürültülü patlar | Düşük | **Open (kayıtlı)** — yeni para birimi eklemeden önce `minorUnits` genişletilmeli |
| PROD-R40 | **Tenant self-checkout ve frontend üçlü kilidin 2/3'ü bu dilimde yok.** Checkout host-only (`subscriptions.manage`, mevcut sabit; `CheckoutEndpointIT` negatif yetki testi ile). Frontend `<Can>`/route-guard ve ekran sonraki dilim | Düşük | **Open (dilim sözleşmesi — kayıtlı)** |

## P2'-A (PayTR + multi-provider dilimi) riskleri — 2026-07-20 eklendi

Dilim: `BillingProviderRegistry` (id çakışması → boot reddi, mutasyon kanıtlı), PayTR bildirim
intake'i (`/api/billing/webhook/paytr`, form-urlencoded, hash gövdede, offline HMAC doğrulaması),
`PAYMENT_FAILED` geçişi (`NOT_PAID→FAILED`; `PAID` KALIR — mutasyon kanıtlı), "OK" gövde
sözleşmesi (byte-eşit; `"ok\n"` mutasyonu kırmızı). Strateji: ADR-0017.

| ID | Bulgu | Şiddet | Durum |
|---|---|---|---|
| PROD-R41 | **PayTR bildirim retry takvimi BELGESİZ.** Stripe'ın aksine PayTR, başarısız (non-"OK") bildirimin kaç kez ve hangi aralıklarla yeniden denendiğini yayınlamıyor; 429/413/500 sonrası teslimatın ne zaman tükendiği bilinmiyor. Bu, runbook mutabakatını Stripe'takinden bile daha kritik yapar: takvimin sonu görünmezse tek emniyet düzenli karşılaştırmadır | Orta | **Open (kayıtlı).** Mitigasyon OPERASYONEL: RELEASE-RUNBOOK §3.9 PayTR paneline genişletildi — PayTR mağaza paneli işlem listesi, `NOT_PAID`/`FAILED`'de bekleyen `payments` satırlarıyla periyodik karşılaştırılır. Kalıcı kapanış: sorguyla-mutabakat job'u (PROD-R43 backlog) |
| PROD-R42 | **"OK" ack sözleşmesi, gelecekteki global hata-işleyici/advice değişikliklerine karşı KIRILGAN.** Tahsilat, 200 durum koduna değil yanıt GÖVDESİNİN byte-eşitliğine bağlı; yarın eklenecek bir `ResponseBodyAdvice`, bir sarmalayıcı, hatta content-negotiation değişikliği parayı sessizce keser (uygulama yeşil, PayTR "failed" okur, esnafa aktarım durur) | Orta | **Mitigated (test ile).** `PayTRWebhookIT` üç yerde ham gövde `isEqualTo("OK")` + `text/plain` iddia eder; mutasyon kanıtı kayıtlı (`"ok\n"` → kırmızı). Bu satır, o testleri "kozmetik" sanıp gevşetecek kişiye uyarıdır: o assertion tahsilatın kendisidir |
| PROD-R43 | **iyzico dilimi bekliyor; sorguyla-mutabakat (reconciliation-by-query) job'u backlog'da.** ADR-0014'ün ikinci savunma hattı (`BillingProvider.fetchStatus()` benzeri sorgu modeli) hâlâ yok; iyzico'nun retrieve modeli bu job'un doğal tasarım girdisi olduğundan bilerek o dilime ertelendi. O gelene kadar kaçan webhook'un tek telafisi §3.9 elle mutabakatı | Orta | ✅ **KAPANDI (P2'-B).** `IyzicoBillingProvider` (SDK 2.0.142) + SPI'ye `supportsQueryConfirmation`/`confirmBySessionQuery` + `BillingConfirmationService` (retrieve-otoriter tek yol: webhook funnel'ı, browser callback'i ve job AYNI çağrıyı kullanır) + `BillingReconciliationJob` (ShedLock `billing-reconciliation`, saatlik, `NOT_PAID`/`FAILED` + `min-age` taraması; V9 `payments.provider` ile atfetme). Kanıt: `IyzicoWebhookIT` + `BillingReconciliationJobIT` (vacuity-guard'lı) + üç mutasyon kırmızısı. **Sınır:** yalnız iyzico sorgulanabilir — PayTR sorgu API'si yakalanmadı, PayTR/Stripe/atfedilmemiş satırlar job'da SAYILARAK atlanır ve PROD-R41 + §3.9 onların ağı olarak AÇIK kalır |
| PROD-R44 | **PayTR get-token isteğinin alıcı kimlik alanları (email, user_ip, ad/adres/telefon) YER TUTUCU.** `CheckoutRequest` host-operated akışta alıcı kimliği modellemiyor; adaptör belgeli placeholder gönderiyor. Canlı çağrı zaten test edilmiyor (PROD-R37 deseni PayTR'a da uygulanır: IT'ler kaydeden sahte kullanır, hash/token formülleri offline vektörle test edilir). Gerçek alıcı verisi bağlanmadan canlı PayTR checkout'u ÇALIŞMAYABİLİR — ilk canlı smoke bunu ölçmeli | Orta | **Open (kayıtlı — dilim sözleşmesi).** Kapanış yolu: checkout DTO'suna alıcı alanları + RELEASE-RUNBOOK'a PayTR test-mode checkout smoke'u |

## P2'-B (iyzico + mutabakat job'u dilimi) riskleri — 2026-07-20 eklendi

Dilim: `IyzicoBillingProvider` (CF initialize + retrieve, SDK 2.0.142; `X-IYZ-SIGNATURE-V3` offline
doğrulama kendi kodumuzda), retrieve-otoriter tasarım (webhook/callback yalnız TETİKLEYİCİ —
aktivasyon tek yol: `BillingConfirmationService` → sağlayıcı sorgusu), `/api/billing/webhook/iyzico`
+ `/api/billing/callback/iyzico` (dörtlü gate kaydı), V9 `payments.provider`,
`BillingReconciliationJob` (ShedLock). PROD-R43 bu dilimle kapandı (yukarıda).

| ID | Bulgu | Şiddet | Durum |
|---|---|---|---|
| PROD-R45 | **`iyziReferenceCode`'un retry'lar arası tekilliği BELGESİZ (UNKNOWN).** Doc "teslimat başına tekil" der; "istek başına tekil" okunursa AYNI event'in retry'ı YENİ referans koduyla gelebilir — o zaman `webhook_events` dedup'ı (katman 1) retry'ı yeni kayıt olarak kabul eder. Ölçülemedi: sandbox'ta retry tetiklemek operatör hesabı ister (P2'-C smoke) | Düşük | **Open (kayıtlı — tasarımla zararsızlaştırıldı).** Bu sağlayıcı teslimattan HİÇ aktive olmadığı için ikinci katman taşıyıcıdır: her kabul edilen event aynı idempotent retrieve-confirm yolundan geçer, `PAID` guard'ı + satır kilidi çifte aktivasyonu keser. `IyzicoWebhookIT.fraudReviewBlocksActivationUntilAConfirmingRecheck` yeni-referans-kodlu yeniden teslimatı bilerek simüle eder. Referans kodu YOKSA dedup anahtarı `token:STATUS`'a düşer (PayTR şekli); ikisi de yoksa 400 |
| PROD-R46 | **iyzico CF initialize'ın alıcı alanları YER TUTUCU + callback'in HTTP metodu BELGESİZ.** (a) Buyer zorunlu alt-alanları doc'ta sabitlenmemiş; PROD-R44 deseniyle tarihsel-güvenli set (id, ad, soyad, TCKN placeholder `11111111111`, email, adres, şehir, ülke, ip) + billing address placeholder gönderiliyor — canlı initialize REDDEDEBİLİR. (b) `callbackUrl`'e dönüşün GET mi POST mu olduğu yazmıyor; controller ikisini de kabul ediyor ama ölçülmedi | Orta | **Open (kayıtlı — dilim sözleşmesi).** İlk canlı sandbox smoke (P2'-C) ikisini de ölçer. Callback yalnız tetikleyici olduğu için yanlış metod = gecikme maliyeti (webhook + job ağ), para kaybı değil |
| PROD-R47 | **İki canlı iyzico çağrısı (`createCheckoutSession`, `confirmBySessionQuery`) hiçbir otomatik testte koşmuyor** — PROD-R37 deseninin iyzico'ya uzantısı, ve burada DAHA kritik: retrieve artık mutabakat job'unun da tek doğruluk kaynağı. IT'ler her ikisini SPI dikişinde sahteyle değiştirir; imza doğrulaması GERÇEK kodla, offline vektörle test edilir. Gerçek anahtar/hesap/parametre hataları ilk kez canlıda görünür | Orta | **Open (bilinçli — dilim sözleşmesi).** Kapanış yolu: P2'-C sandbox smoke'u (CF initialize → ödeme → webhook/retrieve → aktivasyon zinciri). Retrieve transport hatası tasarımda yutulmaz: webhook'ta 500+rollback (retry yeniden sorar), job'da payment başına WARN + sonraki koşu |
| PROD-R48 | ~~**Mutabakat taraması satır-sayısı SINIRSIZ**~~ → stack-review Finding 2 ile İKİ boyutta sınırlandı: satır (`max-rows-per-pass` 50, cap+1 probe, kesilme WARN — sessiz cap yok; tarama SORGUDA yalnız sorgu-yetenekli sağlayıcıya filtreli, yoksa çözülemez satırlar sınırlı pencereyi tıkardı) ve süre (`query-timeout` PT30S — SDK'da knob YOK, iyzipay-java 2.0.142 `HttpClient` 140 sn connect+read hardcode eder, javap ile ölçüldü; sınır bizim katmanda daemon worker + `future.get`). `lock-at-most-for` aritmetiği: 50×30 sn = 25 dk en kötü → PT45M tavan (job'daki yorum) | Düşük | **Mitigated (test ile).** `BillingReconciliationJobIT.capExceededScanTruncatesLoudlyAndLaterPassesDrainTheRest` (cap ısırıyor + WARN + kalan sonraki pass'lerde eriyor; mutasyon: filtre düşürülünce kırmızı). **Kalan (kayıtlı):** pencere en-eski-id sıralı — hiç çözülmeyen iyzico satırları (süresiz fraud incelemesi, terk edilmiş checkout) KALICI kesik taramada slot tutar; WARN metni tam bunu söyler ve §3.9 incelemesi ister |

## Mitigasyon takvimi (özet)

- **F1 ✅:** R-01, R-02 Closed; R-10 taşınmama kararı.
- **F2 ✅ (Closed):** R-07, R-12, R-13, R-14, R-16, R-17, R-18, R-19, R-20, R-21.
- **F2 kısmi — R-11:** permission model kapandı (PermissionTreeIT); genel durum **Mitigating** (grant verisi ETL → F6).
- **F2 kapanış (commit):** R-22 (mvnw LF — commit + CI koşusuyla doğrula).
- **F2 devam / slice C:** R-09 (React ekranları — impersonation/audit/settings UI), R-08 (@FilterJoinTable/ArchUnit), R-23 (düşük artıklar).
- **F3:** R-03 (WS auth), R-06 koşullu jti denylist, mutasyon testi.
- **F5:** R-15 (SaaS ticari katman).
- **F6:** R-03/R-04/R-05/R-11 veri tarafı (ETL); secret rotasyonu (R-10) cutover.
