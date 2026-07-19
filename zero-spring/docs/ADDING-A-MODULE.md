# Yeni Modül Ekleme Yordamı

Bu şablona yeni bir dikey dilim (backend modülü + React ekranı) eklemenin tam yordamı.
Aşağıdaki her kural, bu depodaki gerçek koddan doğrulanarak yazılmıştır; doğrulama komutları
ilgili bölümlerde belirtilmiştir.

**Bağlam:** Spring Boot 3.5.5 + Spring Modulith 1.4.5 (`zero-spring/backend/pom.xml` satır 10 ve 22),
React 19 + Vite + TypeScript (`zero-spring/frontend/app/package.json`).

Kökler:
- Backend: `D:/Private/Cafer AYDIN/StartupProjectTemplate/zero-spring/backend`
- Frontend: `D:/Private/Cafer AYDIN/StartupProjectTemplate/zero-spring/frontend/app`

---

## 1. Spring Modulith — paket yerleşimi ve modül sınırı

### 1.1 Yerleşim

Yeni modül, uygulama paketinin **doğrudan alt paketi** olur:
`zero-spring/backend/src/main/java/com/mycompanyname/zero/<modul>/`

Bugün mevcut 10 modül (doğrulama: `ls zero-spring/backend/src/main/java/com/mycompanyname/zero/`):
`audit`, `config`, `identity`, `localization`, `notification`, `saas`, `seed`, `settings`, `shared`, `tenancy`.

Modül içi standart yerleşim — `saas` modülü en olgun örnek:

```
<modul>/
  package-info.java          <- modül sınırı burada tanımlanır
  <Modul>Permissions.java    <- izin sabitleri (modül kendi izinlerine sahiptir)
  domain/                    <- entity + repository + registry
  api/                       <- @NamedInterface: dışarı açılan yüzey (gerekliyse)
  web/                       <- @RestController
  web/dto/                   <- record DTO
```

### 1.2 `package-info.java` — ZORUNLU, ama test seni zorlamaz

```java
@ApplicationModule(allowedDependencies = {"shared"})
package com.mycompanyname.zero.<modul>;

import org.springframework.modulith.ApplicationModule;
```

Mevcut bildirimler (doğrulama: her `package-info.java` okundu):

| Modül | `allowedDependencies` |
|---|---|
| `audit` | `{"shared"}` |
| `settings` | `{"shared"}` |
| `localization` | `{"shared"}` |
| `tenancy` | `{"shared"}` |
| `notification` | `{"shared", "settings"}` |
| `saas` | `{"shared", "tenancy", "settings"}` |
| `identity` | `{"shared", "tenancy", "config", "settings", "notification", "notification :: email", "saas :: api"}` |
| `shared`, `config`, `seed` | `type = ApplicationModule.Type.OPEN` |

**Yeni modül için başlangıç değeri `{"shared"}` olsun.** Bir bağımlılık gerçekten gerekli olduğunda
listeye ekle; asla ters yönde bağımlılık kurma (`saas` çekirdeği `identity`'ye bağlanmaz — bunun yerine
`saas :: api` named interface'i `identity` tarafından tüketilir).

### 1.3 ⚠️ ModularityTests, `package-info` yazmayan modülü YEŞİL geçirir

Bu, bu şablondaki en önemli sessiz tuzaktır ve **deneysel olarak doğrulanmıştır**.

`zero-spring/backend/src/test/java/com/mycompanyname/zero/ModularityTests.java` yalnızca
`ApplicationModules.of(ZeroPlatformApplication.class).verify()` çağırır. Modulith'te
`allowedDependencies` varsayılanı **boş dizidir ve boş dizi "kısıt yok" anlamına gelir** —
"bağımlılık yok" değil. Yani `package-info.java` yazmayan bir modül **her şeye bağlanabilir**
ve doğrulama yine geçer.

**Kanıt (bu depoda çalıştırıldı, sonra geri alındı):**

1. `com/mycompanyname/zero/probetmp/ProbeComponent.java` oluşturuldu; `TenantService`,
   `SettingManager` ve `LocalizationService`'e (üç ayrı modül) bağımlı. `package-info.java` **yok**.
   → `./mvnw -o -Dtest=ModularityTests test` → `Tests run: 1, Failures: 0, Errors: 0` / `BUILD SUCCESS`

2. Aynı pakete `@ApplicationModule(allowedDependencies = {"shared"})` içeren bir `package-info.java`
   eklendi, kod değiştirilmedi.
   → `BUILD FAILURE`, üç ihlal:
   ```
   - Module 'probetmp' depends on module 'tenancy' via ...ProbeComponent -> ...TenantService. Allowed targets: shared.
   - Module 'probetmp' depends on module 'settings' via ...ProbeComponent -> ...SettingManager. Allowed targets: shared.
   - Module 'probetmp' depends on module 'localization' via ...ProbeComponent -> ...LocalizationService. Allowed targets: shared.
   ```

**Sonuç:** `package-info.java` yazmak "testin dayattığı bir formalite" değil, **senin üstlendiğin bir
sorumluluktur.** Yazmazsan modül sınırın yoktur ve hiçbir şey seni uyarmaz. Sınır ihlali mesajı ancak
sen sınırı beyan ettikten sonra ortaya çıkar.

(Not: doğrulama tek yönlü değildir — beyan edilmiş bir modül senin yeni modülünün *içine* uzanırsa
o taraf kırılır. Kırılmayan taraf, yalnızca senin modülünün **dışarı** olan bağımlılıklarıdır.)

### 1.4 Dışarı açma — `@NamedInterface`

Bir modülün üst paketindeki `public` tipler zaten dışarıya açıktır; **alt paketler kapalıdır.**
Bir alt paketi tüketilebilir kılmak için o pakete kendi `package-info.java`'sını yaz:

```java
@NamedInterface("api")
package com.mycompanyname.zero.<modul>.api;

import org.springframework.modulith.NamedInterface;
```

Mevcut örnekler:
- `saas/api/package-info.java` → `@NamedInterface("api")`; tüketici `identity`, `"saas :: api"` yazar.
- `notification/email/package-info.java` → `@NamedInterface("email")`; tüketici `"notification :: email"`.
- `identity/domain/package-info.java` → `@NamedInterface("domain")` (ayrıca `@FilterDef`'leri taşır).
- `identity/repo/package-info.java` → `@NamedInterface("repo")`.

**Kural:** modüllerarası erişim yalnız (a) hedef modülün üst paketi ya da (b) açıkça `@NamedInterface`
ile işaretlenmiş bir alt paket üzerinden olur; ve tüketen modülün `allowedDependencies` listesinde
hedef ismen yer almalıdır. Döngü kurma; döngü gerekiyorsa event kullan
(`tenancy/TenantCreatedEvent.java` bu desenin örneğidir).

---

## 2. Flyway — yeni migration

Dizin: `zero-spring/backend/src/main/resources/db/migration/`
Mevcut: `V1__baseline.sql`, `V2__phase2.sql`, `V3__notifications.sql`, `V4__saas.sql`,
`V5__shedlock.sql`, `V6__hardening.sql`.

Yeni modülün şeması **yeni bir `V7__<modul>.sql`** dosyasına yazılır.

### ⛔ Uygulanmış bir migration'ı DÜZENLEMEK YASAKTIR

Sebep, `V6__hardening.sql` dosyasının kendi başlığında zaten yazılı (satır 4-5):

> `-- V1/V2 are deliberately NOT edited: Flyway records their checksums, and changing an applied`
> `-- migration breaks every existing installation on the next boot.`

Mekanizma: Flyway her uygulanmış dosyanın **checksum**'ını `flyway_schema_history` tablosuna yazar.
Dosyayı sonradan değiştirirsen checksum tutmaz ve **halihazırda migrate edilmiş her kurulum** bir
sonraki açılışta `ValidateException` ile ayağa kalkamaz. Testler bunu yakalayamaz: Testcontainers her
koşuda **temiz** bir veritabanı kullanır, orada geçmiş yoktur, dolayısıyla checksum çakışması hiç
oluşmaz — klasik false-green.

**Doğru yol:** yanlış giden şeyi düzeltmek için **yeni** bir `V<n+1>__...sql` yaz. `V6` tam olarak
bunun örneğidir: `V1`/`V2`'deki eksikleri düzeltmek için `V1`/`V2`'ye dokunmadan eklenmiştir.

### Şema konvansiyonları (V4 başlığından)

- PK: `id bigint generated by default as identity primary key`
- Zaman: `timestamptz`; audit kolonları `created_at/created_by/updated_at/updated_by`
- Para: `numeric(19,4)` + ayrı `currency varchar(3)`
- Adlandırma: `uq_` (unique constraint), `ix_` (index)
- PostgreSQL 15+ gerekir (`unique nulls not distinct` kullanılıyor; `PostgresVersionGuard` bunu
  BEFORE_MIGRATE callback olarak kapıda kontrol eder)

`MigrationGuardIT` (`src/test/java/.../config/MigrationGuardIT.java`) migration'ların gerçekten
uygulandığını `flyway_schema_history` üzerinden doğrulayan mevcut örnektir; yeni bir migration için
aynı deseni izleyebilirsin.

---

## 3. İzinler — kaç adımda kayıt?

Kodu okuyarak çıkarılan cevap: **elle 5 dosya dokunuşu, seed uzlaştırması otomatik.**

### Adım 1 — sabit tanımı

`identity/domain/AppPermissions.java` içine sabiti ekle:

```java
public static final String REPORTS_READ = "reports.read";
```

### Adım 2 — `all()` kümesine ekle (AYRI ADIM, unutulması kolay)

Aynı dosyadaki `all()` metodu sabitleri **elle** sayar. Sabiti tanımlayıp `all()`'a eklemezsen
izin hiçbir role verilmez. Bugün `all()` **22 izin** döndürüyor.

### Adım 3 — ağaca kayıt + `Side` seçimi

`identity/domain/PermissionDefinitions.java` → `TREE` listesine:

```java
group(GROUP_REPORTS, GROUP_ADMINISTRATION, Side.BOTH),
leaf(AppPermissions.REPORTS_READ, GROUP_REPORTS, Side.HOST),
```

`Side` (`identity/domain/Side.java`): `HOST` | `TENANT` | `BOTH`.
- `HOST` → yalnız host kullanıcılar tutabilir. `hostOnlyPermissionNames()` bu izni döndürür ve
  seed uzlaştırması onu **her tenant Admin rolünden çıkarır**.
- `BOTH` → hem host hem tenant.

`GROUP_*` sabiti yeni bir grup açıyorsan onu da aynı dosyanın başındaki sabit bloğuna ekle.
Bir düğüm, başka bir düğüm onu `parent` olarak beyan ettiği anda otomatik olarak "grup" sayılır
(`isGroup()`), ayrı bir bayrak yoktur.

### Adım 4 — i18n (2 dosya)

`src/main/resources/i18n/messages_en.properties` **ve** `messages_tr.properties`:

```
Permission.reports.read=View reports
Permission.reports.read=Raporları görüntüle
```

Anahtar kalıbı `Permission.` + iznin adı (`PermissionDefinitions.KEY_PREFIX`, `displayNameKey`).
Grup düğümleri için de aynı kalıp: `Permission.Pages.Administration.Reports=...`.

> **Doğrulanmış tuzak:** `messages_*.properties` içinde ayrıca eski ABP adlandırmasıyla bir blok var
> (`Permission.Pages.Administration.Users.Edit=...` gibi, satır 20-32). Bunlar **ölü anahtarlardır**;
> canlı olan blok dosyanın kendi yorumunda işaretli: `# --- Permission leaves keyed by permission
> name (resolved by PermissionService.displayName) ---`. Yeni izni **o bloğa** ekle, üsttekine değil.

### Adım 5 — modülün kendi izin sınıfı (modül `identity`'ye bağlanamıyorsa)

`saas` deseni: modül `identity`'ye bağlanamadığı için izin string'leri **iki yerde** yaşar —
`saas/SaasPermissions.java` (modülün `@PreAuthorize`'ları için) ve `identity/domain/AppPermissions.java`
(ağaca kayıt için). İkisinin ayrışmasını `SaasPermissionsAlignmentTest` engeller; bu testin
karşılığını yeni modülün için de yaz.

Modülün `identity`'ye zaten bağlıysa (`identity` altında bir alt modül gibi) bu adım gerekmez.

### Seed uzlaştırması — otomatik, ama neden orada olduğunu bil

`seed/DataSeeder.java` → `reconcileStaticRolePermissions()` **her açılışta** çalışır ve statik
`Admin` rollerini yeniden hizalar:
- host `Admin` → tam olarak `AppPermissions.all()`
- her tenant `Admin` → `all()` eksi `PermissionDefinitions.hostOnlyPermissionNames()`
- `isStatic = false` roller **hiç okunmaz ve hiç yazılmaz**
- kümesi zaten eşleşen rol için UPDATE atılmaz

Yani Adım 1-3'ü doğru yaparsan seed tarafında yapacak işin yoktur. Bu metodun neden ayrı ve neden
`zero.seed.enabled` yerine `zero.seed.reconcile-permissions` (varsayılan `true`) bayrağına bağlı
olduğu dosyadaki Javadoc'ta yazılıdır ve gerçek bir prod olayının sonucudur (F5-R9): prod
`SEED_ENABLED=false` ile çalıştığı için uzlaştırma hiç koşmuyordu, host admin **22 izinden 17'sini**
tutuyordu ve `GET /api/editions` 403 dönüyordu — temiz DB test paketi bunu göremez.

> **Doğrulanmış tutarsızlık (yeni modülde tekrarlama):** `AppPermissions.all()` **22** isim
> döndürüyor, `PermissionDefinitions.leafPermissionNames()` ise **21**. Fark: `ROLES_MANAGE`
> (`"roles.manage"`) `all()` içinde var ama `TREE` içinde yok. Sonuç: Admin'e veriliyor, fakat izin
> ağacı ekranında hiç görünmüyor, dolayısıyla başka bir role elle atanamıyor. Bu geriye dönük
> uyumluluk kalıntısıdır. **Genel bir koruma testi yok** — yalnız SaaS izinleri için
> `SaasPermissionsAlignmentTest` var. Yeni modülünde `all()` ile `TREE`'yi hizada tutan bir test yaz.

---

## 4. Yetkilendirme — üçlü kilit

Aynı izin **üç yerde** ifade edilir. Üçü de gereklidir; ikisi güvenlik değil UX'tir, biri gerçek kilittir.

### 4.1 Backend — `@PreAuthorize` (gerçek kilit)

**Sabit kullan, ham string yazma:**

```java
// DOĞRU — saas/edition/web/EditionController.java:41
@PreAuthorize("hasAuthority('" + SaasPermissions.EDITIONS_READ + "')")

// YANLIŞ — identity/web/UserController.java:40
@PreAuthorize("hasAuthority('users.read')")
```

**Doğrulanmış durum** (komut:
`grep -rn "@PreAuthorize" --include=*.java . | grep -v "SaasPermissions\.\|AppPermissions\." | grep hasAuthority | wc -l`):

| | Adet |
|---|---|
| Toplam `@PreAuthorize` | 56 |
| Sabit üzerinden (doğru) | 15 — hepsi `saas` modülünde |
| **Ham string literal (yanlış örnek)** | **31** |

Ham literaller: `AuditLogController` (3), `ImpersonationController` (1), `OrganizationUnitController` (5),
`RoleController` (6), `UserController` (11), `SettingController` (4), `TenantController` (1).

Bunlar Faz 1-2 kalıntısıdır ve **taklit edilmemelidir**. Ham string, izin adı değiştiğinde derleyicinin
sessiz kalması demektir: uç nokta erişilemez hale gelir ve hiçbir test bunu zorunlu olarak yakalamaz.
Yeni modülde `saas` desenini izle.

### 4.2 Frontend — route guard

`frontend/app/src/auth/require-auth.tsx` → `<RequireAuth permission="...">`.
Kayıt yeri: `frontend/app/src/routing/routes.tsx`.

```tsx
<Route
  path="reports"
  element={
    <RequireAuth permission="reports.read">
      <ReportsPage />
    </RequireAuth>
  }
/>
```

`anyPermission={[...]}` varyantı "en az biri" içindir (SaaS grubu bunu kullanır).
İzin yoksa `<ForbiddenPage />` render edilir, yönlendirme yapılmaz.

### 4.3 Frontend — `<Can>` bileşeni

`frontend/app/src/auth/rbac.tsx` → `<Can permission="...">`. Sayfa **içindeki** aksiyonları
(buton, satır menüsü) gizler. Örnek: `features/organization-units/components/ou-node.tsx:89`.

```tsx
<Can permission={REPORTS_MANAGE_PERMISSION}>
  <Button onClick={...}>Yeni rapor</Button>
</Can>
```

`rbac.tsx` dosyasının kendi yorumu bu ayrımı açıkça kurar:
*"Frontend guards are UX only — the backend enforces the same permissions via `@PreAuthorize`
(double lock). Never rely on these for security."*

### 4.4 Menü görünürlüğü (dördüncü dokunuş)

`frontend/app/src/config/menu.config.tsx` → girdiye `permission: 'reports.read'` ekle.
`filterMenuByPermission`, çocuklarının hiçbirini göremeyen bir grubu tamamen düşürür — tenant
operatörü boş bir başlık görmez.

---

## 5. Kiracılık — `tenant_id` + Hibernate Filter

Tenant'a ait her yeni entity için **üç şey birden** gerekir. Biri eksikse veri sızar.

**1) Migration'da kolon:**
```sql
tenant_id bigint references tenants(id),
```
ve `create index ix_<tablo>_tenant on <tablo>(tenant_id);`

**2) Entity'de alan + iki `@Filter`** (`identity/ou/OrganizationUnit.java` örnek):

```java
@Entity
@Table(name = "reports")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Filter(name = "hostFilter", condition = "tenant_id is null")
public class Report extends AbstractAuditedEntity {

    @Column(name = "tenant_id")
    private Long tenantId;
    ...
}
```

`@FilterDef` tanımları tek yerde, `identity/domain/package-info.java` içinde bildirilir; entity'de
yalnız `@Filter` kullanılır, yeniden tanımlama yapma.

**3) Servis sınıfı `@Service` ile işaretli olmalı.** Filtreleri açan
`tenancy/HibernateTenantFilterAspect.java` pointcut'ı `within(@org.springframework.stereotype.Service *)`
şeklindedir. `@Component` ile işaretlenmiş bir sınıf **bu aspect'in kapsamına girmez.**

### ⚠️ Bunu unutmak sessiz sızıntı üretir

Aspect'in kendi yorumu şunu söyler: *"Second line of defense for tenant isolation (explicit
tenant-scoped repository queries are the primary defense)."* Yani filtre **ikinci** savunma hattıdır;
birinci hat repository sorgularının kendisidir (`findAllByTenantId(...)`).

Eğer `@Filter` anotasyonlarını koymazsan:
- kod derlenir,
- testler geçer (tek tenant'lı bir IT hiçbir fark görmez),
- ve `findAll()` benzeri her çağrı **tüm tenant'ların satırlarını** döndürür.

Hiçbir hata mesajı oluşmaz. Bu yüzden yeni modülün IT'sinde **tenant izolasyonu negatif testi
zorunludur**: tenant A'nın kaydını oluştur, tenant B ile listele, görünmediğini doğrula
(`identity/OrganizationUnitIT` ve `saas/SaasAuthorizationIT` bu deseni taşıyor).

---

## 6. i18n — en + tr

### Backend
`src/main/resources/i18n/messages_en.properties` ve `messages_tr.properties`.
İzin adları (§3 Adım 4), hata mesajları ve sunucu tarafında çözülen etiketler buraya.
Bugün 45 adet `Permission.*` anahtarı var.

### Frontend — iki katman

**Global katman:** `frontend/app/src/i18n/messages/en.ts` ve `tr.ts`.
Yalnız gerçekten uygulama geneli olan anahtarlar (menü başlıkları `nav.*` gibi) buraya.
Yeni modülün menü girdisi için `nav.reports` anahtarını **iki dosyaya birden** ekle.

**Özellik katmanı:** `frontend/app/src/features/<modul>/messages.ts`.
Modülün kendi anahtarları burada yaşar ve sayfa bunları iç içe bir `IntlProvider` ile ortam
kataloğuna karıştırır — global katalog kirlenmez.
Örnek dosya: `features/organization-units/messages.ts`, kendi yorumunda deseni açıklıyor:
*"The global catalogues stay untouched: the page merges these keys into the ambient `IntlProvider`
via a nested provider."*

**Kural:** `en` ve `tr` anahtar kümeleri **birebir** aynı olmalı.

---

## 7. Testler — en az 1 backend IT + 1 frontend davranış testi

### Backend IT

Taban sınıf: `src/test/java/com/mycompanyname/zero/AbstractIntegrationIT.java`
(Testcontainers PostgreSQL 16, singleton-container deseni — **tek** container ve **tek** Spring
context tüm IT sınıflarınca paylaşılır).

**En iyi örnek: `saas/EditionCrudIT.java` + `saas/AbstractSaasIT.java`.**
Neden bu ikili:
- `AbstractSaasIT` modüle özgü fixture'ları (`host()`, `tenantAdmin()`, `ensureTenant(...)`) tek
  yerde toplar — yeni modülün için doğrudan kopyalanabilir yapı.
- Context paylaşıldığı için her fixture ya **benzersiz adlı** ya da **idempotent**tir. Bu kural
  `AbstractSaasIT` yorumunda açıkça yazılı; yeni modülde ihlal edersen testler tek başına yeşil,
  paket halinde kırmızı olur.
- `EditionCrudIT` yalnız mutlu yolu değil, iş kurallarını da (409/400) kanıtlar.

Yeni modül için IT'nin **asgari** kapsamı:
1. yetkili kullanıcıyla CRUD turu,
2. izni olmayan kullanıcı → **403**,
3. tenant izolasyonu (§5) → başka tenant'ın kaydı görünmüyor.

Diğer faydalı örnekler: `permission/PermissionTreeIT` (ağaç host/tenant filtresi),
`saas/SaasAuthorizationIT` (escalation negatifleri), `config/MigrationGuardIT` (migration kanıtı).

### Frontend davranış testi

Harness: `frontend/app/src/test/utils.tsx` → `renderWithProviders(...)`, `App.tsx` ile aynı provider
zincirini kurar (Helmet → Query → Intl → Auth → Tenant → Router).

**En iyi örnek: `features/organization-units/__tests__/ou-tree.test.tsx`.**
Gösterdiği desen:
- `vi.hoisted` ile paylaşılan test durumu,
- `@/providers/auth-provider` `useAuth` sınırında mock'lanır → her test **izin kümesini kendisi
  belirler** (`<Can>` guard'ının iki dalını da test edebilirsin),
- özellik API modülü mock'lanır → sorgu deterministik veriyle çözülür,
- `sonner` toast'ları mock'lanır.

Bugün depoda 19 `.test.tsx` dosyası var (16'sı `features/` altında).
Konum: `features/<modul>/__tests__/<sayfa>.test.tsx`.

Asgari kapsam: render + boş/yükleniyor/hata durumları + RBAC (izinli / izinsiz iki senaryo).

---

## 8. ⚠️ Sayfalama tuzağı — `@EntityGraph` + `Pageable` BİRLİKTE KULLANILMAZ

Bir repository metodu hem koleksiyon fetch'i (`@EntityGraph`, `join fetch`) hem de `Pageable`
alıyorsa, Hibernate **`LIMIT`/`OFFSET` üretemez.** Sebep: join sonrası satır sayısı entity sayısına
eşit değildir, dolayısıyla SQL seviyesinde sayfalamak yanlış sonuç verir. Hibernate bunun yerine
**tüm sonuç kümesini belleğe çeker ve sayfalamayı JVM'de yapar**, yalnızca şu uyarıyı basar:

```
HHH90003004: firstResult/maxResults specified with collection fetch; applying in memory
```

Bu bir hata değil, uyarıdır. Uygulama çalışır, testler geçer, sonuçlar doğrudur — 50 satırlık test
verisiyle fark edilmez. 200.000 kullanıcılı bir kurulumda `GET /api/users?page=0&size=10` çağrısı
200.000 satırı belleğe alır.

**Bu depoda hâlen açık (PROD-R21):** doğrulama
`grep -rn "EntityGraph" --include=*.java src/main/java/` →

- `identity/repo/UserRepository.java` satır 36, 39, 48, 62 — dördü de `@EntityGraph("roles")` +
  `Page<User>` + `Pageable`
- `identity/repo/RoleRepository.java` satır 22, 25 — `@EntityGraph("permissions")` + `Page<Role>`

`CONTRACT-phase5.md` bu kalemi bilinçli olarak açık bıraktığını kayda geçirmiştir (feature freeze
kapsamı dışı, repository/sorgu tasarımı değişikliği gerektiriyor).

**Yeni modülde bu deseni kopyalama.** İki doğru yol var:

**A) İki aşamalı sorgu** — önce ID sayfası (fetch yok, gerçek `LIMIT`/`OFFSET`), sonra o ID'ler için
koleksiyonlu ikinci sorgu:

```java
@Query("select r.id from Report r where r.tenantId = :tenantId")
Page<Long> findIdsByTenantId(@Param("tenantId") Long tenantId, Pageable pageable);

@Query("select distinct r from Report r left join fetch r.tags where r.id in :ids")
List<Report> findAllWithTagsByIdIn(@Param("ids") List<Long> ids);
```

Servis, ikinci sorgunun sonucunu ilk sorgunun sırasına göre yeniden dizer ve
`new PageImpl<>(sirali, pageable, idSayfasi.getTotalElements())` döndürür.

**B) `@BatchSize`** — koleksiyonu lazy bırak, entity/koleksiyon üzerine
`@BatchSize(size = 50)` koy. Sayfalama SQL'de gerçekleşir, koleksiyonlar N+1 yerine sayfa başına
birkaç toplu sorguyla yüklenir. Daha az kod, biraz daha fazla gidiş-geliş.

Sayfa boyutu küçük ve koleksiyon dar olduğunda (B) yeterlidir; büyük sayfalar veya geniş
koleksiyonlar için (A) tercih edilir.

---

## Yeni modül kontrol listesi

**Modül iskeleti**
- [ ] `com/mycompanyname/zero/<modul>/` paketi açıldı
- [ ] `package-info.java` yazıldı, `allowedDependencies` **açıkça** beyan edildi (yazmazsan test seni uyarmaz — §1.3)
- [ ] Dışarı açılan alt paket varsa `@NamedInterface` + tüketicinin `allowedDependencies`'ine `"<modul> :: <ad>"` eklendi
- [ ] Ters bağımlılık / döngü yok (gerekirse event)
- [ ] `./mvnw -o -Dtest=ModularityTests test` yeşil

**Veritabanı**
- [ ] Yeni `V<n>__<modul>.sql` yazıldı; **hiçbir mevcut `V*.sql` düzenlenmedi** (checksum — §2)
- [ ] Konvansiyonlar: `identity` PK, `timestamptz`, `numeric(19,4)`, `uq_`/`ix_`

**Kiracılık**
- [ ] `tenant_id` kolonu + `ix_<tablo>_tenant` indeksi
- [ ] Entity'de `tenantId` alanı + `@Filter(tenantFilter)` **ve** `@Filter(hostFilter)`
- [ ] Servis `@Service` ile işaretli (aspect yalnız `@Service`'i sarar)
- [ ] Tenant izolasyonu negatif testi yazıldı (unutulursa sessiz sızıntı — §5)

**İzinler**
- [ ] `AppPermissions` sabiti eklendi
- [ ] Sabit `AppPermissions.all()` kümesine eklendi (ayrı adım)
- [ ] `PermissionDefinitions.TREE`'ye `leaf(...)` (+ gerekiyorsa `group(...)`) eklendi
- [ ] `Side` bilinçli seçildi (HOST → tenant Admin'den otomatik düşer)
- [ ] `all()` ile `TREE` hizasını koruyan test yazıldı (genel koruma testi yok — §3)
- [ ] Modül `identity`'ye bağlanamıyorsa `<Modul>Permissions` + alignment testi (`saas` deseni)

**Yetkilendirme (üçlü kilit)**
- [ ] Backend `@PreAuthorize` — **sabit üzerinden**, ham string değil (§4.1)
- [ ] `routes.tsx` içinde `<RequireAuth permission="...">`
- [ ] Sayfa içi aksiyonlarda `<Can permission="...">`
- [ ] `menu.config.tsx` girdisine `permission` eklendi

**i18n**
- [ ] `messages_en.properties` + `messages_tr.properties` — `Permission.<izin.adi>` (canlı bloğa, §6)
- [ ] `i18n/messages/en.ts` + `tr.ts` — `nav.<modul>`
- [ ] `features/<modul>/messages.ts` — `en`/`tr` anahtarları birebir

**Frontend iskeleti**
- [ ] `features/<modul>/` altında `api.ts`, `hooks.ts`, `types.ts`, `messages.ts`, `pages/`, `components/`, `__tests__/`
- [ ] `npm run gen:api` ile tipli istemci yeniden üretildi
- [ ] loading / empty / error durumları var

**Sorgular**
- [ ] Sayfalı sorgularda `@EntityGraph`/`join fetch` + `Pageable` **birlikte kullanılmadı** (§8)
- [ ] Koleksiyon gerekiyorsa iki aşamalı sorgu ya da `@BatchSize` tercih edildi

**Testler**
- [ ] ≥1 backend IT (`AbstractIntegrationIT`): CRUD + 403 + tenant izolasyonu
- [ ] Fixture'lar benzersiz adlı ya da idempotent (paylaşılan context)
- [ ] ≥1 frontend davranış testi (`renderWithProviders`, `useAuth` mock'lu, izinli/izinsiz iki dal)
- [ ] `./mvnw verify` ve `npm run build && npm run test` yeşil
