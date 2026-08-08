# Değişiklik Günlüğü

[Keep a Changelog](https://keepachangelog.com/tr/1.1.0/) formatı ·
[Semantic Versioning](https://semver.org/lang/tr/)

> Bu dosya **sizin projenizin** günlüğüdür. Şablonun kendi inşa süreci
> `../history/CHANGELOG-template-build.md` altında arşivlendi — orada gördüğünüz faz/slice
> kavramları sizi bağlamaz.

## [Yayınlanmamış]

### Eklendi

- **Kullanıcı daveti — uçtan uca akış (`identity/invitation`, `V15__user_invitations.sql`).**
  `users.create` sahibi admin tek kullanımlık, süreli (72s) bir token postalar; davetli bağlantıdan
  gelir, admin'in sabitlediği kullanıcı adını görür, parolasını seçer ve hesap kabulde aktif +
  e-postası doğrulanmış doğar (token'ın o adrese gidip geri gelmesi doğrulamanın kendisidir).
  - **Token:** 32 bayt `SecureRandom`, base64url; DB yalnız SHA-256 hex tutar (V14 reset/confirmation
    deseniyle aynı — R-44). Tek kullanım guarded UPDATE ile (`transition`, affected-rows==1 —
    read-then-write değil): çifte accept tek hesap üretir ve İLK parola korunur. `resend` hash'i
    ezerek eski token'ı öldürür; `revoke` PENDING'i kapatır. Ayrı "EXPIRED" durumu bilinçli yok —
    süre `expires_at`'ten okunur (zamanlanmış yazıcı R-46 sınıfı olurdu).
  - **Tablo + RLS politikası TEK migration'da doğar** (V12 `organization_units` şekli, host-global
    kol yok); `RlsCoverageIT` keşfi yeni tabloyu otomatik kapsar (taban 9 → keşif 10). Kısmi UNIQUE
    indexler yalnız PENDING'i bağlar (`nulls not distinct` — host davetlerinde de teklik).
  - **İzin:** `users.create` yeniden kullanıldı — davet, ertelenmiş kullanıcı yaratmadır; yeni izin
    5-dosya kayıt + rol şablonu değişikliği maliyetine hiç kimsenin istemediği bir sınır çizerdi.
    Anonim iki uç (`GET /api/account/invitation`, `POST /api/account/accept-invitation`) üçlü
    yükümlülükle kayıtlı: SecurityConfig'te TAM path permitAll + `zero.ratelimit.paths` (GET de
    BİLEREK listede — token tahmin kanalı) + `@EndpointPolicy(ANONYMOUS, SUBSCRIPTION_EXEMPT)`;
    `SecurityPathBindingIT`/`SubscriptionExemptPathBindingIT` türetip doğruluyor. Koltuk sınırı
    (`app.maxUserCount`) davet ANINDA değil kabul ANINDA ölçülür — bekleyen davet koltuk tutmaz.
  - **Ölçülmüş iki gerçek tuzak, kodda gerekçeli** (kaynak projede kırmızı ölçüldü, buraya
    düzeltilmiş haliyle geldi): (1) tenancy aspect'i her iç `@Service` girişinde Hibernate filtresini
    yeniden kurar ve Hibernate 6 etkin filtreleri **bulk HQL mutasyonlarına da** uygular — anonim
    akışta claim 0 satır güncelleyip geçerli token'a "Invalid invitation" diyordu; (2) koltuk sayacı
    hostFilter altında her kiracı için 0 sayıp limiti hiç ısırmıyordu. Her bulk/varlık okuması öncesi
    `disableTenantFilters()` ile kapatıldı (`InvitationService.claim` javadoc'u).
  - **Frontend:** users ekranında davet + davet listesi dialogları (üçlü kilidin `<Can>` ayağı),
    `/account/accept-invitation` sayfası (kullanıcı adı görüntülenir, parola belirlenir; ACCEPTED →
    girişe yönlendirme, oracle yok), ayrı `invitation-*` modülleri (mevcut test mock fabrikaları
    kırılmasın diye). ⚠️ Tipler ELLE bildirildi + `TODO(gen:api)` işaretli — bu depoda şema henüz
    davet uçlarıyla yeniden üretilmedi; üretildiğinde alias'a çevrilmeli (elle kopya, backend alan
    adı değişimini derleyiciden kaçırır).
  - **Kanıt:** `InvitationFlowIT` (9/9 — mutlu yol + login, 403, oracle'sız 400'ler, expired+resend,
    çifte accept, revoke, duplicate 409, kabul-anı koltuk sınırı, çapraz-tenant 404 + V15 RLS zemin
    ölçümü `inTenantDatabase`/`asHostDatabase` ile); guard'lar yeşil.

- **RLS taban çizgisi — PostgreSQL Row Level Security: kimlik ayrımı, GUC katmanı, politikalar ve
  kapsam guard'ı (`R-08` Closed; ADR-0018 + ADR-0019).** Kiracı izolasyonu artık yalnız uygulama
  katmanında (`@Filter`) değil, veritabanı zemininde de duruyor. Yığın, bu şablondan türetilmiş
  projede üç kez doğrulanıp (oradaki kanıt: 15 mutasyon, 465 IT) buraya taşındı; `tenant_id`
  taşıyan 9 tablonun 6'sı politikalı, 3'ü ADR-0019 ile bilinçli muaf.
  - **Adım 1 — kimlik ayrımı, atlanamaz ön koşul.** Tek kimlikle RLS **kanıtlanamaz**: owner
    `FORCE` olmadan kendi politikasını atlar, superuser her hâlükârda atlar → izolasyon testleri
    politika hiç çalışmadan yeşil döner ve bunu söyleyen hiçbir satır olmaz. `V11__app_role.sql`
    `zero_app` rolünü (`NOSUPERUSER NOBYPASSRLS`) yaratır; `spring.datasource` ile `spring.flyway`
    kimlikleri ayrıldı (application*.yml, docker-compose `POSTGRES_USER=postgres`, ci.yml
    `DB_USER=zero_app` + `DB_MIGRATION_USER=postgres`); `AppDbCredentialsValidator` prod'da
    (datasource == migration kullanıcısı), (commit'li dev şifresi) ve (çözülmemiş `${...}`)
    hâllerini açılışta reddeder. `AppDbIdentityIT` ayrımı **kilitler**: birinci iddia
    `current_user == zero_app`, üçüncüsü `installed_by != zero_app` — kimlikler aynı olsa bu ikisi
    mantıksal olarak birlikte geçemez.
  - **Adım 2-3 — GUC katmanı + ADR-0018.** Tenant bağlamı `set_config(..., is_local=true)` ile
    transaction-local yazılır (havuzda sızmaz); yazım Hibernate filtresini açan **aynı** aspect'te,
    çünkü filtre kararı ile politika kararı aynı karardır. Her çağrıda iki ayar birlikte yazılır ve
    karşı GUC boşaltılır — yoksa nested/context-switch çağrısında `OR is_host='on'` kolu kiracı
    bağlamında true kalır ve izolasyon tamamen kalkar. Aspect çıkışta **çağıranın** bağlamını geri
    kurar (materialized CTE ile oku-yaz tek round trip; abort edilmiş transaction'da restore
    hatası suppressed olarak eklenir, gerçek hatayı gölgelemez). Politika şablonu
    `nullif(current_setting(...), '')::bigint` — ölçüldü: `is_local=true` GUC transaction bitince
    boş string'e döner, `''::bigint` NULL değil hata verir. Kanıt: `GucTenantContextIT` (10 test).
  - **Adım 4 — politikalar.** `V12__rls_identity.sql` (`users`, `roles`, `organization_units`;
    `users`/`roles` `USING`'inde ölçülmüş gerekçeli, kurulu-kiracı-bağlamına bağlı host-global
    okuma kolu — `ImpersonationService.backToImpersonator()` — `WITH CHECK`'e bilinçli olarak
    eklenmedi) + `V13__rls_audit_notification.sql` (`audit_logs`, `entity_changes`,
    `user_notifications`; host-global kol YOK, host çapraz-tenant görünürlüğü ÜRÜN). `DataSeeder`
    bir `@Component` olduğu için aspect'e uğramaz — kendi bağlamını açıkça yazar
    (`announceHostContextToDatabase`); bu, ADR-0019'daki "`@Component` işten politikalı tabloya
    erişim" kuralının ölçülmüş ilk örneği (`R-46`).
  - **Adım 7 — kapsam guard'ı `RlsCoverageIT` (4 test).** Tablo listesi `information_schema`'dan
    KEŞFEDİLİR (sabit liste yok — politikasız doğan yeni `tenant_id`'li tablo otomatik kırmızı);
    keşif ≥ 9 taban assert'i boş-yeşili kapatır; `FORCE` da denetlenir; muafiyet listesi
    ADR-0019'un makine-okunur satırından ayrıştırılıp test sabitiyle eşitlenir (drift = kırmızı;
    otorite ADR, ekleme yeni ADR ister) ve muaf tablonun hem var hem politikasız olduğu iki yönlü
    doğrulanır.
  - **Bir davranış değişti, bilinçli ve daha sıkı:** 2FA verify'ını başka kiracının header'ıyla
    kullanmak artık 200 + (kendi kiracısının) token değil, **401** — challenge kullanıcısını
    primary key ile çözmek RLS altında çapraz-kiracı okumadır ve `@Filter`'ın aksine RLS'in
    `find()` muafiyeti yoktur. `TwoFactorTenantIsolationIT` iki yarımı da assert eder (ret + aynı
    kullanıcının kendi header'ıyla kontrol).
  - **Uyarlanan testler:** `AbstractIntegrationIT` çift kimlik (Flyway=superuser, app=`zero_app`)
    + `inTenantDatabase`/`asHostDatabase` yardımcıları aldı; 14 IT sınıfı test-thread
    okuma/yazmalarında bağlamını **bildirir** hâle geldi (politikadan muaf tutulmadı):
    `TenantFilterCoverageIT`, `TenantBootstrapIT`, `AbstractTwoFactorIT`, `TwoFactorLoginIT`,
    `TwoFactorManagementIT`, `TokenRevocationIT`, `TokenRevocationSubSecondIT`,
    `SoftDeletedUsernameReuseIT`, `MeShouldChangePasswordIT`, `SessionOwnershipIT`,
    `SaasNotificationBridgeIT`, `SubscriptionExpiryNoticeIT`, `RolePermissionReconciliationIT`,
    `SeedHardeningIT`, `ExportRowBoundIT`.
  - **Kanıt (bu depoda):** 5 yeni IT sınıfı — `AppDbIdentityIT`(3) + `GucTenantContextIT`(10) +
    `RlsIdentityIsolationIT`(8) + `RlsAuditNotificationIsolationIT`(11) + `RlsCoverageIT`(4) —
    yeşil; migration+aspect taşınıp uyarlanmamış testlerle koşulduğunda `TenantBootstrapIT` 4/6 ve
    `TenantFilterCoverageIT` 3/4 tam beklenen desenle kırmızıydı ("new row violates row-level
    security policy" / fail-closed 0 satır) — uyarlamalar bu ölçümden sonra yapıldı. Artık
    riskler: `R-45`, `R-46`, `R-47` (RISK-REGISTER).

- **SaaS parite kapanışı: yaşam döngüsü bildirimleri + abonelik geçmişi UI + parite matrisi.**
  ASP.NET Zero SaaS davranışıyla kalem kalem parite ölçüldü ve iki kritik boşluk kapatıldı
  (`docs/SAAS-PARITY-MATRIX.md` — Tam/Tam+/Kısmi/Deferred + kanıt + bilinçli farklar):
  (1) **SaaS olay → bildirim köprüsü:** her `subscription_events` girdisi artık `SubscriptionChanged`
  (saas::api) yayınlar; identity'deki `SubscriptionNotificationBridge` operasyonel olayları
  (activated/cancelled/period-ended/expired/downgraded/expiring-soon) tenant **Admin** üyelerine
  in-app bildirime çevirir (i18n en+tr, aynı transaction — yarım-durum yok). Alıcı sorgusu bilinçli
  cross-tenant: tenancy filtresi yalnız o sorgu için askıya alınıp **geri yüklenir** (ikinci savunma
  hattı transaction'ın kalanında aynen). (2) **Süre-dolumu ön uyarısı:** lifecycle job'a pencere
  taraması (`zero.saas.expiry-notice-days`, varsayılan 7) + `EXPIRY_NOTICE` event-ledger idempotency —
  kaynaktaki tam-gün-eşitliği kusurunun (koşu kaçarsa uyarı kaybolur) aksine geç koşu yine uyarır,
  saatlik koşu çift uyarmaz. (3) **Abonelik detay + yaşam döngüsü geçmişi UI:** subscriptions
  listesine "Geçmiş ve detay" sheet'i — durum anlık görüntüsü + from→to/reason/actor/zaman
  çizelgesi (backend'de zaten vardı, ilk kez görünür). Kanıt: backend 222 unit + **367 IT** (+3:
  `SaasNotificationBridgeIT` 2, `SubscriptionExpiryNoticeIT` 1) `clean verify` yeşil; frontend 32
  dosya / **168 test** (+3 detay sheet). Negatif kanıt ölçüldü: bildirim teslimi yokken testler
  KIRMIZI (`Expecting [] to contain ["saas.subscription.activated"]` — tenancy-filtre kök nedeni
  bulunup kapatıldı), provisioning-bildirmez gürültü negatifi, pencere-dışı-0/tekrar-koşu-1
  idempotency kanıtı. Modulith sınırları korunuyor (ArchitectureRulesTest 9/9; saas yeni bağımlılık
  ALMADI — köprü identity'de, mevcut `identity → saas::api + notification` kenarlarıyla).

- **Tab-bazlı dashboard yönetim merkezi (yalnız frontend).** Widget dashboard'ı 5 sekmeli yönetim
  merkezine yükseltildi (`85c42e8`, CI 9/9 run 29927220897): Genel Bakış (KPI + trend + hızlı
  erişim, herkese) · Operasyon (bildirim gelen kutusu + son hesaplar) · Aktivite (trend + zaman
  çizelgesi; `auditlogs.read`) · Finans (tenant: kendi aboneliği / host: abonelik özeti;
  `subscriptions.read`) · Yönetim (KPI + kullanıcılar + admin kısayolları; herhangi bir admin izni).
  Kullanılamayacak sekme HİÇ sunulmaz. Aktif sekme URL'de (`?tab=…`) — deep-link + refresh dayanıklı;
  Radix tablist = klavye/erişilebilirlik hazır. 2 yeni widget (bildirim gelen kutusu · host abonelik
  özeti) mevcut Widget/durum/`enabled` deseninde. **Performans:** pasif sekme unmount → sorgular ilk
  ziyarette atılır (testle kanıtlı: Operasyon açılana dek `listNotifications` çağrılmıyor); recharts
  `lazy()` ile ayrı chunk'a bölündü — ilk bundle 1557→1166 kB (gzip 439→331, ~%25 hafifleme). Kanıt:
  31 dosya / 165 test; dashboard suite 14 (sekme görünürlüğü iki yönlü, tembel sekme verisi, negatif
  izin, sekmeler-arası hata izolasyonu, host/tenant finans ayrımı).

- **Enterprise dashboard widget sistemi (yalnız frontend).** Quick-access-only dashboard,
  modüler widget mimarisine dönüştü (`41dafbd`, CI 9/9 run 29919645849): ortak `Widget`
  container'ı (gerçek `h2` + `aria-labelledby`, aksiyon slotu, i18n'li refresh, footer) +
  standart durum bileşenleri (skeleton kpi/chart/list · hata+retry · boş) ve 6 widget — KPI
  kartları (kullanıcı/rol/kiracı/okunmamış), aktivite trend grafiği (recharts, 14 günlük denetim
  hacmi), son kullanıcılar, aktivite zaman çizelgesi, abonelik (yalnız tenant bağlamı; 404 = boş),
  hızlı erişim. 12 kolonlu responsive grid; izinsiz widget hem gizli hem **sorgusu hiç atılmıyor**
  (`enabled`); tüm veri MEVCUT uçlardan (yeni endpoint yok, `schema.d.ts` değişmedi, yeni bağımlılık
  yok). Commit öncesi stack-reviewer'ın 5 bulgusu kapatıldı — en önemlisi: trend örneklemi sunucunun
  sessiz `max-page-size: 100` tavanına hizalandı ve kısmi örneklem artık footer'da beyan ediliyor
  (`totalElements` kıyası, iki yönlü testli). Kanıt: 31 dosya / 161 frontend testi (dashboard suite
  10: negatif izin = sorgu yok, widget-başına hata izolasyonu, host/tenant bağlamı, örneklem beyanı).

- **Kullanım + AI prompt dokümantasyon seti (`docs/usage/`).** Klonlayan ekip için operasyonel
  rehberler — kod/davranış değişmedi, sadece doküman. 7 dosya: `QUICKSTART.md` (15–30 dk ayağa
  kaldırma + ilk smoke akışı + sık kurulum hataları tablosu), `WORKING-WITH-AI.md` (Codex/Claude
  çalışma modeli, hangi ajan/rol ne zaman, paralel çalışma + aynı-dosya çakışması önleme),
  `PROMPT-CATALOG.md` (backend/frontend/security/review/release için kopyala-kullan role-based
  promptlar; her biri scope-lock + kabul kriteri + kanıt formatı + no-fake-green + non-goals
  taşır), `EVIDENCE-AND-GATES.md` (minimum kanıt, negatif kanıt örnekleri, test/CI/governance
  senkron checklist, drift + false-green önleme), `OPERATOR-HANDOFF.md` (geliştirici↔operatör
  sınırı, secret sınırları, prod provisioning checklist, deploy/rollback kısa akışları),
  `FIRST-7-DAYS.md` (gün bazlı onboarding), `CHEAT-SHEET.md` (1 sayfalık ilk gün özeti). `usage/README.md`
  index + `docs/README.md` ve kök `README.md`'ye işaretçiler. Mevcut governance (AGENT-WORKING-AGREEMENT,
  QUALITY-GATES, RISK-REGISTER, SETUP §6, RELEASE-RUNBOOK) ile hizalı; tekrar değil, kullanım katmanı.

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

- **Bayat kayıt: "şifre sıfırlama ekranı eksik" iddiası YANLIŞTI — kod değil kayıt düzeltildi.**
  Kök `README.md` §6 ve `usage/FIRST-7-DAYS.md` Gün 2, "hazır olmayanlar" arasında şifre sıfırlama
  ekranını (ve kullanıcı davetini) sayıyordu; oysa ekranlar `5bc76d7`'den (2026-07-19, "add the
  account screens the backend already supported") beri uçtan uca var: `features/account` altında
  forgot/reset/confirm sayfaları, login'den bağlantılı, en+tr, `PasswordPolicyIT` uçtan uca.
  Yanlış, bu şablondan türetilmiş bir projenin keşif turunda yakalandı (oradaki denetim "ekran
  eksik" diye iş açmıştı — kaynağı bu bayat kayıttı). İki doküman düzeltildi; davet akışı da bu
  dilimle geldiği için listeden çıktı. Kayıtla birlikte üç GERÇEK boşluk da kapatıldı: politika
  ihlali artık **alan hatası** olarak görünür (detail prefix sözleşmesi — "Password…" alan,
  "Invalid or expired…" sayfa uyarısı; `reset-password.tsx` + davranış testi), geçersiz/bayat kodda
  **"yeni kod isteyin"** bağlantısı (`account.reset.requestNew`), ve enumeration güvenliğinin
  bilinmeyen-hesap yarısına IT kanıtı (aynı 204 + GreenMail'e posta GELMEDİĞİ assert edilir;
  uydurma kod → 400, `PasswordPolicyIT`).

- **Login: boş kiracı alanı bayat kalıcı kiracıyı temizliyor (`8f8452b`).** Eski davranış: alan
  doluysa `setTenant`, boşsa hiçbir şey — önceki oturumun kiracısı localStorage'da kalıyor ve her
  isteğe `X-Tenant` olarak biniyordu; "varsayılan için boş bırakın" bayat kiracıya giriş yapıyordu
  (canlıda gözlendi: kalıntı `cafer` kiracısı → Invalid credentials → o kiracının admin'i kilitlendi;
  host admin API'de 200 — parola doğruydu). Düzeltme: login ekranında tek kiracı otoritesi form
  alanı — boş alan depoyu temizler. Negatif kanıt eski kodda ölçüldü (`expected 'stale-tenant' to be
  null` kırmızı → düzeltmeyle yeşil); dolu-alan kalıcılık yolu ikinci testle sabit. `MeResponse.tenantId`
  tip yalanı da giderildi (`string` → `number | null`, wire gerçeği; host/tenant ayrımı bu nullability'e
  yaslanıyor).

- **Doküman tutarlılık düzeltmesi (yalnız doküman).** Kök `README.md`'deki eski metrikler
  (`330 backend + 90 frontend testi`, `8 kapılı CI`) kaldırıldı — kırılgan sabit sayı yerine
  dayanıklı ifade + governance kaydına işaretçi (güncel sayı `QUALITY-GATES-RESULTS.md`'de).
  `SETUP-NEW-PROJECT.md` içindeki CI job sayısı çelişkisi giderildi: §1 "sekiz job" → "dokuz job";
  §2 required-check listesine `docker-build` eklendi (8 job, `release` hariç — push-gated, PR'da
  skipped); §6.4 "9 job" ifadesi §2 listesine hizalandı. `usage/WORKING-WITH-AI.md`'ye üç skill
  (`migration-safety`, `permission-model`, `tenant-isolation`) doğru ad + `.claude/skills/<ad>/SKILL.md`
  path'iyle eklendi (skill'ler mevcut; doğrulandı). Kod/CI akışı/test/konfig değişmedi.

### Güvenlik

- **R-44 kapatıldı: şifre sıfırlama ve e-posta doğrulama kodları artık DB'de hash'li ve süreli
  (`V14__reset_confirmation_code_hardening.sql` + `AccountRecoveryCodes`).** `password_reset_code`
  ve `email_confirmation_code` V2'den beri düz metin ve süresizdi; "Invalid or expired reset code"
  mesajındaki *expired* hiç gerçekleşmeyen bir koşuldu. Artık iki akış da davet token'ı deseninde:
  DB yalnız SHA-256 hex + expiry tutar (reset **1 saat**, confirmation **72 saat**; süre `Clock`
  üzerinden okuma anında türetilir), ham kod yalnız e-postada yaşar. Migration eski kolonlara
  DOKUNMAZ: bekleyen kodlar TRUNCATE edilmez, yeni akış yalnız `*_hash` okur — NULL hash hiçbir
  girdiyle eşleşmez (**fail-closed**), eski kolonlar rolling-deploy penceresi kapanınca ayrı V ile
  düşülecek. Expired-vs-unknown aynı mesajla reddedilir (oracle yok). Kanıt `PasswordPolicyIT`:
  DB zemininde saklanan değer postalanan kodun SHA-256'sı + legacy kolon NULL; `MutableClock` ile
  1s+1dk sonra reset kodu 400 ve parola değişmemiş, 72s+1dk sonra confirmation kodu 400, taze kod
  kendi penceresinde çalışıyor.

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
