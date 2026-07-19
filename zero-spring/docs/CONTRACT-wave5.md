# Dalga 5 — Scope-lock sözleşmesi

**Statü:** bağlayıcı. Kapsam dışı bir iş yapılacaksa **önce bu dosya değişir**.

**Giriş hattı (ölçüldü):** frozen toplam **18** — Rule 4 = 12, Rule 5 = 6, Rule 1/2/3 = 0.
Backend **376** test (252 IT + 124 unit), frontend **123**. CI zinciri 7/7 yeşil (`849e881`).

**Kaba efor: 5,5–6 mühendis-günü.** Küçük bir dalga değil; W5-4 tek başına dalganın yarısı.

---

## 1. Kapsam

| # | İş | Ne eritir / kapatır | Efor |
|---|---|---|---|
| **W5-1** | Rule 4'ü **gerekçesini ölçecek** şekilde yeniden formüle et: "her `@Entity`, `@ApplicationModule` beyan eden bir modül kökünün altında olmalı". Dosya ekleyerek susturma **yok**. | Rule 4 **12 → 0** (meşru yoldan). R-37'yi kapatır ve kapsamını düzeltir (2 paket → **8 paket / 12 entity**). | 1 g |
| **W5-2a** | 6 korumasız handler'a `@PreAuthorize("isAuthenticated()")`. `INTENTIONALLY_ANONYMOUS`'a **hiçbiri eklenmez**. | Rule 5 **6 → 0**. Frozen toplam **0**. | 0,5 g |
| **W5-2b** | `AuthService.logout` ve `ImpersonationTokenStore.consume` için **sahiplik bağı**: iptal edilen token / redeem edilen ticket çağıranın kendi principal'ına ait olmalı. | Yeni **R-39** (cross-user oturum iptali), **R-40** (ticket sızıntısı, 30 sn pencere). W5-2a'nın "principal-türevli" sınıflandırmasını **doğru kılar**. | 0,5 g |
| **W5-3** | Ortak `ExportLimits` + `zero.export.max-rows` (varsayılan 10 000). `UserService.exportToExcel` **ve** `AuditLogService.export` sınırı `max+1` çekerek uygular. | **R-35** — iki sınırsız `XSSFWorkbook` yolu (ikincisi bu analizde bulundu). | 0,5 g |
| **W5-4** | `ApiPaths` kayıt sınıfı: `SecurityConfig` permitAll matcher'ları, `SubscriptionAccessCheck.DEFAULT_EXEMPT_PATHS` ve `@RequestMapping`'ler **tek kaynaktan**; reflection tabanlı hizalama testi. | **R-38 / alt-tür A** — sınıfın en sert üyesi: `identity`, `allowedDependencies`'inde **olmayan** `localization` üzerinde güvenlik kararı veriyor. Modulith, ArchUnit ve derleyici üçü de görmüyor. | 2–2,5 g |
| **W5-5** | i18n anahtar **iki yönlü küme eşitliği** + varlık testi. | **R-38 / B** — her iki bundle 100 anahtar; yalnız 45 `Permission.*` korunuyor → **55 anahtar korumasız**. Eksik anahtar kullanıcıya ham anahtar olarak gider. | 0,5 g |
| **W5-6** | `SettingNames` sabitleri + 10 ham literal; `AccountService`'teki ham `"tenantFilter"`/`"hostFilter"` literalleri mevcut sabitlere bağlansın. | **R-38 / C ve D**. | 0,5 g |

---

## 2. Kapsam dışı

### 2.1 Şablon disiplini gereği yasak

Yeni ürün özelliği/ekran/uç · faz dışı modül · `saas`'ın bölünmesi · alt paketlere `@ApplicationModule`
· **yeni `@NamedInterface`** (paketi dışa açar, sınırı gevşetir) · SXSSF/streaming (Dalga 6)
· frontend değişikliği (123 test sabit).

### 2.2 Bilinçli teknik borç

**Yok. Dalga 5 sonunda frozen sayısı 0 olmalıdır.**

Rule 4'ün 12 ihlali borç olarak **bırakılmıyor**, çünkü borç değil: **yanlış formüle edilmiş bir
kuralın artığı.** W5-1 kuralı düzeltir, borcu tanımaz.

### 2.3 Açıkça reddedilen öneri — boş `package-info.java` eklemek

`JavaSources.hasPackageInfo` yalnız **dosya varlığına** bakıyor: yukarı yürümüyor, içeriği
okumuyor, anotasyon aramıyor. Tek satırlık boş bir dosya kuralı susturur ve **Modulith
semantiği sıfır değişir** — alt paketler Modulith'te zaten internal. 12 boş dosya sonrası Rule 4,
hiçbir şey ölçmeyen, her koşulda yeşil bir kapı olur. Şablon olduğu için **her klona miras kalır**.

**Ölçülmüş kanıt:** 12 ihlalin tamamı, `allowedDependencies`'i **boş olmayan** 5 modülün
(`audit`, `identity`, `notification`, `saas`, `settings`) internal alt paketlerinde. Kuralın
gerekçesi — *"verify() hiç sınır beyan etmeyen modülü yeşil geçirir"* — bu 12 ihlalin
**hiçbirine** uymuyor. Gerekçenin geçerli olduğu yer `config`, `seed`, `shared` (üçü de
`Type.OPEN`) ve orada entity olmadığı için **kural oraya hiç bakmıyor**.

**Ters işaret:** kuralı bugün geçen 5 entity'nin 3'ü `identity.domain`'de ve o paketin
package-info'su `@NamedInterface("domain")` — yani paketi **dışa açıyor**. Rule 4 bugün, düzgün
kapsüllenmiş paketleri ihlal, dışa açılmışları temiz sayıyor.

### 2.4 `INTENTIONALLY_ANONYMOUS`'a ekleme yasağı

Liste açıkça *"her giriş `SecurityConfig`'teki bir `permitAll()` matcher'ını yansıtır"* diye
tanımlı; 6 ucun hiçbiri `permitAll` değil. Ekleme, listeyi **yalancı** yapar ve gelecekte
gerçekten anonim bir ucu gizler.

### 2.5 Yeni izin sabiti yasağı (Rule 5)

6 ucun hiçbiri hak etmiyor: 4'ü principal-türevli, 1'i veri dönmüyor (204), 1'i bootstrap
allowlist'i. `/api/auth/me` için izin istemek **tanım gereği yanlıştır**: izinleri öğrenmek için
izin gerekirdi.

---

## 3. Frozen store disiplini — bağlayıcı

1. **Store otomatik büyüyemez.** Her commit `wc -l archunit_store/*` çıktısını yazar. Toplam
   **monoton azalmalı**; artan sayı commit'i geçersiz kılar.
2. `freeze.refreeze=true` ve `allowStoreCreation=true` **`archunit.properties`'e yazılamaz**;
   komut satırında tek seferlik kullanım da bu dalga boyunca yasak.
3. Store dosyalarına **elle satır eklenemez**.
4. **Yeniden adlandırma tuzağı — W5-1 için zorunlu ölçüm.** Bir kuralın `.as(...)` açıklaması
   değişince `stored.rules` anahtarı değişir ve ArchUnit onu **yeni kural** sayar.
   `allowStoreCreation=false` yalnız **dizinin** oluşturulmasını engeller; var olan store içinde
   yeni bir kuralın mevcut ihlallerinin sessizce dondurulup dondurulmadığı **bu depoda
   ÖLÇÜLMEDİ**. W5-1'in ilk adımı budur. Zorunlu çıktı: yeni UUID dosyası **0 satır**,
   `stored.rules` **5 kural satırı**, öksüz UUID dosyası **yok**. Sıfırdan farklı bir sayıyla
   donarsa W5-1 **başarısızdır**.
5. **"Göremediğini onaylayan" kural bırakmak yasak.** Dokunulan her kural/test, girdi kümesinin
   **boş olmadığını kendi içinde** doğrulamalı. Boş kümede yeşil dönebilen kontrol, kontrol
   değildir — `migration-drift` bu depoda tam olarak böyle davranabiliyordu.

---

## 4. Done kriterleri — sayılabilir

| # | Kriter (komut çıktısıyla kanıtlanır) |
|---|---|
| **W5-1** | Rule 4 dosyası **12 → 0 satır**; `stored.rules` tam 5 kural; öksüz UUID yok. **Hiçbir `package-info.java` eklenmemiş**: `find src/main -name package-info.java \| wc -l` → **15 → 15**. Kural, 17 entity gördüğünü assert eden bir vacuity guard taşır. |
| **W5-2a** | ✅ **TAMAM.** Rule 5 **6 → 0**. `git diff` tam 6 `@PreAuthorize("isAuthenticated()")`. `INTENTIONALLY_ANONYMOUS` **7 girdi**, değişmemiş — *bu sözleşme ilk hâlinde **8** yazıyordu ve yanlıştı; gerçek liste `AuthController#login/refresh`, `AccountController#forgotPassword/resetPassword/confirmEmail`, `LocalizationController#dictionary/languages`. Yanlış sayı tehlikeliydi: bir denetçi "8 olmalı, biri eksik" deyip **girdi ekleyerek** §2.4'ü tam da yasakladığı yönde ihlal edebilirdi.* |
| **W5-2b** | 2 yeni IT: `logoutRejectsARefreshTokenBelongingToAnotherUser`, `impersonationTicketRejectsRedemptionByAnotherActor`. |
| **W5-3** | 4 yeni IT (sınır üstü 400, tam sınır 200 — her iki export yolu için). `ExportLimits` **tek** sınıf, iki tüketici. |
| **W5-4** | `SecurityConfig` ve `SubscriptionAccessCheck`'te **0 ham yol literali**. Hizalama testi en az **4 controller** gördüğünü assert eder. `/api/settings/client` muafiyeti **ölçülür**, sonuç RISK-REGISTER'a yazılır. |
| **W5-5** | `messageBundlesHaveIdenticalKeySets` + `everyMessageKeyResolvesInEveryBundle`; anahtar sayısının **0 olmadığı** ayrıca assert edilir. |
| **W5-6** | `App.*` literali `settings` dışında **10 → 0**; `"tenantFilter"`/`"hostFilter"` **16 → 14**. |
| **Toplam** | Frozen **18 → 0**. Backend **376 → ≥389**. Frontend **123** (değişmez). `ci-local.sh` tüm gate'ler yeşil. |

---

## 5. Negatif kanıt zorunlulukları

**Düzeltmeden önce testi yaz ve eski kodda düştüğünü gör.** Her satır `gate-auditor` ile ayrıca kanıtlanır.

| # | Neyi bozacaksın | Ne kırmızıya dönmeli | Bağlı olduğu ölçülmüş tuzak |
|---|---|---|---|
| W5-1a | `saas/package-info.java`'yı sil | **Yeni** Rule 4 kırmızı | — |
| **W5-1b** | Aynı silmeyi **eski** kuralla koştur | **Eski kural YEŞİL kalmalı** — `Subscription` zaten donmuş. W5-1'in tüm gerekçesi buna dayanır; yeşil kalmazsa gerekçe çürür | *Yeşil ≠ doğruladı* |
| ~~W5-1c~~ | ~~`shared`'dan `@ApplicationModule`'ü kaldır → yeni kural kırmızı (`AbstractAuditedEntity` üzerinden)~~ | ⚠️ **PREMİS YANLIŞTI — prob geçersiz.** Koşuldu: kural **YEŞİL** kaldı. Sebep kuralın körlüğü değil: `AbstractAuditedEntity` **`@MappedSuperclass`**, `@Entity` değil, ve `shared` altında **hiç `@Entity` yok**. Kural doğru davrandı. Aynı hata analizde "17 entity" rakamını da üretmişti (`^@Entity` deseni `@EntityListeners`'ı yakalıyor); **gerçek sayı 16** = 12 donmuş + 4 geçen (`User`, `Role`, `RefreshToken`, `Tenant`). `Type.OPEN` kabulünü ölçmek isteyen bir prob, `shared` altına gerçek bir `@Entity` koymayı gerektirir — kapsam dışı | *Yanlış sebeple yeşil'in tersi: **doğru sebeple yeşil**, ama probu geçersiz kılıyor* |
| W5-2a | 6 handler'dan **birini** seç, `@PreAuthorize`'ı kaldır; en az 2 turda **farklı** handler | Rule 5 kırmızı | *Sınıfı kapat, yazımı değil* |
| **W5-2b-1** | `logout` sahiplik kontrolünü kaldır | Test **iki gerçek kullanıcı ve iki gerçek token** ile yazılacak — tek kullanıcıyla yazılan test, kontrol olsun olmasın geçer | *Yanlış sebeple yeşil* |
| **W5-2b-2** | Ticket `actorUserId` karşılaştırmasını kaldır | İkinci aktör **farklı tenant'tan** olacak; aynı tenant'tan ikisi, ileride eklenecek bir tenant kontrolüyle testi yanlış sebeple yeşil tutabilir | *Yanlış sebeple yeşil* |
| W5-3a | `max+1` kontrolünü kaldır | Fixture **parametrik** olacak (`max-rows` testte 5'e çekilir), sabit 10 000 satır üretilmeyecek | — |
| **W5-3b** | Sınırı `max+1` yerine `max` yap (off-by-one) | Tam sınırdaki IT kırmızı. **İki test aynı fixture sayısıyla koşulmaz** — tek noktadan ölçüm, ikisini birden tesadüfen geçebilir | *Sıra testinin ASC'de geçip DESC'te yakalanması* |
| W5-3c | Yalnız `AuditLogService.export` sınırını kaldır | Audit tarafı kırmızı — tek taraflı düzeltme sınıfı kapatmaz | *Sınıfı kapat, yazımı değil* |
| W5-4a | `LocalizationController` yolunu `/api/l10n` yap | Hizalama testi kırmızı | — |
| **W5-4b** | Aynı değişikliği **test eklenmeden önce** yap, **tüm suite'i** koştur | **Suite YEŞİL kalmalı** — R-38 körlüğünün bu alt-türde de geçerli olduğunun ölçülmüş kanıtı. Kırmızı dönerse W5-4'ün gerekçesi zayıflar | *Yeşil ≠ doğruladı* |
| **W5-4c** | — | **Test bytecode'da yazılamaz**: `ApiPaths.X` bir `static final String`, javac çağrı yerinde **sabit-katlar**, `SecurityConfig` bytecode'unda referans kalmaz. Test ya runtime'da `@RequestMapping` okuyacak ya `JavaSources` kaynak-okuma tekniğini kullanacak. **ArchUnit bytecode kuralı kabul edilmez** | *javac sabit katlaması* |
| W5-5a | `messages_tr`'den bir anahtar sil | Eşitlik testi kırmızı | — |
| **W5-5b** | `messages_en`'e **fazladan** anahtar ekle | Aynı test kırmızı — tek yönlü (`en ⊆ tr`) yazılan test bunu kaçırır; **iki yönlü küme eşitliği** olacak | *Sınıfı kapat, yazımı değil* |
| W5-6a | `App.Password.RequiredLength`'i yeniden adlandır | Hizalama testi kırmızı; `SmtpEmailSender`'ın `catch (RuntimeException)` ile **sessizce** `@Value` fallback'ine düşmediğini gösteren ayrı test | *Sessiz fallback* |
| **W5-6b** | `TENANT_FILTER` sabitinin **değerini** değiştir | Mevcut izolasyon IT'si kırmızı. **Bugün kırmızı dönmez** (literal yazılı) — bu, düzeltmeden **önce** ölçülüp kayda geçirilecek | *R-38 körlüğü* |

---

## 6. Gate sırası

```
W5-1 ──► W5-2a ──► W5-2b ──┐
                            ├──► W5-4 ──► W5-5 ──► W5-6
              W5-3 ─────────┘
```

**W5-1 önce.** (a) Frozen store tek ve okunabilir bir diff'te değişmeli; yeniden adlandırma
tuzağı (§3.4) başka bir store değişikliğiyle karışırsa ölçüm bozulur. (b) Kural düzeltilmezse
"12 boş dosya ekle, yarım günde biter" önerisi bir sonraki oturumda **tekrar gündeme gelir** —
kural hem gerekçe hem kilittir.

**W5-2 ikinci.** W5-1 + W5-2a frozen'ı **0**'a indirir; sonraki işler her yeni ihlalin **sert
hata** olduğu bir zeminde yapılır. W5-2b, W5-2a'dan **sonra gelmek zorundadır**: `isAuthenticated()`
etiketini sahiplik bağı olmadan koymak kuralı "yanlış sebeple yeşil"e çevirir.

**W5-3 bağımsız** — ayrı mühendise verilebilir, dosya kesişimi yok.

**W5-4, W5-2'den sonra** — `INTENTIONALLY_ANONYMOUS` yorumları permitAll matcher'larını satır
satır aynalıyor; W5-4 o matcher'ları taşır.

**Kesim sırası:** zaman biterse önce W5-6, sonra W5-5 düşer. **W5-1…W5-4 kesilemez çekirdektir.**

Her iş için `stack-reviewer` commit'ten önce; §5 satırları için `gate-auditor` zorunlu.

---

## 7. Risk kabulü — Dalga 5 sonunda hâlâ açık

| ID | Açık kalan | Neden kabul |
|---|---|---|
| **R-36** | `RoleService.toDto` sayfa başına sayfa-boyutu kadar ekstra COUNT | Ölçülmüş yavaşlık yok, indeksli COUNT. Dalga 6'nın ilk kalemi |
| **R-35 (kalan)** | Akış yok; `@Transactional` tüm POI üretimini kapsıyor, `toByteArray()` tepe 2× kopya. **Tepe bellek ölçülmedi** | W5-3 hasarı sınırlar, kaldırmaz. Akış 2–3 gün + bağlantı yaşam süresi tasarımı |
| **R-38 / B** | Anahtar–tüketici bağı hâlâ string | 100 anahtarlık sabit sınıfı; kazanç/maliyet düşük |
| **R-38 / E,F,G** | Cache adları, ShedLock, `@Value` property'leri. Bilinmeyen cache adının sessiz mi kırıldığı **doğrulanamadı** | Düşük etki, modül sınırı aşmıyor |
| **R-38 / I** | 132 frontend izin dizesi, merkezî sabit yok | Frontend kapsam dışı; ayrı dalga |
| **Rule 5 ayırt etme gücü** | Kardeş kural — *"`isAuthenticated()` taşıyan handler, çağırandan gelen tanımlayıcı kabul etmemeli"* — **yazılmıyor**; yazılabilirliği doğrulanmadı | `/actuator/metrics` vakasının sınıfını kapatan gerçek invaryant budur. Yeni kural = ratchet'e yeni yüzey; Dalga 5 borç kapatıyor, yüzey açmıyor. Dalga 6 |
| **`/api/settings/client`** | Muafiyet listesinde **yok** (ölçüldü); süresi dolmuş kiracı 403 alır ama toparlanma ekranının bootstrap ayarları orada. **UI'yi gerçekten kırdığı doğrulanamadı** | W5-4'te ölçülür; düzeltmesi davranış değişikliği, ölçüme bağlı |
| **Miras** | PROD-R21, PROD-R23, PROD-R27, Issue #1 | Kapsam dışı, değişmeden devrediliyor |

**Yönetişim (dalga kapanışının parçası):** R-37'nin kapsam hatası düzeltilir ve W5-1 ile kapatılır;
R-38 alt-tür tablosuyla genişletilir; R-39/R-40 açılır; R-35 "sınırlandı, akış açık" olarak
yeniden yazılır. Rule 4'ün yeniden formülasyonu bir **ADR** gerektirir: eski kuralın neden yanlış
şeyi ölçtüğü ve boş `package-info` yolunun neden reddedildiği kayda geçer.
