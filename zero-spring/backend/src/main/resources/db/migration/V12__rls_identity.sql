-- RLS taban cizgisi, Adim 4 — ILK POLITIKALI TABLO GRUBU (identity): users, roles,
-- organization_units. Otorite: politika sablonu ve host kolu ADR-0018; muafiyetin kapali kume
-- oldugu ADR-0019. Sablon her tabloda aynidir:
--
--   ENABLE + FORCE ROW LEVEL SECURITY
--   CREATE POLICY tenant_isolation
--     USING      (tenant_id = nullif(current_setting('app.current_tenant', true), '')::bigint
--                 OR current_setting('app.is_host', true) = 'on')
--     WITH CHECK (ayni ifade)
--   GRANT ... TO zero_app
--
-- FORCE sart: tablolarin owner'i migration kimligidir ve FORCE olmadan kendi politikasini
-- atlar (V11 basligi). `nullif(..., '')` sart: `is_local=true` ile yazilan bir GUC transaction
-- bitince "hic set edilmemis"e degil BOS STRING'e doner (postgres:16'da olculdu) ve
-- `''::bigint` NULL degil HATA verir — duz `::bigint` sekli, havuzdan gelen her kullanilmis
-- baglantida host okumalarini da patlatirdi (ADR-0018). Asagida YALNIZ bu grubun bu sablondan
-- FARKLI olan iki seyi anlatilir.
--
-- ============================================================================================
-- FARK 1 — HOST-GLOBAL SATIRLAR GERCEKTEN VAR (`tenant_id IS NULL`)
-- ============================================================================================
-- `users` ve `roles` host operatorlerini ve host rolunu tasir (`seed/DataSeeder.seedHost()`),
-- `organization_units` de host-global bir agac tasiyabilir. Ucunde de `@Filter(name=
-- "hostFilter", condition="tenant_id is null")` bildirimi VAR — yani bu satir sinifi urunun bir
-- parcasidir, kaza degil.
--
-- Politikanin `tenant_id = <GUC>` kolu bu satirlari ASLA dondurmez: `NULL = 5` true degil NULL'dur.
-- Dolayisiyla iki soru ayri ayri cevaplanmak zorundadir.
--
-- SORU 1 — Host baglaminda (`app.is_host = 'on'`) gorunuyorlar mi? EVET. ADR-0018'in host kolu
-- satirin `tenant_id`'sine hic bakmaz, dolayisiyla NULL olanlar da dahil HER satiri dondurur.
-- Host login (`AuthService.findUser(null, ...)` → `findByUsernameIgnoreCaseAndTenantIdIsNull`),
-- host kullanici/rol yonetimi ve `DataSeeder`in uzlastirmasi bu koldan gecer.
--
-- SORU 2 — KIRACI baglaminda host-global bir satira erisen MESRU bir yol var mi? EVET, VAR, ve
-- OLCULDU (kod okumasi + mevcut yesil test):
--
--   `ImpersonationService.backToImpersonator()` — POST /api/auth/back-to-impersonator.
--   Bu ucu cagiran istek, taklit edilen KIRACI kullanicisinin oturumudur: `ImpersonationIT`
--   (adim 4) onu `X-Tenant: default` header'i ve `tenant` claim'i default kiraciyi gosteren bir
--   JWT ile cagirir, `AuthenticatedTenantFilter` de claim'i otoriter kabul edip TenantContext'i
--   o kiraciya kurar. Servis o baglamda `userRepository.findById(<act claim>)` ile GERI DONULECEK
--   AKTORU okur — ve o aktor HOST kullanicisidir (`users.tenant_id IS NULL`). Ardindan
--   `authoritiesOf(actor)` aktorun HOST rolunu (`roles.tenant_id IS NULL`) yukler.
--
--   ⚠️ BU YOL BUGUN NEDEN CALISIYOR: Hibernate `@Filter` `EntityManager.find()`'a UYGULANMAZ,
--   yani `findById` uygulama katmanindaki kiraci daralmasini zaten atliyor. RLS'in boyle bir
--   muafiyeti YOKTUR. Yani bu tablolarda RLS, Hibernate filtresinin degistirmedigi bir davranisi
--   DEGISTIRIR — grubun en riskli tarafi budur.
--
--   `tenant_id IS NULL` kolu olmadan sonuc: `findById` bos doner → 401 "Impersonator is not
--   available" → `ImpersonationIT#hostImpersonatesTenantUserAndReturnsBack` adim 4 KIRMIZI.
--   Kullanici satiri gorunse bile rol gorunmezse token IZINSIZ basilirdi — sessiz surum.
--
-- ⇒ KARAR: `users` ve `roles` politikalarina host-global kol **YALNIZ `USING`'e** eklenir,
--   `WITH CHECK`'e EKLENMEZ. Asimetri bilinclidir:
--     * `USING` = okuma/gorunurluk. Mesru kiraci-baglami okumasi yukarida kanitli.
--     * `WITH CHECK` = yazma. Kiraci baglamindan host-global satir YAZAN mesru bir yol ARANDI ve
--       BULUNAMADI: `DataSeeder.seedHost()` host kapsaminda kosar, `UserService`in host dali
--       yalniz `TenantContext.getTenantId() == null` iken girilir, `AccountService`in sifre
--       sifirlama/e-posta onay akislari host hesabi icin host yuzeyinden (X-Tenant'siz) gelir,
--       impersonation donusu ise aktoru yalniz OKUR. Simetrik yazsaydik bir kiraci baglami
--       `tenant_id = NULL` ile satir INSERT edebilir, yani KENDI host operatorunu yaratabilirdi —
--       izin katmanini komple atlayan bir yukseltme.
--
--   ⚠️ ASIMETRININ BEDELI, kayda geciyor: kiraci baglamindan host-global bir satiri UPDATE etmek
--   `USING`'den gecer (satir gorunur) ama `WITH CHECK`e carpar → "new row violates row-level
--   security policy" ile SERT hata. Bu yon bilincli tercih edilmistir (sessiz 0 satir yerine
--   gurultulu ret) ve bugun boyle bir akis yoktur; yarin olusursa hata mesaji dogru yeri gosterir.
--
--   ⚠️ HOST-GLOBAL KOL BOS BAGLAMDA ACILMAZ — duz `OR tenant_id IS NULL` YAZILAMAZ. Duz yazsaydik
--   hicbir GUC set etmemis bir transaction (aspect'e hic ugramamis bir yol, ham SQL, bir
--   `@Component` is) `users`/`roles`in host satirlarini GORURDU: yani RLS taban cizgisinin butun
--   temeli olan "GUC yoksa 0 satir" ozelligi bu iki tabloda tam olarak host operatorlerinin
--   satirinda duserdi. Bu yuzden kol `AND nullif(current_setting('app.current_tenant', true), '')
--   IS NOT NULL` ile KURULU BIR KIRACI BAGLAMINA baglanir: host-global satirlar "bir kiraci adina
--   kosan" bir transaction'a gorunur, "kimse adina kosmayan" bir transaction'a gorunmez.
--   `RlsIdentityIsolationIT` bu iki hâli ayri ayri assert eder.
--
--   ⚠️ KALAN ACIK (kabul edildi, kapatilmadi): `USING`'deki host-global kol, HER kiraci
--   baglamina host-global satirlari OKUNABILIR yapar — `users` icin bu, host operatorlerinin
--   kullanici adi/e-postasi ve parola HASH'i demektir. Zemin burada tavandan bir satir sinifi
--   GENIS: uygulama katmaninda `tenantFilter` (`tenant_id = :tenantId`) o satirlari her filtreli
--   sorgudan disliyor, RLS ise dislemiyor. Daraltmanin iki yolu vardi ve ikisi de daha pahali:
--     (a) aktor okumasini `security definer` bir yardimciya tasimak — yeni bir guvenlik yuzeyi,
--         kendi ADR'ini ister,
--     (b) `backToImpersonator`i host baglamina cevirmek — ama istek gercekten bir KIRACI
--         istegidir; TenantContext'i servis icinde bosaltmak `app.is_host='on'`u o transaction'in
--         geri kalanina devrederdi, yani kesinlikle daha kotu.
--   Kayit: RISK-REGISTER (R-45) + bu blok.
--
-- ⚠️ `organization_units` BU KOLU ALMAZ. Kanita dayali fark: kiraci baglaminda host-global bir OU
-- okuyan mesru bir yol YOK — `OrganizationUnitService` her okumayi `TenantContext.getTenantId()`
-- ile daraltir ve id ile bulduklarini `getInTenantOrThrow` ile ayrica karsilastirir (host OU'su
-- kiraci baglaminda zaten 404). Host'un kendi agacina erisimi `is_host` kolundan gecer. Uc tabloya
-- ayni sekli "tutarlilik olsun" diye yazmak, gerekcesiz bir gorunurluk genislemesi olurdu.
--
-- ============================================================================================
-- FARK 2 — GUC YAZMAYAN COK YOL VAR (aspect'in pointcut'i `within(@Service *)`)
-- ============================================================================================
-- ADR-0019 kurali: "`@Component` bir isten politikali bir tabloyu okumak yasaktir". Bu dosyayla
-- birlikte kurali ihlal eden IKI yer var ve ikisi de ayni dilimde ele alindi:
--   * `seed/DataSeeder` (`@Component`, `ApplicationRunner`) — `users`/`roles` yazar ve okur.
--     GUC olmadan INSERT'i politika REDDEDER, yani uygulama HIC AYAGA KALKMAZ. Bu dosya
--     uygulanmadan once DataSeeder kendi baglamini acikca yazacak sekilde degistirildi
--     (`announceHostContextToDatabase`).
--   * `identity/saas/SubscriptionNotificationBridge` (`@Component`) — hedef kiracinin admin'lerini
--     okur; ama cagiranlari her zaman bir `@Service` (`SubscriptionService`) oldugu ve o cagri
--     zinciri host baglamindan geldigi icin GUC `is_host='on'`dur. Olcum: SubscriptionExpiryNoticeIT.
-- Testlerin kendi thread'inden yapilan dogrudan repository okumalari da ayni sinifta: onlar
-- `AbstractIntegrationIT`in yeni `inTenantDatabase`/`asHostDatabase` yardimcilarini kullanir.

-- users --------------------------------------------------------------------------------------
-- UNIQUE dogrulamasi da RLS'i atlar (bir UQ ihlali gorunmeyen bir satirin varligini
-- sizdirabilir). Bu grubun ikisinde de ayirici KOLONUN ICINDE: `uq_users_tenant_username_live`
-- (V6) ve `uq_roles_tenant_name` `(tenant_id, ...)` uzerinde `nulls not distinct` ile tanimli, yani
-- "bu ad kullaniliyor" hatasi baska bir kiracinin satirini ele vermez — yalniz KENDI kiracisinin ya
-- da host kapsaminin satirini. `organization_units`ta hic UQ yok (yalniz index).
ALTER TABLE users ENABLE ROW LEVEL SECURITY;
ALTER TABLE users FORCE  ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON users;
CREATE POLICY tenant_isolation ON users
  USING      (tenant_id = nullif(current_setting('app.current_tenant', true), '')::bigint
              OR (tenant_id IS NULL
                  AND nullif(current_setting('app.current_tenant', true), '') IS NOT NULL)
              OR current_setting('app.is_host', true) = 'on')
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::bigint
              OR current_setting('app.is_host', true) = 'on');
GRANT SELECT, INSERT, UPDATE, DELETE ON users TO zero_app;

-- roles --------------------------------------------------------------------------------------
-- `role_permissions` (element collection) `tenant_id` TASIMAZ, yani politikasizdir ve aday da
-- degildir (ADR-0019'un "muaf olmakla aday olmamak ayni sey degildir" ayrimi). Sonucu:
-- gorunmeyen bir rolun izin satirlari ham SQL ile hâlâ okunabilir. Erisim `role_id` bilmeyi
-- gerektirir ve `roles` uzerinden o id kiraci baglaminda gorunmez; kapatmanin yolu o tabloya
-- `role_id -> roles` uzerinden bir EXISTS politikasi yazmaktir (ayri dilim, kendi olcumu;
-- RISK-REGISTER R-47).
ALTER TABLE roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE roles FORCE  ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON roles;
CREATE POLICY tenant_isolation ON roles
  USING      (tenant_id = nullif(current_setting('app.current_tenant', true), '')::bigint
              OR (tenant_id IS NULL
                  AND nullif(current_setting('app.current_tenant', true), '') IS NOT NULL)
              OR current_setting('app.is_host', true) = 'on')
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::bigint
              OR current_setting('app.is_host', true) = 'on');
GRANT SELECT, INSERT, UPDATE, DELETE ON roles TO zero_app;

-- organization_units -------------------------------------------------------------------------
-- Kanita dayali fark: `tenant_id IS NULL` kolu YOK (gerekcesi yukarida). `parent_id` kendi
-- tablosuna referans verir ve bir FK backstop ALAMAZ: bir tablonun politikasi kendi tablosunu
-- sorgularsa Postgres `infinite recursion detected in policy for relation "organization_units"`
-- (42P17) ile HER INSERT'i reddeder — turetilmis projede birebir olculdu. O halka bugun
-- `OrganizationUnitService.getInTenantOrThrow` ile servis katmaninda kapalidir.
ALTER TABLE organization_units ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_units FORCE  ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON organization_units;
CREATE POLICY tenant_isolation ON organization_units
  USING      (tenant_id = nullif(current_setting('app.current_tenant', true), '')::bigint
              OR current_setting('app.is_host', true) = 'on')
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::bigint
              OR current_setting('app.is_host', true) = 'on');
GRANT SELECT, INSERT, UPDATE, DELETE ON organization_units TO zero_app;
