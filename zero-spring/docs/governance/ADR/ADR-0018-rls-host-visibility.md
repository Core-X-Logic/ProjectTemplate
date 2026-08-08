# ADR-0018: RLS host görünürlüğü — bypass politikanın içinde, SECURITY DEFINER fonksiyonlarda değil

- **Durum:** Accepted · **Tarih:** 2026-08-08
- **İş kaydı:** RLS taban çizgisi (`feat/rls-baseline-and-identity-hardening`) · risk `R-08`

## Bağlam

RLS taban çizgisi, kiracı izolasyonunu uygulama katmanından (`@Filter` +
`HibernateTenantFilterAspect`, ADR-0003) veritabanı zeminine indiriyor. Sıra şu: Adım 1 kimlik
ayrımı (`V11__app_role.sql`: uygulama `zero_app` ile bağlanır — NOSUPERUSER NOBYPASSRLS — Flyway
owner/superuser ile; tek kimlikle RLS **kanıtlanamaz**, owner FORCE olmadan kendi politikasını
atlar, superuser her hâlükârda atlar). Adım 2'de aspect artık her `@Service` çağrısında
transaction-local iki ayar yazıyor: `app.current_tenant` ya da `app.is_host`. Adım 4'te tablolara
politika ekleniyor (`V12`, `V13`). Karar verilmesi gereken şey **host bağlamının politikadan nasıl
geçtiği**.

Zero Platform'un host tarafı, kiracıya ait tablolarda **çapraz-kiracı okuma yapmak zorundadır** ve
bu bir kaza değil, üründür: host denetçisi tüm kiracıların `audit_logs` / `entity_changes`
satırlarını görür (`TenantFilterCoverageIT` bunu iki yönlü olarak assert eder), host admin tüm
abonelikleri yönetir (ADR-0015), `TenantAdminBootstrapper` yeni bir kiracının admin'ini host
isteğinin içinde yazar. Saf bir `tenant_id = current_setting('app.current_tenant')::bigint`
politikası bu yolların **hepsini** sessizce 0 satıra düşürür.

Üç seçenek vardı:

1. **SECURITY DEFINER yolu:** host işleri `SECURITY DEFINER` fonksiyonlara taşınır; app rolü
   `NOBYPASSRLS` kalır, muafiyet **fonksiyonda** olur ("BYPASSRLS rolde değil fonksiyonda").
2. **İkinci politika:** `HostVisibility ... USING (tenant_id IS NULL AND
   current_setting('app.is_host', true) = 'on')`.
3. **Tek politikanın içinde bypass:** `USING (tenant_id = ... OR
   current_setting('app.is_host', true) = 'on')`.

## Karar

**Seçenek 3.** `tenant_isolation` politikası host bypass'ını kendi içinde taşır; hem `USING`
hem `WITH CHECK` şu şekli alır:

```sql
USING      (tenant_id = nullif(current_setting('app.current_tenant', true), '')::bigint
            OR current_setting('app.is_host', true) = 'on')
WITH CHECK (tenant_id = nullif(current_setting('app.current_tenant', true), '')::bigint
            OR current_setting('app.is_host', true) = 'on')
```

`app.is_host` **yalnız** `TenantContext.isHost()` iken (yani `tenant_id` çözülmemişken)
`'on'` olur; kiracı bağlamında aynı aspect onu boşaltır, çünkü ikisinin birden set olması
politikayı her satır için doğru yapardı (`GucTenantContextIT` bu iki geçişi de ölçer).

RLS, entity başına değişen `hostFilter` (`tenant_id is null`) daralmasını **taklit etmez**;
o karar uygulama katmanında kalır. Politikanın host kolu "host her satırı görebilir" der,
"host yalnız host satırlarını görür" demez — çünkü tablo seviyesindeki politika, o okumanın
hangi entity görünümü için yapıldığını bilemez. Seçenek 2 tam olarak burada kırılır: politikalar
aynı komut için OR'lanır, ama `tenant_id IS NULL` koşulu host'un çapraz-kiracı denetim
görünürlüğünü kaldırır.

## Gerekçe ve bilinçli takas

**RLS host principal'ını DOĞRULAMAZ.** `app.is_host = 'on'`, uygulama katmanının "bu istek host
isteğidir" kararının veritabanına yansımasıdır; veritabanı bu kararı denetleyemez, yalnız ona
uyar. Dolayısıyla:

- **Host yetkisinin gerçek kilidi uygulama katmanında kalır:** `SecurityConfig` +
  `@PreAuthorize` (host-only izinler, `Side.HOST`) + `TenantResolverFilter` +
  `AuthenticatedTenantFilter`'ın JWT-claim otoritesi (ADR-0003: header ile claim uyuşmazlığı
  403; `TenantEscalationIT` kanıtı).
- **RLS'in kapsadığı hata sınıfı:** *kiracı bağlamında* filtresi/predicate'i unutulmuş bir
  sorgu ya da native SQL. Burada zemindir ve sonucu fail-closed'dır.
- **RLS'in kapsamadığı hata sınıfı:** *host bağlamında* yanlış açılmış bir uç. Host bağlamı
  yanlış kurulduysa politika buna itiraz etmez. Bir uç yanlışlıkla host'a açılırsa onu yakalayan
  şey RLS değil, izin katmanı ve o ucun negatif testidir.

SECURITY DEFINER modeli (seçenek 1) **daha güçlüdür**: muafiyeti veri yoluna değil, adı ve imzası
olan sayılı fonksiyona bağlar, `REVOKE ALL ... FROM PUBLIC` + tek role `GRANT EXECUTE` ile kapatır.
Bu şablon için **çok pahalıdır**: host tarafı bugün `hostFilter` / "filtre yok" davranışına
dayanıyor ve bu davranış audit, notification, SaaS yönetimi, tenant bootstrap ve identity
yollarına yayılmış durumda. Hepsini `SECURITY DEFINER` fonksiyonlara taşımak, bu işi "RLS ekleme"
işinden "host veri erişim katmanını yeniden yazma" işine çevirir. Bugün alınan koruma, sızıntı
riskinin gerçekten yoğunlaştığı yerde (kiracı bağlamı) tamdır.

## Sonuçlar

- (+) Tablo başına maliyet ~15 satır SQL; mevcut host yolları değişmeden çalışır.
- (+) Kiracı bağlamındaki her sorgu, `@Filter` unutulsa bile veritabanında daralır; GUC hiç
  yazılmamışsa sonuç sızıntı değil **0 satır**dır (`current_setting(..., true)` → NULL).
- (−) **Host bağlamı RLS ile korunmaz.** Azaltım zorunludur: host'a açılan her yeni uç için
  host-only izin **ve** negatif yetki testi (dikey dilim tanımının "negatif yetki" sütunu).
  Bu, ADR-0015'teki tasarım borcunun aynısıdır ve aynı disiplinle kapatılır.
- (−) `app.is_host` yeni bir güvenlik yüzeyidir: onu `'on'` yapabilen yerler aspect'in host kolu
  ve — ölçülmüş tek istisna — `DataSeeder.announceHostContextToDatabase()`'dir (bir `@Component`
  `ApplicationRunner` olarak aspect'e hiç uğramaz ve host kapsamı tanım gereğidir). Bunların
  dışında bir yerde `set_config('app.is_host', 'on', ...)` çağrılması, o transaction için
  izolasyonu tamamen kaldırır — bu satır kod incelemesinde **kırmızı bayraktır**.
- (−) **Politika metni `nullif(..., '')` kullanmak ZORUNDA.** Ölçüldü (postgres:16):
  `is_local = true` ile yazılan bir GUC, transaction bittiğinde "hiç set edilmemiş" hâline
  DÖNMEZ; placeholder GUC'un varsayılanı olan **boş string**e döner. `''::bigint` NULL değil
  **hata** verir ve `OR` kısa devre yapmaz — yani düz `::bigint` şekli, havuzdan gelen ve daha
  önce bir kiracıya hizmet etmiş her bağlantıda host okumalarını da patlatırdı. `nullif` ile boş
  değer NULL'a çevrilir ve fail-closed davranış korunur.
- İzolasyon IT'leri (`RlsIdentityIsolationIT`, `RlsAuditNotificationIsolationIT`) bu ADR'nin
  negatif kanıtını taşır: GUC yokken 0 satır, yabancı `tenant_id` ile yazma reddi. Host kolunun
  kendi negatif testi de oradadır (`app.is_host` set edilmemiş bir transaction hiçbir kiracı
  satırını görmemeli).
