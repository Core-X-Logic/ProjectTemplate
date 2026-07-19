# Güvenlik modeli

Bu şablonun güvenlik yüzeyi, gerekçeleriyle. Her başlıkta **ne garanti edildiği**, **neyin
garanti edilmediği** ve **hangi testin bunu tuttuğu** yazılı.

Buradaki kuralların çoğu bir kez ihlal edildiği ve ölçülebilir bir açık ürettiği için var.
Değiştirmeden önce ilgili testi okuyun.

---

## 1. Kiracı izolasyonu — en sessiz hata sınıfı

Bir izolasyon açığı hata vermez: **200 döner** ve yanlış kiracının verisini gösterir. Pozitif
testler bunu asla yakalamaz, çünkü onlar da 200 bekler.

### Otorite JWT claim'idir, header değil

İki filtre sırayla çalışır:

| Sıra | Filtre | Ne yapar |
|---|---|---|
| 1 | `TenantResolverFilter` | `X-Tenant` header'ından bağlam kurar — kimliksiz login için gerekli |
| 2 | `AuthenticatedTenantFilter` | Kimlik doğrulanmışsa JWT `tenant` claim'i **otoriterdir**; header claim'le çelişirse **403** |

Bu sıra pazarlık dışı. Yalnız header'a güvenmek, kullanıcının **kendi kiracısını seçmesi**
demektir — Faz 1'de bulunan ve düzeltilen açık tam olarak buydu.

*Kanıt:* `TenantIsolationIT`, `TenantEscalationIT`.

### Veri katmanı

Paylaşımlı şema + `tenant_id` ayırıcı; Hibernate `@Filter` sorgulara `tenant_id = :tenantId`
ekler.

> ⚠️ **Bu ikinci savunma hattı eksiksiz DEĞİL.** `tenant_id` taşıyan entity'lerin hepsinde
> `@Filter` yok (`AuditLog`, `EntityChange`, `UserNotification`, `TenantFeature`,
> `Subscription` eksik). Yani **"diğerleri nasıl yapmış" güvenilir bir örnek değildir**;
> kuralı çoğunluktan değil `ADR-0003`'ten alın. ArchUnit kuralı yeni entity'lerde bunu zorlar.

`saas` modülü **bilinçli** istisnadır (`ADR-0015`): orada `@Filter` yok, karşılığında her uç
için **negatif yetki testi zorunlu**.

### Filtreyi atlayan yollar

Yeni kod yazarken bilerek kontrol edin: ham SQL / native query, doğrudan `EntityManager`,
host bağlamında koşan servisler (`tenantId = null` — kasıtlı mı?), arka plan işleri ve
scheduler'lar (hangi bağlamda koşuyorlar?).

---

## 2. Yetkilendirme — üçlü kilit

| Katman | Ne yapar | Eksikse |
|---|---|---|
| Backend `@PreAuthorize` | **Gerçek kapı** | Yetkisiz kullanıcı veriyi alır — açık |
| Frontend `<Can>` | Butonu gizler | Kullanıcı tıklar, 403 yer — kötü deneyim |
| Route guard | Sayfayı korur | Boş/hatalı ekran |

**Frontend kontrolü güvenlik değildir.** İkisini de yapın, hangisinin gerçek kapı olduğunu
unutmayın.

### Ham string yazmayın

```java
@PreAuthorize("hasAuthority('users.raed')")   // derlenir · test geçer · SONSUZA DEK 403
```

Yazım hatası hiçbir yerde yakalanmaz: derleyici string'i doğrulamaz, test aynı yanlış string'i
kullanırsa yeşil kalır, endpoint sessizce erişilemez olur. **Depoda 31 ham literal var**
(`identity`, `audit`, `settings`) — bunlar düzeltilecek borç, kopyalanacak örnek **değil**.
Doğru örnek: `saas` modülü.

### Host / kiracı sınırı

Host kullanıcısı `tenantId = null` taşır. Host-only izinler (`settings.host.manage`,
`languages.manage`, `tenants.manage`, `editions.manage`) hiçbir kiracı rolüne verilemez.
Yeni izin eklerken `PermissionDefinitions` içindeki `Side` (HOST/TENANT/BOTH) **yanlış
seçilirse kiracı adminine host yetkisi verilir**.

> Bilinen tutarsızlık: `AppPermissions.all()` 22 isim döndürürken `PermissionDefinitions`
> 21 döndürüyor — `ROLES_MANAGE` ağaçta yok. Hizalama testi yalnız `saas`'ı kapsıyor.

---

## 3. Kimlik doğrulama

| Kalem | Karar | Kanıt |
|---|---|---|
| Algoritma | HS512 **pinli** (iki uçta) | `JwtAudienceIT` |
| Doğrulama | issuer **ve** audience zorunlu | `JwtAudienceIT` (4) |
| Access token | 15 dk | `ADR-0004` |
| Refresh token | Dönen (rotating), DB'de SHA-256 hash; **yeniden kullanım tespit edilirse aile revoke edilir** | `ADR-0004` |
| Şifre | BCrypt(12) + politika + geçmiş | `PasswordPolicyIT` |

**Açık kısıt:** `kid` claim'i ve çok-anahtarlı decoder yok → anahtar rotasyonu **tüm
oturumları düşürür**. Access token 15 dk boyunca **iptal edilemez**. Acil iptal gerekiyorsa
secret rotasyonu + 15 dk pencere kabul edilmelidir.

---

## 4. İstek koruması

| Koruma | Değer | Not |
|---|---|---|
| Rate limit | IP **ve** kullanıcı adı bazında, anonim uçlarda | Bucket'lar **JVM-local**: N replika = N × limit |
| Gövde sınırı (anonim throttled) | 16 KB | Daha sıkı olan kazanır |
| Gövde sınırı (global `/api/**`) | 1 MB | `Content-Length` varsa gövde okunmadan reddedilir |
| Medya tipi | **Fail-closed** | Limiter'ın çözemediği gövde 415 alır, ölçülmeden geçmez |

**Kritik deployment ön koşulu:** istemci kimliği `X-Forwarded-For`'a dayanır. **Proxy bu
başlığı EZMEK zorundadır** (`proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for`).
Ezmiyorsa çağıran kendi bucket'ını seçer ve rate limit anlamsızlaşır. Kodla garanti edilemez.

*Kanıt:* `RateLimitIT`, `RateLimitBypassIT`, `RateLimitMediaTypeFailClosedIT`,
`RequestBodyLimitIT`, `RequestBodyLimitLayeringIT`.

---

## 5. Tarayıcı ve transport

- **CORS:** allowlist config'ten; prod'da **default yok** (eksikse startup patlar), boş liste
  fail-closed, `*` reddedilir, `allowCredentials=false`. *Kanıt:* `CorsPolicyIT` (4).
- **Başlıklar:** HSTS, CSP, Referrer-Policy, Permissions-Policy, `X-Frame-Options: DENY`.
  HSTS yalnızca proxy `X-Forwarded-Proto: https` gönderdiğinde yazılır — düz HTTP'de
  görünmemesi **doğru** davranıştır. *Kanıt:* `SecurityHeadersIT`.
- **OpenAPI:** varsayılan **kapalı**, yalnız `dev`/`test` profillerinde açık. Kapı bir zamanlar
  "prod değilse aç" idi ve **profilsiz boot'ta açık kalıyordu** — yön tersine çevrildi.
  *Kanıt:* `ApiDocsExposureIT`, `ProdApiDocsExposureIT`, `DefaultProfileApiDocsExposureIT`.

---

## 6. Operasyonel uçlar

`/actuator/health/**` **anonim** (probe'lar kimlik taşımaz). Geri kalan her actuator ucu
`settings.host.manage` ister — host-only.

Bu, kimlik doğrulamanın yeterli sanıldığı bir açıktan geldi: anonim 401 alıyordu (herkesin
kontrol ettiği durum), ama **sıfır izinli bir kiracı kullanıcısı 200** alıyordu — heap/JVM
durumu, tüm route isimleri, istek sayaçları ve `spring.security.filterchains.*` yani **hangi
korumaların devrede olduğu**.

*Kanıt:* `ActuatorExposureIT` (5): anonim 401 · sıfır izinli kiracı 403 · **kiracı admini de
403** (sınır host/kiracı, yetkili/yetkisiz değil) · host admin 200 · probe'lar anonim 200.

Perimeter'de ayrıca kapatın: `RELEASE-RUNBOOK` §1.3-J.

---

## 7. Secret yönetimi

`JWT_SECRET` ve `CORS_ALLOWED_ORIGINS` prod'da **default'suzdur** — eksikse uygulama açılmaz.
Bu **fail-closed** davranış istenen davranıştır.

`JwtSecretValidator` repoda commit'li **her** anahtarı `prod` profilinde reddeder. Şablonun
dev/test anahtarları herkese açıktır ve açık olmaları sorun değildir; koruma budur.

> ⚠️ Çözülmemiş bir `${VAR}` placeholder'ı Spring'de **hata vermez, literal string olarak
> bağlanır**. Yukarıdaki ikisi yakalanır çünkü doğrulayıcıları var. **Kendi eklediğiniz
> default'suz her property'nin de bir doğrulayıcısı olmalıdır**, yoksa `"${MY_VAR}"` değerini
> sessizce kabul eder.

CI'da iki katman: bloklayıcı desen taraması (AWS/GitHub PAT/Slack/private key) ve **bloklayıcı**
gitleaks tam-geçmiş taraması. gitleaks bir bulgu verirse **rotasyon şarttır** — dosyadan
silmek yetmez, değer git geçmişinde kalır.

---

## 8. Log bütünlüğü

Kimlik doğrulamış ama **yetkisiz** bir çağıran `ERROR` satırı üretememelidir. Üretebiliyorsa
gerçek bir arızayı gürültüye gömebilir; prod'un JSON loglamasında bu, log bütçesini istek
hızında harcamak demektir.

Ölçülmüştü: geçersiz bir `sort` parametresi istek başına **~29 KB log ve 233 stack frame**
üretiyordu ve bunu **herhangi bir token** tetikleyebiliyordu.

`ClientErrorLogBudgetIT` bunu **özellik olarak** ifade eder — istisna adları listesi olarak
değil — böylece gelecekteki bir regresyon adı ne olursa olsun burada düşer. İki kontrol dengeyi
korur: gerçek bir beklenmedik istisna **hâlâ** bir `ERROR` + stack trace üretmelidir, ve
reddedilen girdi çağırana **echo edilmez**.

---

## 9. Yeni kod yazarken

`ADDING-A-MODULE.md` tam yordamı verir. Güvenlik açısından atlanmaması gerekenler:

- [ ] `@PreAuthorize` **ve** sabit kullanımı (ham string değil)
- [ ] **Negatif yetki testi**: yetkisiz çağıran 403
- [ ] Çok kiracılıysa `@Filter` **ve** kiracılar-arası **negatif** test
- [ ] Host-only ise: kiracı admininin **de** reddedildiği test
- [ ] Yeni uç geçersiz girdide 500 + stack trace üretmiyor
- [ ] Yeni bir dış bağımlılık eklendiyse: `/actuator/health` aggregate'ini DOWN yapabilir mi
      ve **etmeli mi**? Uygulama onsuz servis edebiliyorsa readiness grubuna **girmemeli**

## 10. Kodun garanti EDEMEDİKLERİ

Bunlar deployment ön koşuludur; hiçbir test bunları kanıtlayamaz:

- Proxy `X-Forwarded-*` başlıklarını **ezmeli** (rate limit ve HSTS aynı güven sınırında)
- `client_max_body_size` proxy'de ayarlı olmalı (§1.3-I)
- `/actuator/**` perimeter'de kapatılmalı, `/health` hariç (§1.3-J)
- `CORS_ALLOWED_ORIGINS` gerçek origin'lerle doldurulmalı
- Çok-instance kurulumda rate limit paylaşımlı değildir (PROD-R6)
- **Branch protection bu planda kurulamıyor** → kırmızı CI merge'i engellemez (PROD-R23)
