-- RLS taban cizgisi, Adim 4 — SON TABLO GRUBU: audit_logs, entity_changes, user_notifications.
-- Otorite: politika sablonu ve host kolu ADR-0018; muafiyetin kapali kume oldugu ADR-0019.
-- Desen V12 ile birebir aynidir (V12'nin host-global okuma kolu BURADA YOK — gerekcesi asagida).
-- Bu dosyayla `tenant_id` tasiyan 9 tablonun 6'si politikali, 3'u ADR-0019 ile muaftir; kapsam
-- artik RlsCoverageIT tarafindan zorlanir.
--
-- ============================================================================================
-- BU GRUBUN OZELLIGI — HOST'UN CAPRAZ-TENANT GORUNURLUGU URUNDUR
-- ============================================================================================
-- Bu uc tabloda "host her kiracinin satirini gorur" bir yan etki degil, OZELLIKTIR: capraz-tenant
-- denetim gorunumu (audit_logs/entity_changes: "kiraci X'te ne oldu?") ve host aliciya kiraci
-- ETIKETLI bildirim (user_notifications: "kiraci acme'nin aboneligi bitti"). Entity'lerin ucunde
-- de bu yuzden `hostFilter` bilinçli olarak YOK ve TenantFilterCoverageIT bunu IKI YONLU assert
-- eder (kiraci baskasini goremez + host herkesi gorur). ADR-0018'in host kolu bu davranisi
-- degistirmeden tasir: `is_host = 'on'` satirin tenant_id'sine hic bakmaz.
--
-- V12'NIN HOST-GLOBAL OKUMA KOLU (`tenant_id IS NULL AND <kurulu kiraci baglami>`) BURADA YOK.
-- Kanita dayali fark, `organization_units` ile ayni sekilde: kiraci baglamindan `tenant_id IS
-- NULL` bir satiri (host operatorunun kendi audit izini, host'a NULL etiketle yazilmis bir
-- bildirim) okuyan MESRU bir yol ARANDI ve BULUNAMADI — `AuditLogService` kiraci baglaminda
-- explicit tenant predicate ekler, `NotificationService.list` alicinin `userId`'siyle okur ve
-- kiraci kullanicilarinin satirlari her zaman kendi kiracisiyla etiketlidir (asagida yazma
-- yollari). Hibernate `tenantFilter` da bu satirlari kiraci sorgularindan zaten disliyor; RLS
-- ayni cizgiyi cizer. Kolu "tutarlilik olsun" diye eklemek, host operatorlerinin istek izlerini
-- her kiraci baglamina acardi (R-45'in genislemesi) — gerekcesiz.
--
-- ============================================================================================
-- YAZMA YOLLARI — POLITIKA HICBIRINI KIRMAMALI, VE KIRARSA SESSIZ KIRAR (olculdu, kod okumasi)
-- ============================================================================================
-- Uc tablonun uc yazicisi da sonunda bir `@Service` sinirindan gecer, yani aspect GUC'u yazar;
-- DataSeeder-tipi bir duzeltme GEREKMEDI:
--   * audit_logs — `AuditLogInterceptor` bir `@Component` AMA kendisi yazmaz:
--     `AuditLogService.save` (@Service, @Transactional) cagirir. Satirin etiketi
--     `AuditPrincipal.tenantId()` ONCE TenantContext'i okur — aspect'in GUC'a yansittigi AYNI
--     kaynak — yani kiraci baglaminda etiket == GUC insaat geregi esittir. TenantContext bos
--     (host istegi, kiracisiz anonim istek, async re-dispatch) ⇒ aspect `is_host='on'` yazar,
--     etiket NULL ya da JWT claim'i olabilir; her iki halde host kolu gecirir.
--   * entity_changes — Hibernate event listener (`EntityChangeListener`, @Component) yalniz
--     TAMPONLAR; INSERT'i commit SONRASI `EntityChangeWriter.writeAll` (@Service,
--     REQUIRES_NEW) yapar. Aspect o YENI transaction'in icinde kosar ve GUC'u o anda hâlâ
--     kurulu olan thread-local TenantContext'ten baglar — yani "flush aninda hangi GUC?"
--     sorusunun cevabi: YAZAN transaction'inki, ve o da kaydi tetikleyen istegin baglamidir.
--     Etiket yine `AuditPrincipal.tenantId()` (context-first) ⇒ GUC ile esit.
--   * user_notifications — tek yazici `NotificationService.publish` (@Service). Kiraci
--     baglaminda cagiranlar (orn. `UserService` welcome) etiketi o kiracinin id'siyle gecer ⇒
--     tenant kolu. Host baglamindaki cagiranlar (`SubscriptionNotificationBridge` — @Component
--     ama cagirani daima host baglamindaki bir @Service, olcum: SubscriptionExpiryNoticeIT)
--     HEDEF kiracinin etiketini host GUC'uyla yazar ⇒ WITH CHECK'in host kolu tam bunun icin
--     var. Kiraci baglamindan BASKA kiracinin etiketiyle ya da NULL etiketle yazan mesru yol
--     ARANDI, YOK ⇒ WITH CHECK simetrik V12 sablonu kalir: kiraci ne baska kiracinin denetim
--     izine satir enjekte edebilir ne de NULL etiketle kendi izini kiraci gorunumunden
--     saklayabilir.
--
-- ⚠️ BU GRUBUN OZEL RISKI — POLITIKA REDDI SESSIZDIR: `AuditLogInterceptor.afterCompletion` ve
-- `EntityChangeListener.BufferSynchronization.afterCommit` RuntimeException'i YUTAR (log.warn).
-- Yanlis yazilmis bir WITH CHECK burada kirmizi test degil, SESSIZCE KAYBOLAN DENETIM IZI
-- uretir. RlsAuditNotificationIsolationIT bu yuzden gercek HTTP istekleriyle satirin
-- YAZILDIGINI assert eder — "istek 200 dondu" yetmez.
--
-- FK BACKSTOP BU GRUPTA YOK, uc tablo icin uc ayri gerekceyle:
--   * audit_logs / entity_changes: `tenant_id` ve `user_id` FK'SIZDIR (V2 — kayit, kaynagi
--     silinse de yasamali). Backstop'un dogrulayacagi bir ebeveyn halkasi yok.
--   * user_notifications.user_id → users(id) FK'si BILINCLI olarak kiraci siniri asar: alici
--     anahtari GLOBALDIR ve host alici, hakkinda oldugu kiracinin etiketini tasiyan satir
--     tutabilir (entity javadoc'u + TenantFilterCoverageIT). `EXISTS (users u ... u.tenant_id =
--     user_notifications.tenant_id)` tam bu urun seklini reddederdi. Artik risk kaydi: kiraci
--     baglamindan ham SQL ile baska kiracinin kullanicisina satir yazilabilir (FK RLS'i atlar)
--     ama satir kendi etiketini tasimak zorunda oldugundan hedef kullanicinin gorunumune ASLA
--     girmez — gurultu vektoru bile degil, cop.
--
-- entity_property_changes bu dosyada YOK ve muafiyet listesinde de YOK: `tenant_id` kolonu
-- tasimaz, yani ADR-0019'un diliyle "aday degil". role_permissions ile ayni asimetri sinifi
-- (R-47): gorunmeyen bir change'in property satirlari `entity_change_id` bilinerek ham SQL
-- ile okunabilir; kapanisi R-47'nin EXISTS dilimiyle birlikte.

-- audit_logs ---------------------------------------------------------------------------------
-- UNIQUE yok (UQ-ihlali oracle'i bu tabloda dogamaz); tek okuma yuzeyi AuditLogService ve
-- o kiraci baglaminda zaten explicit predicate tasir — RLS burada ikinci degil UCUNCU hat, ve
-- tam da bu yuzden degerli: predicate'i unutan bir sonraki sorgu icin zemin.
ALTER TABLE audit_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_logs FORCE  ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON audit_logs;
CREATE POLICY tenant_isolation ON audit_logs
  USING      (tenant_id = nullif(current_setting('app.current_tenant', true), '')::bigint
              OR current_setting('app.is_host', true) = 'on')
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::bigint
              OR current_setting('app.is_host', true) = 'on');
GRANT SELECT, INSERT, UPDATE, DELETE ON audit_logs TO zero_app;

-- entity_changes -----------------------------------------------------------------------------
ALTER TABLE entity_changes ENABLE ROW LEVEL SECURITY;
ALTER TABLE entity_changes FORCE  ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON entity_changes;
CREATE POLICY tenant_isolation ON entity_changes
  USING      (tenant_id = nullif(current_setting('app.current_tenant', true), '')::bigint
              OR current_setting('app.is_host', true) = 'on')
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::bigint
              OR current_setting('app.is_host', true) = 'on');
GRANT SELECT, INSERT, UPDATE, DELETE ON entity_changes TO zero_app;

-- user_notifications -------------------------------------------------------------------------
ALTER TABLE user_notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_notifications FORCE  ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON user_notifications;
CREATE POLICY tenant_isolation ON user_notifications
  USING      (tenant_id = nullif(current_setting('app.current_tenant', true), '')::bigint
              OR current_setting('app.is_host', true) = 'on')
  WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::bigint
              OR current_setting('app.is_host', true) = 'on');
GRANT SELECT, INSERT, UPDATE, DELETE ON user_notifications TO zero_app;
