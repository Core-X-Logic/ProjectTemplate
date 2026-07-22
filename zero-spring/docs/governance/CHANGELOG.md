# Değişiklik Günlüğü

[Keep a Changelog](https://keepachangelog.com/tr/1.1.0/) formatı ·
[Semantic Versioning](https://semver.org/lang/tr/)

> Bu dosya **sizin projenizin** günlüğüdür. Şablonun kendi inşa süreci
> `../history/CHANGELOG-template-build.md` altında arşivlendi — orada gördüğünüz faz/slice
> kavramları sizi bağlamaz.

## [Yayınlanmamış]

### Eklendi

- **CI release hattı prod-tetiklemeye hazır (altyapı, deploy YOK).** `docker-build` job'ına
  **kapılı** registry login + push adımları eklendi (`a8a8262`, CI 9/9 run 29910265282). Varsayılan
  **güvenli no-op**: `PUSH_IMAGE=true` (Actions Variable) olmadan imaj build edilip sertleştirmesi
  doğrulanır ama registry'e **push edilmez**. Push açıkken imaj `sha-<kısa-sha>` (+ opsiyonel
  `IMAGE_EXTRA_TAG`) etiketiyle push edilir, tag+digest step summary'e yazılır; GHCR'da `REGISTRY_TOKEN`
  boşsa built-in `GITHUB_TOKEN`'a (`packages: write`) düşer. **Sıra garanti:** hardening assert
  push'tan önce koşar — doğrulanmamış imaj push edilemez. Yeni Actions Variables: `PUSH_IMAGE`,
  `IMAGE_EXTRA_TAG`, `REGISTRY_USERNAME`; Secret: `REGISTRY_TOKEN` (opsiyonel). SETUP §6 tam
  Variables/Secrets tablolarıyla yeniden yazıldı.

### Değişti

- **`release` guarded deploy fail-fast sertleşti.** `DEPLOY_ENABLED=true` iken artık `IMAGE_REGISTRY`
  eksikse de (yalnız `DEPLOY_COMMAND` değil) anlaşılır hatayla durur — girdiler eksikken sessiz yanlış
  deploy yerine gürültülü hata. Dry-run plan ve `DEPLOY_ENABLED!=true` no-op davranışı değişmedi.

### Kaldırıldı
### Düzeltildi
### Güvenlik

- **Secret'lar repoya yazılmadı.** Registry + deploy kimlik bilgileri yalnız Actions Secrets/Variables
  ve prod secret store üzerinden tüketiliyor; `docker login` `--password-stdin` ile, kimlik bilgisi
  log'lanmıyor. Değişiklik kapsamı yalnız `.github/workflows/ci.yml` + docs — domain/API/auth/tenant/
  frontend davranışı değişmedi.

---

## [1.0.0-rc.1] — 2026-07-22

Release candidate. `513ede6` üzerinde donduruldu, CI 9/9 (run 29907487912): 586 backend + 149
frontend test, image hardening ✓, npm audit 0, gitleaks temiz, V1..V10 doğrulandı. Blocker yok;
açık kalanlar org-policy / operatör-ops / next-phase (RISK-REGISTER RC bölümü). Bu sürüme dahil
olan değişiklikler aşağıdadır.

### Eklendi

- **iyzico + retrieve-otoriter doğrulama + mutabakat job'u (P2'-B).** Hiçbir webhook/callback
  payload'ı tek başına aktive etmez: her tetik tek huniye düşer, iyzico'ya `retrieve` ile sorulur,
  yalnız `paymentStatus SUCCESS` + `fraudStatus 1` aktive eder (mutasyon kanıtlı). V9:
  `payments.provider` (çapraz-sağlayıcı yönlendirme reddi). ShedLock'lu saatlik mutabakat:
  takılı `NOT_PAID/FAILED` satırları sorguyla çözer (50/pass, gürültülü kesme; SDK'nın 140 sn
  gömülü timeout'una karşı PT30S dekoratör sınırı). Review'da yakalanan bayat-birinci-seviye-cache
  double-activation yarışı commit öncesi kapatıldı (barrier'lı eşzamanlılık IT'si önce kırmızı).
- **Tenant oluşturma artık kullanılabilir tenant üretiyor (Issue #1 kapanışı).** Tenant + Admin
  rolü + admin kullanıcısı tek transaction; parola verilmezse üretilir ve **yalnız bir kez**
  yanıtta görünür (dialog tek-seferlik gösterim + kopyala). Negatif kanıt eski kodda ölçüldü
  (admin login 401), canlı smoke + re-boot smoke koşuldu.

- **PayTR bildirimi + çoklu-sağlayıcı registry (P2'-A, ADR-0017).** Güncel sağlayıcılar PayTR +
  iyzico (iyzico sıradaki dilim); Stripe uyuyan global-pazar adaptörü, PayPal dışarıda.
  `POST /api/billing/webhook/paytr`: resmî formülle HMAC doğrulama (sabit-zaman), düz-metin
  byte-eşit `OK` ack'i (aksi = tahsilat yok — mutasyonla belgelendi), duplicate → `OK` +
  tek işleme, webhook-yazımlı `FAILED` sonrası hash-geçerli `success` → aktivasyon (iframe içi
  retry meşru). Throttle filtresi form gövdesini tükettiği için `CachedBodyHttpServletRequest`
  form POST'larda parametre API'sini önbellekten yanıtlıyor. Sınırlar: PROD-R41..R44.

- **Stripe billing çekirdeği (P2-A).** `BillingProvider` SPI + `StripeBillingProvider`
  (stripe-java 33.1.1), `POST /api/billing/webhook/stripe` (kimlik = imza; dört gate-kaydı tam),
  host-side `POST /api/billing/checkout` (`SUBSCRIPTIONS_MANAGE`), `V8__billing.sql`
  (`payments` + `webhook_events`). Kaynak sistemin iki ölçülmüş kusuru sınıf olarak kapandı:
  duplicate webhook artık idempotent (UNIQUE event id + tek transaction; 200, yeniden işleme yok)
  ve edition aktivasyonu tarayıcı redirect'ine değil webhook'un kendisine bağlı
  (server-authoritative). İkisi de mutasyon kanıtlı. Sınırlar: PROD-R36..R40.

### Doğrulandı

- **PayTR get-token token formülü, PayTR'nin resmî Hash Hesaplama aracıyla harici doğrulandı**
  (byte-birebir). Canlı get-token/bildirim/aktivasyon round-trip'i **koşulamadı** — PayTR paylaşılan
  test kimlik bilgisi yayınlamıyor, mağaza hesabı şart; operatör-bağımlı açık kalıyor (PROD-R44b).
  **iyzico canlı doğrulaması bilinçli olarak sonraki faza ertelendi** (kimlik bilgisi yok, PROD-R47) —
  kapatılmadı, kod tarafı offline kanıtlı.

### Değişti

- **`/api/auth/me` artık `twoFactorEnabled` yansıtıyor.** Profil 2FA kartı mevcut durumu tahmin
  eden heuristic'i bıraktı; enable/disable görünümünü **yalnız** backend'in otoriter `MeDto`
  alanından türetiyor. Additive/non-breaking (mevcut /me tüketicileri değişmedi); yalnız
  görünürlük, auth davranışı aynı. 2FA diliminin küçük takip maddesini kapatır.

- **Release-hardening: CI/config uyarı temizliği (davranış değişikliği YOK).** (a) GitHub Actions
  major uplift — `checkout` v4→v7, `setup-node` v4→v7, `setup-java` v4→v5, `upload-artifact` v4→v7,
  `download-artifact` v4→v8; hepsi Node 24 runtime, "Node 20 deprecated" uyarısı kalktı (koşuda
  0 satır). Yalnız version tag'i değişti; `with:` girdileri, cache, working-directory, artifact
  adları ve `needs:` gate sırası aynı — upload@v7/download@v8 handoff digest-eşleşmeli kanıtlandı.
  (b) `tsconfig.app.json` — `baseUrl` kaldırıldı (TS 5.x'te `paths` göreli çözülür; susturma değil,
  kökten). (c) `scrollable.css` — `@media (max-width: var(--breakpoint-lg))` → `64rem` (CSS custom
  property media-feature değerinde geçersizdi; kural ölüydü, build uyarı basıyordu). Frontend build
  0 warning, 140 test + backend 502 test yeşil, CI 8/8 (run 29822177991).

### Kaldırıldı
### Düzeltildi

- **Docker imajı artık CI'da build ediliyor ve sertleştirmesi doğrulanıyor (PROD-R27 kapandı).**
  Yeni `docker-build` gate'i backend imajını buildx ile build edip `docker image inspect` ile dört
  şeyi assert ediyor: non-root kullanıcı (`zero`), HEALTHCHECK, `SPRING_PROFILES_ACTIVE=prod`,
  container-farkındalı heap tavanı. Daha önce imajı hiçbir kapı build etmiyordu; sertleştirmeler
  sessizce çürüyebilirdi. Push yok — registry hedefi klonlayanın. CI 9/9 (run 29831107658).
- **Placeholder `release` adımı → parametrik, cloud-agnostic deploy scaffold.** `release` artık
  `docker-build`'i de bekliyor; bir "Deploy plan (dry-run)" adımı environment/image-ref/secret
  noktalarını yazıyor, guarded bir adım `DEPLOY_ENABLED=true` yapılana dek güvenli no-op kalıyor
  (gerçek komut `DEPLOY_COMMAND` secret'i ile enjekte edilir — hiçbir cloud CLI hardcode değil).
  **Davranış değişikliği yok** (uygulama/domain/API/permission dokunulmadı); klonlayan için
  `SETUP-NEW-PROJECT.md §6` deploy checklist'i eklendi.

### Güvenlik

- **JWT revocation sertleştirmesi (PROD-R16 F3+F4 daraltıldı).** (F3) Access token'lar additive bir
  `ims` (issued-millis) claim'i taşıyor ve kullanıcı-bazlı iptal işareti artık millis çözünürlükte
  karşılaştırılıyor: kimlik değişiminden aynı saniye hemen önce basılan bir token artık iptal
  ediliyor, sonraki re-login hayatta kalıyor — pencere 1 saniyeden saat çözünürlüğüne indi,
  login-loop yok (ims'siz eski tokenlar pre-F3 saniye davranışını korur, deploy-window loop da
  yok). (F4) Redis iptal-yazımı sınırlı retry+backoff ile deneniyor ve başarısızlıkta bir Micrometer
  sayacı + greppable WARN ile gözlemlenebilir (token/jti loglanmaz); okuma yolu değişmedi, hâlâ
  fail-closed. Üçü de mutasyon-kanıtlı; 586 backend test, CI 9/9. Durable outbox ertelenmiş residual.

- **JWT anahtar rotasyonu (kid key-ring) + access-token revocation (PROD-R16 kapandı).** Access
  token'lar artık `kid` başlığı taşıyor ve doğrulama, anahtar halkasından (active imzalar,
  previous'lar grace penceresinde doğrular) kid ile seçilen HS512 anahtarıyla yapılıyor — böylece
  imzalama anahtarı **kesintisiz rotate edilebiliyor** (ekle → active'i çevir → grace ≥ TTL →
  emekliye ayır; RELEASE-RUNBOOK §1.3-K). Bilinmeyen/emekli kid ve `none`/`HS256`-downgrade/`RS256`
  gibi alg-confusion denemeleri **reddediliyor**; kid'siz token (rolling-deploy'daki eski token)
  active anahtarla doğrulanıyor (imza yine zorlanıyor). **Access-token erken iptali:** Redis
  tabanlı revocation — jti bazlı (logout sunulan token'ı) ve kullanıcı bazlı `notBefore` (şifre
  değişimi + 2FA disable outstanding oturumları düşürür), doğrulama zincirinde her authenticated
  istekte enforce ediliyor. **Fail-closed:** Redis erişilemezse token reddedilir (fail-open yasak;
  rate-limit'in aksine güvenli local fallback yok). `enabled=true` ama servis yoksa decoder boot'u
  reddeder (enabled⟹enforced). API non-breaking (kid = header, jti = additive). Redis artık auth
  için **sert bağımlılık** (live-smoke'a Redis servisi eklendi). Güvenlik review'i 2 bulguyu commit
  öncesi kapattı, mutasyon-kanıtlı. Kanıt: 581 backend test, CI 9/9. Residual: Redis kesintisi
  auth'u reddeder (kısa TTL + Redis HA), asimetrik JWKS + granülarite limitleri kayıtlı.

- **İki-faktörlü kimlik doğrulama (TOTP + kurtarma kodları).** 2FA açık kullanıcı, şifresi doğru
  olsa bile ikinci adım olmadan **token alamaz**: `login` kısa-ömürlü, tek-kullanımlık, deneme-limitli
  bir challenge döner; `POST /api/auth/two-factor/verify` bir TOTP kodu veya kurtarma kodu kabul
  edince gerçek token'lar basılır. **Fail-closed + oracle yok** (her hata jenerik 401). TOTP secret
  AES-256-GCM ile şifreli saklanır (yeni `FieldEncryptionService`; anahtar `zero.crypto.field-key`,
  JWT secret gibi boot'ta doğrulanır, prod'da commit'li dev anahtarı reddedilir); kurtarma kodları
  BCrypt hashli, tek-kullanımlık, bir kez gösterilir. Self-service yönetim `/api/profile/two-factor/
  {setup,enable,disable,recovery-codes/regenerate}` (disable/regenerate şifre re-verify). Frontend:
  login ikinci-adım ekranı + profil 2FA kartı (QR + kurtarma kodları bir kez). Non-2FA login birebir
  değişmedi. Güvenlik review'i iki gerçek açığı commit öncesi kapattı (birinci-faktör başarısının
  lockout sayacını sıfırlaması → sınırsız brute-force; challenge/kurtarma consume TOCTOU → double-spend),
  ikisi de mutasyon-kanıtlı. `V10__two_factor.sql`. Kalan: PROD-R49..R52 (anahtar rotasyonu, kurtarma
  UX, admin 2FA-reset, SMS/WebAuthn/QR — sonraki faz). Kanıt: 547 backend + 147 frontend test, CI 9/9.

- **Rate limit artık dağıtık (Redis-backed) — çok-replikada tutarlı (PROD-R6 kapandı).** Bucket
  store'u JVM-local'den Redis'e taşındı (bucket4j-redis 8.10.1, Spring'in Lettuce'u yeniden
  kullanılır): `capacity` tek küme-geneli limit, N replika = N×limit değil. Redis kesintisinde
  **per-instance local fallback** (eski davranış) — asla fail-open (sınırsız brute force) ya da
  fail-closed (503 kilitleme) değil; dedup'lı WARN. `X-Forwarded-For` güven modeli (trusted-proxy
  sağdan, anti-spoof) **değişmedi**; Redis anahtarı çözülen gerçek istemciden kurulur, forged
  başlık taze bucket üretemez. Kimliksiz uçlar dışında davranış değişmedi (429 + ProblemDetail +
  Retry-After aynı); authenticated-endpoint throttle'ı bilinçle kapsam dışı (R-42). Kanıt:
  `DistributedRateLimitIT`/`WiringIT` (Testcontainers Redis) + `RateLimitDegradeTest`, 512 test,
  CI 9/9 (run 29841476694). `zero.ratelimit.redis.*` env-override'lı, güvenli varsayılan.

---

## Şablon temeli — 2026-07-19

Klonladığınız temel bu. Aşağıdakiler şablonun kendi sertleştirme turlarının **sonucudur**;
kendi günlüğünüzü yukarıya yazın.

### Güvenlik

- **Oturum iptali ve impersonation ticket'ı artık sahibine bağlı.** `logout`, sunulan refresh
  token'ın çağıranın kendi token'ı olduğunu doğruluyor; **statü bilerek 204'te bırakıldı** —
  403/404 dönmek ucu bir *varlık oracle*'ına çevirirdi (statü farkı "bu string canlı bir refresh
  token'dır" bilgisini onaylar). Ayrım operatörün göreceği WARN satırına taşındı.
  `ImpersonationTokenStore.consume(String, Long callerUserId)` — aktör bir **parametre**, ticket'tan
  okunan alan değil, yani bağ çağrı yerinde **unutulamaz**. Yanlış aktör ticket'ı **yakmıyor**;
  aksi hâlde sızan bir ticket meşru hand-off'a DoS'a dönerdi.
- **Export'lar sınırlı.** `/api/users/export` ve `/api/audit-logs/export` tüm scope'unu tek listeye
  çekiyordu; audit tablosu her servis edilen istekle büyüdüğü için ikincisi daha ağırdı. İkisi de
  ortak `BoundedExport` üzerinden `maxRows+1` çekiyor ve sınır aşılırsa **reddediyor** (400
  ProblemDetail), **kesmiyor** — sessizce kısaltılmış bir export, tam görünen ve olmayan bir
  dosyadır. `zero.export.max-rows` (varsayılan 10 000) boot'ta doğrulanıyor: `0` ile kurulum
  **açılmıyor**, her export'ta 500 üretmiyor.
- **Korumasız 6 handler'a yetki beyanı eklendi.** ArchUnit Rule 5 donmuş 6 → 0.
- **Erişim kararları artık string'e bağlı değil.** Beş karar başka bir modülün URL yüzeyini string
  olarak adlandırıyordu; bağ string olduğu için derleyici, Modulith ve ArchUnit'in üçü de kördü —
  `/api/localization` yeniden adlandırılsa `permitAll` sessizce eşleşmeyi bırakır ve login ekranı,
  login formunu çizmek için gereken sözlüğü toplamaya çalıştığı kimlik bilgisinin arkasında bulurdu.
  Çözüm `@EndpointPolicy`: handler'ın **kendi** beyanı, tarif ettiği şeyin üzerinde yaşıyor.
  Bu bir **claim**, asla bir **grant** değil — `ANONYMOUS` yazmak hiçbir şey açmaz, grant tek
  gözden geçirilebilir yerde (`SecurityConfig`) kalır ve testler **iki yönlü** mutabakat arar:
  grant'sız claim de, claim'siz grant da build'i kırar. `@Target(METHOD)` bilinçli — tip seviyesinde
  bir claim, controller'a eklenecek bir sonraki metodu sessizce kapsardı.
  `audit` kenarı **beyan edilmedi, silindi**: konteyner `preHandle`'a `HandlerMethod`'u zaten
  veriyor, dolayısıyla `audit` başka modülün yolunu adlandırmayı tamamen bıraktı ve
  `allowedDependencies = {"shared"}` — depodaki en dar sınır — olarak kaldı.
  `saas → identity` bilinçle beyan **edilmedi**: bir string sorununu çözmek için `saas`'a
  `identity`'nin her tipini açmak, kapsüllemeyi görünürlüğe feda etmek olurdu.
- **İzin dizgeleri artık sabitlerle yazılıyor.** 31 ham `hasAuthority('...')` literali
  `AppPermissions` (ve modül sahipli `AuditPermissions` / `SettingsPermissions` /
  `TenantPermissions` / `SaasPermissions`) sabitlerine taşındı. Yazım hatası içeren bir literal
  derlenir, testten geçer ve endpoint'i **sonsuza dek 403**'te bırakır; hiçbir katman yakalamaz.
  ArchUnit kuralı yeni literalleri build zamanında reddediyor.
- **Kiracı filtresi üç entity'ye daha uygulandı** (`AuditLog`, `EntityChange`,
  `UserNotification`). `hostFilter` bilinçli olarak **eklenmedi**: host'un kiracılar arası audit
  incelemesi bir ürün özelliği, filtre onu kırardı.
- **`roles.manage` kaldırıldı.** Seeder her Admin rolüne veriyordu ama izin ağacında, iki mesaj
  paketinde, hiçbir `@PreAuthorize`'da ve frontend'de yoktu — hiçbir şeyi korumayan, ekranda
  görünmeyen ve geri alınamayan bir grant. `V7__drop_dead_roles_manage_permission.sql` bayat
  satırları temizliyor.

### Düzeltildi

- **`/api/users` ve `/api/roles` artık veritabanında sayfalıyor.** `@EntityGraph` + `Pageable`
  birlikte kullanıldığında Hibernate tüm satırları çekip bellekte diliyordu
  (`HHH90003004`) — 5 kayıtta görünmez, 50 binde heap uçurumu. İki aşamalı sorguya geçildi
  (id sayfası → fetch join), sayfa sırası açıkça geri yükleniyor.
- E-postalardaki bağlantıların tabanı (`zero.app.base-url`) `localhost:4200`'ü işaret ediyordu —
  Angular'ın portu. Frontend Vite/5173'te olduğu için geliştirmede gönderilen her şifre sıfırlama
  ve doğrulama bağlantısı ölü bir porta gidiyordu.

### Eklendi

- **ArchUnit cırcırı (5 kural), donmuş ihlal 58 → 0.** Mevcut ihlaller donduruldu, **yeni** ihlal
  build'i kırar; liste düzeltildikçe küçülür ve geri büyüyemez. Kuralların ikisi bytecode'da ifade
  edilemediği için (`javac` sabit katlaması, anotasyonsuz `package-info`'nun `.class` üretmemesi)
  `.java` kaynağını okuyor — kaynak kökü bulunamazsa **fırlatıyor**, "ihlal yok" demiyor.
  Rule 4 ayrıca yeniden formüle edildi (ADR-0016): entity'nin **kendi** paketinde dosya aramak
  yerine modül köküne yukarı yürüyüp `@ApplicationModule` **beyanı** arıyor — eski hâli, koruduğunu
  iddia ettiği beyan silindiğinde yeşil kalıyordu.
- **Export'ların sınırının SQL'e indiğini tutan test.** Bir davranış testi bunu göremez: `Pageable`'ı
  yok sayıp tüm satırları okuyan ve sınırı Java'da uygulayan bir fetcher dışarıdan **birebir aynı**
  davranır. `ExportRowBoundIT`, `org.hibernate.SQL`'e `ListAppender` bağlayıp sorgunun satır limiti
  taşıdığını doğruluyor — önünde bir vacuity guard var, sıfır statement yakalanırsa test kendini düşürüyor.
- Test profilinde `hibernate.query.fail_on_pagination_over_collection_fetch` — ArchUnit'in
  göremediği `join fetch` + `Pageable` şeklini de kapatır.
- Hesap ekranları: şifremi unuttum/sıfırla, profil + şifre değiştirme, e-posta doğrulama,
  kiracı yönetimi.

### Bilinen kısıtlar

`RISK-REGISTER.md` → "Şablonu klonluyorsanız — devraldığınız açık kısıtlar".
