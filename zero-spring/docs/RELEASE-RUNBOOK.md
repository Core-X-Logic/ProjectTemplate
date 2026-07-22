# Release Runbook — zero-spring

Kapsam: Spring Boot 3.5 backend (`backend/`) + React/Vite admin (`frontend/app/`), çok kiracılı SaaS.
Hedef okuyucu: release owner + on-call. Bu doküman **çalıştırılabilir** olacak şekilde yazıldı —
her adımın komutu ve beklenen çıktısı var.

> **Ortam gerçekliği (Varsayım):** proje bugün tek-instance dev kurulumunda çalışıyor. Kubernetes yok.
> Aşağıdaki "rolling deploy" bölümü iki-instance + reverse proxy varsayımıdır; tek-instance
> kurulumda kısa kesinti (recreate) kabul edilir ve bu açıkça işaretlendi.

> **Bakım notu:** §1.3 bu şablonun **güvenlik varsayılanlarını** anlatır — hangi ayarın neden bu
> değerde olduğunu ve nasıl doğrulanacağını. Kod bu varsayılanları taşıyor ve testlerle bağlı;
> bir varsayılanı değiştirdiğinizde ilgili maddeyi ve testini birlikte güncelleyin.

---

## 1. Ön koşullar / config checklist

### 1.1 Zorunlu ortam değişkenleri (prod)

| Değişken | Zorunlu | Varsayılan davranış | Nasıl doğrulanır |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE=prod` | **EVET** (imaj varsayılanı zaten `prod`) | Profilsiz boot base config'i kullanır: JWT secret default'u **yok** → uygulama açılmaz (fail-closed), seeding kapalı, cache `simple`, CORS boş | Log ilk satırı: `The following 1 profile is active: "prod"`. Değilse **deploy'u durdur** — `simple` cache ve eksik prod ayarlarıyla koşuyorsun. |
| `DB_URL` | EVET | `jdbc:postgresql://localhost:5432/zero` (`application.yml`) | Başlangıç logunda `HikariPool-1 - Start completed`; `/actuator/health` içinde `"db":{"status":"UP"}` |
| `DB_USER` | EVET | `zero` (`application.yml`) | Aynı health çıktısı; yanlışsa startup'ta `FATAL: password authentication failed` |
| `DB_PASSWORD` | EVET | `zero` (`application.yml`) | Aynı. **Prod'da `zero` görürsen deploy'u durdur.** |
| `JWT_SECRET` | EVET | `application-prod.yml` default **yok** → prod'da eksikse startup patlar (istenen davranış) | Base64 çözümü ≥64 bayt olmalı; kod zorluyor (`JwtService`). Yerel doğrulama: `echo -n "$JWT_SECRET" \| base64 -d \| wc -c` → **≥64** |
| `REDIS_HOST` | EVET | `localhost` (`application.yml`) | `/actuator/health` içinde `"redis":{"status":"UP"}` |
| `REDIS_PORT` | EVET (6379 değilse) | `6379` (`${REDIS_PORT:6379}`) — yönetilen Redis çoğu zaman 6379'da değildir (Azure Cache TLS için 6380) | `/actuator/health` içinde `"redis":{"status":"UP"}` |
| `MAIL_HOST` | EVET (e-posta gerekiyorsa) | **boş** → `LoggingEmailSender`, e-posta sessizce gönderilmez (`application.yml`) | Post-deploy forgot-password smoke (§3.6). Log'da `LoggingEmailSender` görürsen prod'da e-posta YOK |
| `MAIL_PORT` | EVET | `1025` (mailpit) | Aynı smoke |
| `MAIL_USERNAME`/`MAIL_PASSWORD` | EVET (auth isteyen relay ise) | boş; gerçek relay (SES/SendGrid/Postmark) için ayrıca `MAIL_SMTP_AUTH=true` ve genelde `MAIL_SMTP_STARTTLS=true` gerekir | §3.6 forgot-password smoke; log'da `LoggingEmailSender` görünmemeli |
| `SEED_ENABLED` | `false` (önerilen) | prod'da zaten `false` (`application-prod.yml`) | Deploy sonrası log'da seeding satırı olmamalı |
| `SEED_ADMIN_PASSWORD` | `SEED_ENABLED=true` ise **zorunlu ve güçlü** | boş; `DataSeeder` her profilde boş/dev-default parolada fail-fast | İlk kurulumda bilinçli `true` yap, ilk login sonrası `SEED_ENABLED=false` ile yeniden deploy et |
| `CORS_ALLOWED_ORIGINS` | **EVET — default YOK** | `application-prod.yml` default vermiyor → boşsa startup patlar; boş liste fail-closed, `*` reddedilir | Preflight: listedeki origin `200` + `Access-Control-Allow-Origin`; liste dışı origin `403`. Kanıt: `CorsPolicyIT` (4 test) |
| `VITE_API_BASE_URL` (frontend build-time) | EVET | `frontend/app/.env.example:1` → `http://localhost:8080` | `dist/assets/*.js` içinde prod API host'u geçmeli: `grep -o 'https://api[^"]*' dist/assets/*.js` |

**Vite değişkenleri build-time'dır** — imaj/dist üretilirken enjekte edilmeli, çalışma anında değiştirilemez.

Yukarıdaki tablo **prod host / secret store** tarafıdır (uygulamanın çalışma-anı ortamı). Bunları
repoya YAZMA — orchestrator/secret manager'dan gelir.

### 1.1b CI pipeline tetikleyicileri — Actions **Variables/Secrets** (repoya YAZILMAZ)

`docker-build` push ve `release` deploy **kapılıdır**; varsayılan güvenli no-op. Açmak için (tam
tablolar + sıra: SETUP §6):

| Konum | Anahtar | Rol |
|---|---|---|
| Variables | `IMAGE_REGISTRY` | registry prefix `ghcr.io/<org>` |
| Variables | `IMAGE_NAME` | imaj adı (varsayılan `zero-backend`) |
| Variables | `PUSH_IMAGE=true` | docker-build push'u açar (yoksa **no-op**) |
| Variables | `IMAGE_EXTRA_TAG` | (ops.) sha yanında `rc`/`prod`/`latest` |
| Variables | `DEPLOY_ENVIRONMENT` | `dev`\|`stage`\|`prod` |
| Variables | `DEPLOY_ENABLED=true` | release gerçek deploy'u açar (yoksa **dry-run**) |
| Variables | `REGISTRY_USERNAME` | (ops.) login user; boşsa `github.actor` |
| Secrets | `REGISTRY_TOKEN` | registry push token; GHCR'da boşsa `GITHUB_TOKEN`'a düşer |
| Secrets | `DEPLOY_COMMAND` | cloud-agnostic deploy komutu; `IMAGE_REF` env hazır verilir |

**Fail-fast:** `DEPLOY_ENABLED=true` ama `IMAGE_REGISTRY`/`DEPLOY_COMMAND` eksikse release job
anlaşılır hatayla **durur** — sessiz yanlış deploy yok.

### 1.2 Deploy öncesi kapı (gate) — hepsi yeşil olmadan devam etme

```bash
# 1) Profil ve secret'lar set mi (prod host üzerinde)
for v in SPRING_PROFILES_ACTIVE DB_URL DB_USER DB_PASSWORD JWT_SECRET REDIS_HOST; do
  [ -n "${!v}" ] && echo "OK   $v" || echo "FAIL $v (bos)"
done

# 2) JWT secret gercekten >=64 bayt mi
echo -n "$JWT_SECRET" | base64 -d | wc -c    # beklenen: >= 64

# 3) Dev default sizmis mi (bu komut HICBIR SEY dondurmemeli)
echo "$JWT_SECRET" | grep -c "ZGV2LW9ubHktc2VjcmV0"   # beklenen: 0
echo "$DB_PASSWORD" | grep -cx "zero"                 # beklenen: 0

# 4) CI yesil mi (main)
gh run list --branch main --limit 1
```

### 1.3 Güvenlik varsayılanları ve perimeter işleri

Bu bölüm iki farklı şeyi ayırır ve karıştırılmamalıdır:

- **(a) Kodun zaten garanti ettiği varsayılanlar** — deploy'da *doğrulanır*, kurulmaz. Her birinin
  arkasında bir test var. Bir tanesi kırmızıysa bu bir **regresyondur**, deploy'u durdurun.
- **(b) Yalnızca perimeter'de yapılabilecek işler** — uygulama bunları kendi başına yapamaz;
  her kurulumda **sizin** yapmanız gerekir (§1.3-I, §1.3-J).

#### (a) Doğrulanacak varsayılanlar

| Konu | Garanti | Nerede | Kanıt |
|---|---|---|---|
| **JWT secret** | Base config'te default **yok**; sızmış dev anahtarı her profilde reddedilir → profilsiz boot *sessizce güvensiz* değil, **hiç açılmaz**. Anahtar halkası ise her anahtarı ayrı doğrular (PROD-R16, §1.3-K) | `application.yml` `zero.jwt.secret: ${JWT_SECRET}` | `JwtKeyRing` (≥64 bayt, geçerli base64, tekil/dolu kid, tam bir aktif kid) + `JwtSecretValidator` (profil politikası) |
| **CORS** | Allowlist `zero.cors.allowed-origins`; prod'da default yok (eksikse startup patlar), boş liste fail-closed, `*` reddedilir, `allowCredentials=false` | `application-prod.yml` | `CorsPolicyIT` (4 test), `CorsPropertiesValidationTest` |
| **Seeding** | Base config'te `zero.seed.enabled=false`; boş/dev-default parolada **her profilde** fail-fast | `application.yml`, `DataSeeder` | `SeedHardeningIT` |
| **İzin uzlaştırması** | `reconcile-permissions` seeding kapalıyken de **açık** — yeni sürümde eklenen izinler statik Admin rollerine ulaşır | `application.yml` / `-prod.yml` | `RolePermissionReconciliationIT` |
| **Prod cache** | `spring.cache.type: ${CACHE_TYPE:redis}` — çok-instance'ta stale feature değeri riskini kapatır | `application-prod.yml` | — (§2.4 rolling ön koşulu) |
| **OpenAPI/Swagger** | Prod'da `api-docs.enabled=false`; `SecurityConfig` `permitAll`'ı **yalnız** `dev`/`test` profilinde verir → profilsiz boot'ta da kapalı | `application-prod.yml`, `SecurityConfig` | `ApiDocsExposureIT` (2), `ProdApiDocsExposureIT` (5), `DefaultProfileApiDocsExposureIT` (4) |
| **Actuator yetkisi** | `/actuator/health/**` anonim (probe'lar); **geri kalan her şey** `settings.host.manage` ister — kimlik değil **yetki** kapısı | `SecurityConfig` | `ActuatorExposureIT` (5) |
| **Gövde sınırı (uygulama)** | `zero.request.max-body-bytes` = 1 MB, `/api/**`; aşan istek gövde okunmadan `413` | `application.yml` | `RequestBodyLimitIT`, `RequestBodyLimitLayeringIT` |
| **Anonim uç sınırı** | `zero.ratelimit.max-body-bytes` = 16 KB, beş anonim uçta; global sınırdan bağımsız ve daha sıkı | `application.yml` | `RateLimitMediaTypeFailClosedIT` |
| **İmaj varsayılanı** | `SPRING_PROFILES_ACTIVE=prod`, heap tavanı, readiness `HEALTHCHECK`, `exec` ile PID 1 (SIGTERM alır) | `backend/Dockerfile` | — (§3.2 ile doğrula) |
| **Readiness kapsamı** | `readinessState` + **`db`**; redis/mail bilerek dışarıda | `application.yml` | `HealthProbeContractIT` |

**Neden actuator'da yetki kapısı var:** anonim `401` herkesin kontrol ettiği durumdur, ama sıfır
izinli bir **tenant kullanıcısı** `/actuator/prometheus`'tan heap/JVM durumunu, tüm route isimlerini,
istek sayaçlarını ve *hangi korumaların devrede olduğunu* sayan metrikleri okuyabilirdi. Kapı bu
yüzden `settings.host.manage`'e bağlı. Üç durumu birden doğrulayın: §3.3.

**Bilinçli olarak kapatılmamış tek kalem — sessiz e-posta:** `MAIL_HOST` boşsa uygulama **hata
vermeden** `LoggingEmailSender`'a düşer; şifre sıfırlama sessizce ölür. Prod'da fail-fast tercih
edilirdi. Telafisi operasyoneldir: **§3.6 forgot-password smoke'u her deploy'da zorunludur.**

**`docker-compose.yml` uygulamayı ayağa kaldırmaz:** yalnız `postgres` / `redis` / `mailpit`
(dev bağımlılıkları) içerir. Prod compose'unu ayrı bir dosya olarak tutun.

#### (b) Perimeter işleri

**I — Reverse proxy'de gövde (body) sınırı — asıl kontrol burada**

Uygulama tarafında **savunma derinliği** olarak global bir sınır var:
`zero.request.max-body-bytes` (varsayılan **1 MB**, `RequestSizeLimitFilter`, `/api/**`).
Aşan istek gövde okunmadan `413 PAYLOAD_TOO_LARGE` ile reddedilir, `WARN` yazılır, stack trace
üretilmez. **Ama asıl kontrol reverse proxy'dedir** ve bu kalem onsuz kapanmış sayılmaz.

*Neden proxy asıl katman:* uygulama sınırı ancak istek Tomcat'e ulaştıktan sonra devreye girer —
TLS el sıkışması yapılmış, bir worker thread ayrılmış, `Content-Length` başlığı okunmuştur.
`client_max_body_size` ise baytları **JVM'e hiç ulaşmadan** reddeder; ayrıca uygulama sınırının
yakalayamadığı yolları (`/actuator/**`, statik frontend) da kapsar. Uygulama katmanı, proxy yanlış
yapılandırıldığında / değiştirildiğinde / perimeter içinden atlandığında ayakta kalan ikinci
katmandır — tek başına yeterli değildir.

*nginx:*
```nginx
server {
    # Uygulama sınırıyla AYNI değer (zero.request.max-body-bytes = 1 MB).
    # Proxy'yi gevsetip uygulamayi unutmak 413'u Tomcat'e tasir; tersi sessizce
    # nginx'in 413'unu dondurur ve ProblemDetail govdesi kaybolur (SPA "code" alanini okuyamaz).
    client_max_body_size 1m;

    # 413'u proxy uretse bile SPA'nin hata sozlesmesi bozulmasin diye:
    # buyuk govdeyi buffer'a almadan reddet.
    client_body_buffer_size 16k;

    location /api/ {
        proxy_pass http://backend;
        # Guven siniri: istemcinin yolladigi X-Forwarded-* EZILMELI (uygulama
        # forward-headers-strategy=framework ile bu basliklara guveniyor).
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Host              $host;
    }
}
```

*Değeri değiştirirken:* üç yer birlikte hareket eder ve `application.yml` bunların ikisini tek
placeholder'dan sürer:
| Katman | Ayar | Varsayılan |
|---|---|---|
| Reverse proxy | `client_max_body_size` | **elle** 1m (yukarıdaki blok) |
| Uygulama (global) | `zero.request.max-body-bytes` ← `REQUEST_MAX_BODY_BYTES` | 1048576 |
| Uygulama (multipart) | `spring.servlet.multipart.max-request-size` ← `REQUEST_MAX_BODY_BYTES` | 1048576 |

Anonim uçlardaki **16 KB**'lık sınır (`zero.ratelimit.max-body-bytes`) bundan bağımsızdır ve
daha sıkı olduğu için o beş yolda kazanan odur — global sınırı yükseltmek onu gevşetmez.

*Doğrulama (deploy sonrası, §3'e ek):*
```bash
# 1 MB ustu govde -> 413, ve govde ProblemDetail olmali (nginx'in HTML 413'u DEGIL)
head -c 2000000 /dev/zero | tr '\0' 'A' > /tmp/big.txt
curl -s -o /dev/stderr -w '%{http_code}\n' -X POST "$BASE/api/users" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  --data-binary @/tmp/big.txt
# beklenen: 413 ; uygulama katmani cevap verdiyse govdede "PAYLOAD_TOO_LARGE" gecer
```

**J — `/actuator/**` perimeter'de kapatılmalı; scrape iki yoldan biriyle kurulur**

Uygulama katmanı artık `/actuator/health/**` dışındaki her şey için `settings.host.manage`
istiyor. Bu **yetkilendirme** boşluğunu kapatır ama **erişilebilirliği** kapatmaz: endpoint hâlâ
public port üzerinde duruyor ve 401/403 üretmek için de olsa istek işliyor. Perimeter kuralı
şart:

```nginx
# Probe'lar acik kalir (LB/k8s bunlari kimliksiz cagirir).
location = /actuator/health          { proxy_pass http://backend; }
location ^~ /actuator/health/         { proxy_pass http://backend; }
# Geri kalan actuator yuzeyi disaridan hic gorunmesin.
location ^~ /actuator/               { return 404; }
```

*Scrape nasıl çalışacak — iki desteklenen yol:*

| Yol | Kurulum | Ne zaman tercih edilir |
|---|---|---|
| **1. Özel ağ** (önerilen) | Prometheus'u backend'e **proxy'yi atlayarak** ulaştır (aynı VPC/namespace, `backend:8080/actuator/prometheus`) ve host rolüne sahip bir servis hesabının token'ıyla scrape et | Kubernetes / özel ağ varsa |
| **2. Host servis hesabı** | `settings.host.manage` içeren bir host kullanıcısı aç, sadece scrape için kullan; Prometheus `authorization: {type: Bearer, credentials: <token>}` | Tek makine / proxy dışında yol yoksa |

*Bilinen kısıt (kabul edilmiş):* access token TTL'i 15 dakikadır; access-token revocation artık var
(PROD-R16, §1.3-K) ama access token yine kısa ömürlüdür, yani 2. yol scrape tarafında bir token
tazeleyici gerektirir. Uzun vadeli doğru çözüm `management.server.port`'u ayrı bir porta almak ve o
portu **hiç yayınlamamaktır**; o durumda ana security chain o porta uygulanmaz, koruma tamamen ağ
katmanına geçer — bu yüzden port yayınlanırsa açık bir regresyondur.

**K — JWT anahtar rotasyonu (PROD-R16) — sıfır kesinti prosedürü**

HS512 imza anahtarı bir **anahtar halkasıdır** (`JwtKeyRing`): `zero.jwt.active-kid` imzalar, halkadaki
diğer anahtarlar grace penceresinde yalnız doğrular. Token'ın `kid` header'ı doğru doğrulama anahtarını
seçtirir. **Yalnız `zero.jwt.secret` set'liyse** tek anahtarlı halka `legacy` kid'iyle sentezlenir —
mevcut kurulum hiçbir şey yapmadan çalışmaya devam eder; rotasyona ancak ihtiyaç olunca geçilir.

Anahtarı sızmış/eskimişse ya da periyodik döndürüyorsan, **sırayla** (her adım bir deploy):

1. **Yeni anahtarı ekle** (henüz imzalatma). Her iki anahtar da halkada:
   ```yaml
   zero.jwt:
     active-kid: k-2025          # HÂLÂ eski
     keys:
       - { kid: k-2025, secret: ${JWT_KEY_2025} }
       - { kid: k-2026, secret: ${JWT_KEY_2026} }   # yeni, sadece doğrular
   ```
   Deploy. Artık her instance yeni anahtarı **doğrulayabilir** (henüz kimse onunla imzalamıyor).
2. **`active-kid`'i yeniye çevir.** Yeni token'lar `k-2026` ile imzalanır; hâlâ `k-2025` kid'i taşıyan
   token'lar doğrulanmaya devam eder çünkü eski anahtar halkada.
   ```yaml
   zero.jwt: { active-kid: k-2026, keys: [ {kid: k-2025, ...}, {kid: k-2026, ...} ] }
   ```
   Deploy.
3. **Grace penceresi bekle** (≥ `zero.jwt.access-token-ttl` = 15 dk). Bu süre sonunda eski anahtarla
   imzalı her token süresi dolmuştur.
4. **Eski anahtarı halkadan çıkar.** `k-2025` kid'i taşıyan bir token artık **fail-closed** reddedilir
   (bilinmeyen kid).
   ```yaml
   zero.jwt: { active-kid: k-2026, keys: [ {kid: k-2026, secret: ${JWT_KEY_2026}} ] }
   ```
   Deploy.

Her anahtar base64, çözümü ≥64 bayt; `JwtKeyRing` boot'ta doğrular (kid'ler tekil/boş değil, tam bir
aktif kid halkada, çözülmemiş `${...}` placeholder reddi), `JwtSecretValidator` her anahtarı profil
politikasına vurur (sızmış/dev anahtar `prod`'da reddedilir). **Rolling deploy güvenliği:** rotasyondan
önceki kod `kid`'siz token üretiyordu; decoder `kid` yoksa aktif anahtara düşer, yani deploy sırasındaki
in-flight token'lar doğrulanmaya devam eder.

**Access-token revocation (aynı PROD-R16):** logout sunulan access token'ı da iptal eder; parola değişimi
ve 2FA disable kullanıcının **tüm** açık access token'larını iptal eder. Enforcement Redis'te
(`zero.jwt.revocation`, varsayılan açık). **FAIL-CLOSED:** Redis erişilemezse authenticated istekler 401
döner (iptal edilmiş token'ı onurlandırmaktansa auth'u reddeder) — bu yüzden **Redis HA çalıştırın**. Redis
bilerek readiness grubunda değildir (§3.1); revocation-store blip'i instance'ı rotasyondan çıkarmaz, ama o
blip süresince authenticated trafik reddedilir. Doğrulama: §3.4 login smoke'tan sonra logout → aynı access
token ile `/api/auth/me` **401** dönmeli.

---

## 2. Deployment adımları

### 2.0 Sürüm sabitle

```bash
cd zero-spring
git fetch --tags && git checkout <RELEASE_SHA>
export REL=$(git rev-parse --short HEAD)
echo "release=$REL"
```

### 2.1 Backend imajı build

```bash
cd backend
docker build -t zero-platform:$REL .
docker tag zero-platform:$REL zero-platform:latest
```
Beklenen: `BUILD SUCCESS` + son satır `naming to docker.io/library/zero-platform:<REL>`.

> Not: `Dockerfile` build asamasinda `package -DskipTests` kosuyor — **imaj testleri koşmaz.**
> Kalite kapısı CI'dır (`.github/workflows/ci.yml`); imaj build'i yalnız CI yeşilken yapın.

### 2.2 Frontend build

```bash
cd ../frontend/app
npm ci
VITE_API_BASE_URL=https://api.example.com \
VITE_APP_NAME="Zero Platform" \
VITE_DEFAULT_LOCALE=en \
npm run build
grep -o 'https://api[^"]*' dist/assets/*.js | head -1   # prod host gectigini dogrula
```
`dist/` çıktısını statik host'a / reverse proxy kök dizinine yayınlayın.

### 2.3 DB migration stratejisi

**Mevcut durum:** `spring.flyway.enabled: true` → migration **uygulama başlangıcında** çalışıyor.
Migration'lar `backend/src/main/resources/db/migration/` altındadır; deploy edilecek sürümdeki
listeyi doğrudan oradan okuyun (`ls backend/src/main/resources/db/migration/`) — sürüm notunda
(§7) hangi `V<n>` dosyalarının **yeni** olduğu yazılmalıdır.

**Öneri — tek-instance (bugünkü kurulum): uygulama başında Flyway'de kalın.**
Basit, ek işletme yükü yok. Flyway PostgreSQL advisory lock kullandığı için eşzamanlı iki instance'ta
bile çift koşma olmaz; ancak *uzun* bir migration tüm instance'ları başlangıçta bekletir.

**Öneri — rolling deploy'a geçildiğinde: ayrı migration job'ı.**
Sebep: rolling sırasında eski ve yeni sürüm aynı anda çalışır; migration'ın **geriye dönük uyumlu**
(expand/contract) olması ve deploy'dan *önce* bitmiş olması gerekir.
```bash
# Migration'i uygulama trafiginden ayri kosmak icin (rolling'e gecince):
docker run --rm --env-file /etc/zero/zero.env \
  -e SPRING_PROFILES_ACTIVE=prod \
  zero-platform:$REL \
  java -jar /app/app.jar --spring.main.web-application-type=none --spring.flyway.enabled=true
# ardindan uygulama instance'larini SPRING_FLYWAY_ENABLED=false ile baslat
```
> **Varsayım:** yukarıdaki ayrık-job komutu bu projede henüz koşulmadı; ilk kullanımdan önce
> staging'de doğrulanmalı. Bugünkü tek-instance kurulum için gerekli değildir.

**Kural (her sürüm):** migration'lar yalnız **ileri** yönlüdür. Flyway `undo` yok. Yeni migration
mutlaka additive olmalı (kolon ekle, `not null` ikinci adımda; kolon/tablo **silme** aynı sürümde
kodla birlikte gitmemeli).

### 2.4 Deploy

**Tek-instance (bugünkü kurulum — kısa kesinti kabul):**
```bash
docker stop zero-app && docker rm zero-app
docker run -d --name zero-app --restart unless-stopped \
  --env-file /etc/zero/zero.env \
  -e SPRING_PROFILES_ACTIVE=prod \
  -p 8080:8080 zero-platform:$REL
docker logs -f zero-app     # "profile is active: prod" satirini gor, sonra Ctrl-C
```
Beklenen kesinti: ~20-40 sn (Flyway + Spring başlangıcı).

**Rolling (iki instance + reverse proxy) — Varsayım: bu topoloji henüz kurulu değil**
1. `app-b`'yi yeni imajla yeni portta başlat, LB'ye **ekleme.**
2. Readiness bekle (§2.5), smoke koş (§3).
3. LB'de `app-b`'yi ekle, `app-a`'yı drain et (mevcut istekleri bitir).
4. `app-a`'yı yeni imajla değiştir, aynı döngü.
Ön koşul: prod cache'i Redis olmalı (§1.3-a), yoksa iki instance farklı feature değeri görür.

### 2.5 Readiness bekleme

```bash
for i in $(seq 1 60); do
  s=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/actuator/health/readiness)
  [ "$s" = "200" ] && { echo "READY after ${i}s"; break; }
  echo "waiting... ($s)"; sleep 1
done
```
Beklenen: 60 sn içinde `READY`. Gelmiyorsa → §5.

---

## 3. Post-deploy doğrulama

Her adım **beklenen sonucu** ile birlikte; biri kırmızıysa §4 rollback kararına git.

### 3.1 Liveness / readiness

**Üç ucun rolü ayrıdır — LB/probe'u yanlış olana bağlamak kesinti üretir.**

| Uç | Ne sorar | İçerik | Nereye bağlanır |
|---|---|---|---|
| `/actuator/health` | Tam tablo | **tüm** indicator'lar (db, redis, disk…) | İzleme/alarm. **LB'ye BAĞLAMAYIN** — tali bir bağımlılık düştüğünde 503 döner ve çalışan uygulamayı rotasyondan çıkarır |
| `/actuator/health/readiness` | İstek servis edebilir miyim | `readinessState` + **`db`** | **k8s readinessProbe, LB health check, Dockerfile HEALTHCHECK** |
| `/actuator/health/liveness` | Süreç öldürülmeli mi | `livenessState` | k8s livenessProbe. `db` **bilerek yok**: DB kesintisi restart sebebi değildir, crash-loop üretir |

```bash
# Trafik kapisi (LB/probe bunu kullanmali)
curl -s -o /dev/null -w 'readiness=%{http_code}\n' localhost:8080/actuator/health/readiness
# Tam tablo (izleme icin; host admin token'i ile detay)
curl -s -H "Authorization: Bearer $HOST_TOKEN" localhost:8080/actuator/health | jq .
```
Beklenen: `readiness=200`; tam tabloda `{"status":"UP","components":{"db":{"status":"UP"},"redis":{"status":"UP"},…}}`.
`redis` DOWN ise → §5.2 (uygulama servis etmeye **devam eder**: `CacheErrorHandler` cache'i bypass edip WARN yazar).
`db` DOWN ise → §5.1 (readiness de DOWN olur, pod rotasyondan çıkar — istenen davranış).

**`mail` bu listede yok, kasıtlı:** health indicator'ı kapalı (`management.health.mail.enabled: false`).
Açık olsaydı her probe SMTP'ye bağlantı açardı — 10 sn'lik bir probe relay'e günde ~8.600 bağlantı
demektir ve sağlayıcılar bunu throttle eder/engeller. SMTP erişilebilirliği **§3.6 forgot-password
smoke'u** ile doğrulanır.

### 3.2 Profil doğrulaması (dev secret sızıntısı kontrolü)
```bash
docker logs zero-app 2>&1 | grep -m1 "profile is active"
```
Beklenen: `The following 1 profile is active: "prod"`.

**`prod` değilse: derhal durdur.** Gerekçe *dev secret sızıntısı değildir* — base config'te JWT
secret'ın default'u yok, o yüzden yanlış profille açılan bir uygulama zaten *sessizce güvensiz*
olamaz. Asıl risk sessiz **yapılandırma** kaymasıdır: `dev`/`test` profili Swagger'ı public açar
ve seeding'i geri açar; profilsiz boot ise cache'i `simple`'a düşürür (çok-instance'ta stale
feature değeri) ve prod'a özgü havuz boyutlarını uygulamaz.

### 3.3 Prometheus erişimi
```bash
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/actuator/prometheus
```
Beklenen: **`401`** (kimliksiz). Kurulum §1.3-J'de.

**Üç durumu birden doğrula — ikisi geçip biri kalırsa açık kapanmamıştır:**
```bash
# 1) Kimliksiz -> 401
curl -s -o /dev/null -w 'anon=%{http_code}\n' "$BASE/actuator/prometheus"

# 2) Siradan bir TENANT kullanicisi -> 403   <-- asil kontrol. Bu 200 donerse acik hala orada.
curl -s -o /dev/null -w 'tenant=%{http_code}\n' \
  -H "Authorization: Bearer $TENANT_TOKEN" "$BASE/actuator/prometheus"

# 3) Host admin (settings.host.manage) -> 200
curl -s -H "Authorization: Bearer $HOST_TOKEN" "$BASE/actuator/prometheus" | head -3
# beklenen: "# HELP jvm_..." satirlari

# 4) Probe'lar kimliksiz calismaya devam etmeli (yoksa pod hic ready olmaz)
curl -s -o /dev/null -w 'readiness=%{http_code}\n' "$BASE/actuator/health/readiness"
```
Beklenen: `anon=401`, `tenant=403`, host `200`, `readiness=200`. Kanıt: `ActuatorExposureIT` (5 test).

### 3.4 Login smoke (host admin)
```bash
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"usernameOrEmail":"admin","password":"'"$ADMIN_PASSWORD"'"}' | jq -r .accessToken)
[ ${#TOKEN} -gt 100 ] && echo "LOGIN OK" || echo "LOGIN FAIL"

curl -s localhost:8080/api/users/me -H "Authorization: Bearer $TOKEN" | jq '.permissions | length'
```
Beklenen: `LOGIN OK`.

**İzin sayısı kontrolü — sabit bir sayı beklemeyin, kodla karşılaştırın.** Host admin, tanımlı
**tüm** izinleri (host-only olanlar dahil) taşımalıdır; doğru sayı sürümden sürüme değişir, o yüzden
beklenen değeri kaynaktan üretin:

Otorite `AppPermissions.all()`'dur — seeder host admin rolünü **tam olarak** o kümeye eşitler:

```bash
# Beklenen = AppPermissions.all() icindeki sabit sayisi
EXPECTED=$(sed -n '/Set<String> all()/,/);/p' \
  backend/src/main/java/com/mycompanyname/zero/identity/domain/AppPermissions.java \
  | grep -oE '\b[A-Z][A-Z0-9_]+\b' | sort -u | wc -l)
ACTUAL=$(curl -s localhost:8080/api/users/me -H "Authorization: Bearer $TOKEN" | jq '.permissions | length')
echo "expected=$EXPECTED actual=$ACTUAL"
```

Eşit değilse — özellikle `actual < expected` ise — **izin uzlaştırması çalışmamıştır**: yeni sürümde
eklenen izinler mevcut statik `Admin` rollerine ulaşmamış demektir (`zero.seed.reconcile-permissions`
prod'da açık olmalı; seeding kapalıyken de çalışır). Bloker; §4'e git.

Ayrıca **tenant** admin'in host-only izinleri (`settings.host.manage`, `tenants.manage`,
`languages.manage`, SaaS grubunun tamamı) **görmemesi** gerekir — tersi bir yetki sızıntısıdır.

### 3.5 Tenant izolasyon negatifi (GÜVENLİK — atlanamaz)
```bash
# Tenant kullanicisiyla login ol, sonra host-only bir uca vur:
T_TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -H 'X-Tenant: <TENANT_NAME>' \
  -d '{"usernameOrEmail":"<tenant_admin>","password":"<pwd>"}' | jq -r .accessToken)

curl -s -o /dev/null -w 'editions(host-only): %{http_code}\n' \
  -H "Authorization: Bearer $T_TOKEN" localhost:8080/api/editions

# Baska tenant'in verisine X-Tenant header'i ile ulasmayi dene (JWT claim otoriter olmali):
curl -s -o /dev/null -w 'cross-tenant: %{http_code}\n' \
  -H "Authorization: Bearer $T_TOKEN" -H 'X-Tenant: <OTHER_TENANT>' \
  localhost:8080/api/users
```
Beklenen: `editions(host-only): 403` ve cross-tenant isteğin **başka tenant verisi dönmemesi**
(200 dönerse bile içerik kendi tenant'ı olmalı — `AuthenticatedTenantFilter` JWT claim'i otoriter kılar).
`200` + yabancı veri → **KRİTİK, derhal rollback.**

### 3.6 SaaS kritik akış
```bash
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/editions | jq 'length'
curl -s -H "Authorization: Bearer $T_TOKEN" localhost:8080/api/users/me | jq '.subscription'
```
Beklenen: edition listesi dolu; tenant `/me` **yalnızca kendi aboneliğini** döner
(status + currentPeriodEndAt). Feature limiti doğrulaması: plan limitini aşan bir kullanıcı
oluşturma denemesi **403/400** dönmeli (kanıt: `FeatureEnforcementIT`, `MaxUserCountIT`,
`SubscriptionGuardIT`).

**E-posta gönderimi (atlanmayın — sessiz arıza sınıfı):** bir forgot-password isteği tetikleyin ve
log'da `LoggingEmailSender` **görünmediğini** doğrulayın. Görünüyorsa `MAIL_HOST` boştur ve prod'da
şifre sıfırlama/e-posta onayı **hiç çalışmıyordur** (uygulama hata vermez).
```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/api/account/forgot-password \
  -H 'Content-Type: application/json' -d '{"email":"<gercek_kullanici_epostasi>"}'
docker logs --since 2m zero-app 2>&1 | grep -c "LoggingEmailSender"   # beklenen: 0
```

### 3.7 Frontend smoke
Tarayıcıda prod URL → login → kullanıcı listesi. DevTools Network'te **CORS hatası olmamalı**
(allowlist §1.1 `CORS_ALLOWED_ORIGINS`). Console'da 401 döngüsü olmamalı (refresh singleflight
çalışıyor olmalı).

### 3.8 Lifecycle job
```bash
# R-01b: konteyner ADI değil, compose SERVİSİ. `docker-compose.yml`'den sabit
# `container_name` kaldırıldı (bu şablondan türetilmiş iki proje aynı makinede
# çalışabilsin diye), dolayısıyla `zero-postgres` diye bir ad ARTIK YOK.
# `docker compose exec` compose projesinin dizininden koşulmalı — yoksa `-f` ver:
#   cd zero-spring/backend   ·   ya da   docker compose -f zero-spring/backend/docker-compose.yml ...
docker compose exec postgres psql -U zero -d zero -c "select * from shedlock;"
```
Beklenen: job ilk tetiklendikten sonra bir satır; `lock_until` geçmişte → job serbest.
`locked_at` çok eski + `lock_until` gelecekte takılıysa → §5.5.

### 3.9 Billing mutabakatı (Stripe / PayTR / iyzico'dan biri aktifse — atlanmayın: İZSİZ kayıp sınıfı)

Webhook, anonim gövdeli uç olduğu için ortak throttle'ın altında (PROD-R36; PayTR ve iyzico
yolları da aynı listede). İki arıza modu var ve yalnızca biri kendini iyileştirir:

- **429 (kapasite):** geçici. Stripe retry takvimiyle yeniden dener, kova dolunca başarır.
- **413 (16 KB üstü gövde):** **deterministik ve kalıcı.** Her retry aynı 413'ü alır, takvim
  tükenir; `RateLimitFilter` handler'dan ÖNCE reddettiği için `webhook_events`'e satır bile
  düşmez. Sonuç: para tahsil edilmiş, `payments` satırı `NOT_PAID`'de takılı, **sunucu log/DB
  tarafında hiçbir iz yok.** Tek görünür yer Stripe dashboard'u.

Bu yüzden deploy sonrası ve periyodik (önerilen: günlük) mutabakat zorunlu:

```bash
# 1 saatten eski, hâlâ NOT_PAID ya da FAILED bekleyen ödemeler — her biri açıklanmak zorunda.
# FAILED de taranır: PayTR'da failed → success MEŞRU bir sıralamadır (alıcı iframe içinde kartı
# yeniden dener); success bildirimi kaçarsa para tahsil edilmiş, satır FAILED'de takılı kalır —
# yalnız NOT_PAID tarayan sorgu tam bu şekli görmüyordu.
docker compose exec postgres psql -U zero -d zero -c \
  "select id, tenant_id, external_session_id, amount, currency, status, created_at \
     from payments where status in ('NOT_PAID','FAILED') \
     and created_at < now() - interval '1 hour' order by created_at;"
```

Stripe Dashboard → **Developers → Webhooks → (endpoint) → Failed** listesiyle karşılaştırın:

- Listede başarısız teslimat var + eşleşen `NOT_PAID` satırı var → **Resend** deneyin. Dedup +
  tek-transaction rollback tasarımı sayesinde yeniden gönderim güvenlidir (mükerrer işlenmez,
  başarısız denemeler iz bırakmadan geri alınır — `BillingWebhookIT`).
- Başarısızlık nedeni **413** ise Resend de 413 alacaktır: PROD-R36 kapanana (webhook path'ine
  özel gövde sınırı) kadar çözüm manueldir — ödemeyi Stripe kaydına göre elle mutabık kılın ve
  aboneliği `PUT /api/subscriptions/{tenantId}/edition` + `activate` ile işleyin; işlemi sürüm
  notuna yazın.
- `NOT_PAID` satırı var ama Stripe'ta tamamlanmış session yok → terk edilmiş checkout; normaldir,
  aksiyon gerekmez.

**PayTR aktifse (P2'-A) — aynı mutabakat, PayTR mağaza paneliyle.** İki fark, ikisi de mutabakatı
DAHA kritik yapar:

- **Retry takvimi belgesiz (PROD-R41).** Stripe'ın aksine PayTR, başarısız bildirimin kaç kez
  yeniden deneneceğini yayınlamıyor — takvimin ne zaman tükendiği görülemez, tek emniyet bu
  karşılaştırmadır.
- **Başarı yanıtı gövdeye bağlı (PROD-R42).** Bildirim yalnız literal `OK` gövdesiyle kapanır;
  esnafa aktarım buna bağlıdır. Panelde "başarısız bildirim" görünüyorsa ama uygulama 200 dönmüşse,
  gövdenin `OK` dışına kaydığından şüphelenin (advice/handler regresyonu — `PayTRWebhookIT`).

Sorgu aynıdır (yukarıdaki `NOT_PAID` sorgusu PayTR ödemelerini de kapsar; `external_session_id`
PayTR'da `merchant_oid`'dir, `ZP...` biçimli). PayTR mağaza paneli → İşlemler listesiyle
karşılaştırın:

- Panelde başarılı işlem + `NOT_PAID` **ya da `FAILED`** satırı → bildirimi panelden yeniden
  gönderin (dedup + rollback tasarımı yeniden gönderimi güvenli kılar — `PayTRWebhookIT` duplicate
  testi; `FAILED` satır için de güvenlidir: failed → success sunucu tarafında `FAILED -> PAID` +
  aktivasyonla işlenir, `PayTRWebhookIT.failedThenSuccessActivates`). Yeniden gönderim de
  kapanmıyorsa ödemeyi elle mutabık kılın ve aboneliği
  `PUT /api/subscriptions/{tenantId}/edition` + `activate` ile işleyin; işlemi sürüm notuna yazın.
- Panelde SON durumu da başarısız olan işlem + `payments.status = FAILED` → gerçek başarısız
  tahsilat; aksiyon gerekmez. DİKKAT: `FAILED` tek başına "kapandı" demek DEĞİLDİR — alıcı iframe
  oturumu içinde kartı yeniden deneyebilir (failed → success meşru sıralamadır) ve success
  bildirimi kaçtıysa satır `FAILED`'de takılıyken para tahsil edilmiştir. Karar her zaman panelin
  İŞLEM durumuyla verilir, satırın durumuyla değil; sorgu bu yüzden `FAILED`'i de tarar.
- `NOT_PAID` satırı var, panelde işlem yok → terk edilmiş checkout; normaldir.

**iyzico aktifse (P2'-B) — bu bölümün iyzico satırları OTOMATİK mutabık kılınır.**
`BillingReconciliationJob` (ShedLock `billing-reconciliation`, varsayılan saatlik,
`zero.billing.reconciliation.*`) yukarıdaki sorgunun otomasyonudur: `NOT_PAID`/`FAILED`'de
`min-age`'den (varsayılan 1 saat) eski ve `payments.provider = 'iyzico'` olan her satır için
iyzico'nun KENDİ retrieve API'si sorgulanır; `paymentStatus=SUCCESS` + `fraudStatus=1` ise ödeme
`PAID` + abonelik aktive edilir (`subscription_events.actor = iyzico-reconciliation`). Elle
bakılacaklar:

- **Job'un özet satırını okuyun** (her pass'te INFO): `"Billing reconciliation pass: N
  candidate(s) ... K skipped without a query-capable provider"`. `skipped > 0` ise o satırlar
  PayTR/Stripe/atfedilmemiş (`provider` null, V9 öncesi) demektir — **onlar için bu bölümün elle
  adımları aynen geçerlidir**; iyzico satırları için değildir.
- Pass'ler arasında `NOT_PAID`'de KALAN iyzico satırı = retrieve "tahsil edilmedi" diyor
  (terk edilmiş checkout ya da `fraudStatus=0` inceleme — WARN satırında ayrıntı var). İnceleme
  sonuçlanınca sonraki pass kendiliğinden kapatır; panelle çelişiyorsa PROD-R47'ye bakın.
- `CANCELLED` satıra para geldiğini söyleyen WARN (`money is settled for a written-off payment`)
  → job BİLEREK dokunmaz; elle mutabık kılın (operatör kararı sağlayıcı trafiğine ezdirilmez).
- Webhook'lar için not: iyzico teslimatı yalnız HTTP 200 ile kapanır ve retry bütçesi ~3'tür
  (10 dk arayla) — bütçe tükense bile bu job aynı retrieve'i sorduğu için iyzico'da "izsiz kayıp"
  sınıfı (413 senaryosu dâhil) job'a düşer, panele değil.

### 3.10 Sandbox smoke — PROD-R44 / PROD-R47 kapanış prosedürü (operatör koşar)

Kod tarafındaki her şey offline kanıtlı; **gerçek sağlayıcı çağrısı hiç koşmadı**. Kapanış,
yalnız operatörün açabileceği hesaplara bağlıdır:

| Sağlayıcı | Hesap | Not |
|---|---|---|
| PayTR | Gerçek mağaza başvurusu (merchant_id/key/salt) | Ayrı sandbox hostu YOK; canlı mağazada `test_mode=1`. Bildirim URL panelden tünel adresine çevrilir |
| iyzico | `sandbox-merchant.iyzipay.com` self-service kayıt | `sandbox-` önekli anahtarlar; İşyeri Bildirimleri HTTPS tünel adresine |

Prosedür: `zero-spring/scripts/sandbox-smoke.sh` — env ile kimlik bilgileri + `cloudflared`
tüneli, sonra `bash zero-spring/scripts/sandbox-smoke.sh paytr` ve `... iyzico`. Script API
adımlarını otomatik koşar (checkout başlatma, bozuk-imza negatifi, ACTIVE bekleyişi); kart
girme adımı doğası gereği interaktiftir (3DS). PASS sonucu QUALITY-GATES-RESULTS'a işlenir ve
PROD-R44/R47 kapatılır. Duplicate-teslimat negatifi iyzico panelindeki **Resend** ile ölçülür
(200 dönmeli, `subscription_events` sayacı artmamalı).

---

## 4. Rollback

### 4.1 Temel kural
**Uygulama sürümü geri alınabilir. Veritabanı şeması geri alınamaz.** Flyway `undo` bu projede yok
(Community). DB için tek meşru yol **forward-fix**: yeni bir `V<n+1>` migration yazıp ileri gitmek.

### 4.2 Uygulama sürümünü geri alma (dakikalar)
```bash
docker stop zero-app && docker rm zero-app
docker run -d --name zero-app --restart unless-stopped \
  --env-file /etc/zero/zero.env -e SPRING_PROFILES_ACTIVE=prod \
  -p 8080:8080 zero-platform:<ONCEKI_REL>
# frontend: onceki dist/ yayinini geri al
```
Ardından §3.1 + §3.4 + §3.5'i tekrar koş.

### 4.3 Karar ağacı

```
Bu surumde YENI migration var mi?
├── HAYIR
│   └── Guvenli. Onceki imaja don (§4.2). Veri kaybi riski YOK.
│
└── EVET → migration ADDITIVE mi (sadece ekleme: yeni tablo/kolon/index)?
    ├── EVET → Eski surum yeni semayi goruyor mu?
    │   ├── EVET (yeni kolonlar nullable / default'lu, eski kod onlari bilmiyor ama sema onu kirmiyor)
    │   │   └── Guvenli. Imaji geri al, SEMAYI GERI ALMA. Yeni kolonlar
    │   │       kullanilmadan durur; sonraki ileri deploy'da devreye girer.
    │   │       (tipik ornek: yeni bir tablo ekleyen migration — eski surum tabloyu hic gormez)
    │   └── HAYIR (yeni kolon NOT NULL default'suz → eski kodun INSERT'leri patlar)
    │       └── RISKLI. Once kolonu nullable yap (yeni migration), sonra imaji geri al.
    │
    └── HAYIR — destructive (kolon/tablo DROP, RENAME, tip daraltma, backfill+silme)
        └── *** VERI KAYBI RISKI — GERI ALMA YOK ***
            Imaji geri almak veriyi geri getirmez; eski kod olmayan kolonu arar ve patlar.
            Yapilacak: 1) trafigi kes / bakim moduna al
                       2) INCIDENT ac, DB/Infra owner'i cagir
                       3) PITR / snapshot'tan geri yukleme degerlendir (veri kaybi = son
                          snapshot'tan bu yana gecen sure)
                       4) Tercih: FORWARD-FIX — yeni V<n+1> ile duzelt, ileri deploy et
```

### 4.4 Rollback öncesi zorunlu
- [ ] Rollback kararı release owner tarafından **yazılı** verildi (kanal/ticket).
- [ ] Rollback öncesi DB snapshot alındı (`pg_dump` veya sağlayıcı snapshot'ı).
- [ ] Geri dönülen imaj tag'i doğrulandı (`docker images | grep zero-platform`).

---

## 5. Incident quick-response

### 5.1 İlk 15 dakika — kontrol listesi

| Dk | Aksiyon | Komut / yer |
|---|---|---|
| 0-1 | Etki tespiti: tüm tenantlar mı, tek tenant mı? | `curl /actuator/health` + kullanıcı raporu |
| 1-2 | Incident kanalı aç, release owner'ı etiketle | — |
| 2-4 | Son deploy ne zamandı? Bu bir regresyon mu? | `docker ps --format '{{.Image}} {{.RunningFor}}'` |
| 4-6 | Health bileşenleri: db / redis / diskSpace | `curl -s /actuator/health \| jq .components` |
| 6-9 | Log'da ilk hata (deploy sonrası) | `docker logs --since 30m zero-app 2>&1 \| grep -iE "ERROR\|Caused by" \| head -30` |
| 9-12 | Aşağıdaki 5 senaryodan biri mi? → ilgili bölüme git | §5.2-§5.6 |
| 12-15 | Karar: **forward-fix mi rollback mı** (§4.3 ağacı) ve duyuru | — |

**Kural:** 15. dakikada net bir hipotez yoksa → rollback'i varsayılan seç (yeni migration yoksa).

### 5.2 Senaryo 1 — DB bağlantısı
*Belirti:* startup'ta `HikariPool-1 - Exception during pool initialization`, health `db: DOWN`, tüm uçlar 500.
1. `docker compose exec postgres pg_isready -U zero -d zero` → DB ayakta mı? (ad değil servis — §3.8 notu)
2. Ayakta ise kimlik bilgisi: `DB_USER`/`DB_PASSWORD` env'i doğru mu (§1.2 komutu)?
3. `FATAL: too many connections` ise → Hikari pool boyutu × instance sayısı > PG `max_connections`.
   İlk müdahale: fazla instance'ı durdur; kalıcı: `spring.datasource.hikari.maximum-pool-size` ayarla.
4. Ağ/DNS ise: `DB_URL` host'una `nc -zv <host> 5432`.

### 5.3 Senaryo 2 — Redis down
*Belirti:* health `redis: DOWN`. **Etki:** impersonation token store (`ImpersonationTokenStore`) ve
SaaS feature cache Redis'e dayanıyor → impersonation kırılır; feature çözümü cache'siz kalır.
1. `docker compose exec redis redis-cli ping` → `PONG` bekleniyor. (ad değil servis — §3.8 notu)
2. `PONG` geliyor ama app DOWN diyorsa → adres/port uyuşmazlığı: `REDIS_HOST`/`REDIS_PORT` set
   edilmemişse uygulama `localhost:6379`'a gider. Yönetilen Redis çoğu zaman 6379'da değildir.
   İlk müdahale: doğru `REDIS_HOST`/`REDIS_PORT` ile yeniden başlat.
3. Redis gerçekten çökmüşse: yeniden başlat. Cache kaybı zararsız (yeniden ısınır);
   **impersonation oturumları düşer** — beklenen davranış, kullanıcıya bildir.

### 5.4 Senaryo 3 — JWT secret yanlış
*Belirti A:* startup patlıyor → `zero.jwt.secret must decode to at least 64 bytes` /
`must be valid base64` (`JwtService`). → Secret'ı düzelt, yeniden başlat.
*Belirti B:* uygulama ayakta ama **tüm authenticated istekler 401**, login token üretiyor.
Sebep: secret deploy'lar arası değişti → eski token'lar doğrulanamıyor.
1. Bilinçli rotasyon mu? Evet ise: bu **beklenen** — tüm kullanıcılar yeniden login olmalı, duyur.
2. Hayır ise: `JWT_SECRET`'i önceki değere geri al ve yeniden başlat (env yönetimi hatası).
3. Rolling'de instance'lar **farklı** secret'la koşuyorsa: istekler rastgele 401 alır →
   tüm instance'ları aynı secret'a getir. (Tek-instance'ta bu senaryo yok.)

### 5.5 Senaryo 4 — Migration fail
*Belirti:* startup'ta `FlywayException: Migration V<n>__... failed` → uygulama hiç açılmaz.
1. **Panikleme, DB'ye elle dokunma.** Önce durumu oku:
   ```bash
   docker compose exec postgres psql -U zero -d zero \
     -c "select version, description, success, installed_on from flyway_schema_history order by installed_rank desc limit 5;"
   ```
2. `success = false` satırı varsa → migration yarıda kaldı, şema **belirsiz** durumda.
3. PostgreSQL'de DDL transactional'dır → başarısız migration çoğunlukla rollback olmuştur.
   Doğrula: beklenen tablo/kolon var mı?
4. Müdahale: (a) SQL hatasını düzelt, (b) `flyway_schema_history`'den `success=false` satırını
   `flyway repair` ile temizle, (c) yeniden deploy. **`repair`'i DB/Infra owner onayıyla koş.**
5. Uzun sürüyorsa: önceki imaja dön (§4.2) — migration uygulanmadıysa eski sürüm çalışır.

### 5.6 Senaryo 5 — Lifecycle job kilidi (ShedLock)
*Belirti:* abonelik durumları güncellenmiyor (trial bitmiş ama `TRIALING` kalmış), job log'u yok.
1. Kilit durumunu oku:
   ```bash
   docker compose exec postgres psql -U zero -d zero -c "select * from shedlock;"
   ```
2. `lock_until` **gelecekte** ve `locked_by` artık yaşamayan bir instance ise → kilit takılmış
   (job crash etti, lock süresi dolmadı). `zero.saas.lifecycle.lock-at-most-for` varsayılanı
   **PT10M** → normalde 10 dk içinde kendiliğinden serbest kalır.
3. **Önce 10 dk bekle.** Kalıcı takılıysa ve iş kritikse, DB/Infra onayıyla:
   ```sql
   -- SADECE ilgili instance'in olu oldugu dogrulandiktan sonra:
   update shedlock set lock_until = now() where name = '<job_adi>';
   ```
   Canlı bir instance işi koşarken bunu yapmak **çift koşmaya** yol açar (proration/faturalama
   çift işlenebilir) — bu yüzden instance'ın öldüğünü doğrulamadan yapma.
4. Kilit boşta ama job hiç koşmuyorsa: scheduler açık mı, cron ifadesi doğru mu (log'da
   `Scheduled task` kaydı), `@EnableScheduling` aktif mi?

---

## 6. Owner listesi

> **Varsayım:** proje şu an tek geliştiricili görünüyor; aşağıdaki roller **rol tanımıdır**,
> birden fazlası aynı kişiye atanabilir. İlk prod deploy'dan önce doldurulmalı.

| Rol | Sorumluluk | Ana karar yetkisi | Sahip | Yedek | İletişim |
|---|---|---|---|---|---|
| **Release owner** | Deploy'u başlatır, go/no-go verir, rollback kararının sahibi | Rollback / forward-fix | `<DOLDUR>` | `<DOLDUR>` | `<kanal>` |
| **Backend on-call** | Spring uygulaması, migration, lifecycle job, API hataları | Migration repair, job kilidi | `<DOLDUR>` | `<DOLDUR>` | `<kanal>` |
| **Frontend on-call** | Admin SPA, build/dist yayını, CORS & token akışı | Frontend rollback | `<DOLDUR>` | `<DOLDUR>` | `<kanal>` |
| **DB / Infra** | PostgreSQL, Redis, snapshot/PITR, ağ, reverse proxy | `flyway repair`, snapshot geri yükleme, shedlock elle müdahale | `<DOLDUR>` | `<DOLDUR>` | `<kanal>` |
| **Security** | Secret yönetimi/rotasyonu, tenant izolasyon ihlali, veri sızıntısı | İzolasyon ihlalinde **derhal durdurma** yetkisi | `<DOLDUR>` | `<DOLDUR>` | `<kanal>` |

**Eskalasyon:** Release owner → 15 dk çözümsüz → Backend/Frontend on-call → 30 dk → DB/Infra.
**Tenant izolasyon ihlali (§3.5 kırmızı) her zaman anında Security + derhal rollback** —
15 dakikalık pencere beklenmez.

---

## 7. Sürüm notu şablonu

```markdown
# Release <SÜRÜM> — <YYYY-MM-DD>

**Commit:** <SHA>  ·  **İmaj:** zero-platform:<REL>  ·  **Release owner:** <ROL/İSİM>
**Ortam:** prod  ·  **Deploy penceresi:** <başlangıç>–<bitiş> (<TZ>)

## Ne çıktı
- <modül>: <kullanıcıya görünen değişiklik>
- <modül>: <...>

## Migration
- [ ] Bu sürümde yeni migration **YOK** → rollback güvenli (§4.3)
- [ ] Yeni migration **VAR**: `V<n>__<ad>.sql`
      - Tür: [ ] additive (yalnız ekleme)  [ ] destructive (drop/rename/tip daraltma)
      - Eski sürümle uyumlu mu (rolling sırasında iki sürüm birlikte çalışabilir mi)? <evet/hayır>
      - Tahmini süre / kilit etkisi: <...>

## Config değişikliği
- Yeni/değişen env: `<AD>` = `<açıklama>` (secret ise değeri YAZMA)
- Deploy öncesi set edilmesi gereken: <liste>

## Geri alma notu
- Rollback yolu: `zero-platform:<ÖNCEKİ_REL>` (§4.2)
- **Geri alınabilir mi:** [ ] Evet, koşulsuz  [ ] Evet, ama <koşul>  [ ] **HAYIR — veri kaybı riski**
- Gerekçe: <§4.3 ağacındaki hangi dal>
- Rollback sonrası veri etkisi: <yok / şu kayıtlar yeni şemada kalır / ...>

## Doğrulama
- [ ] CI yeşil (backend verify + frontend build/test)
- [ ] §1.2 config kapısı geçti
- [ ] §3.1 health UP  · [ ] §3.2 profil=prod  · [ ] §3.4 login + izin sayısı
- [ ] §3.5 tenant izolasyon negatifi (403)  · [ ] §3.6 SaaS akışı  · [ ] §3.7 frontend

## Bilinen açıklar (bu sürümde kapanmadı)
- <RISK-REGISTER ID> — <kısa açıklama>
```

---

## Ek — her deploy'da doğrulanacak kısa liste

Bu tablo §1.3'ün özetidir; ikisi çeliştiğinde **kod otoritedir** — doğrulama komutunu koşun.

| Konu | Beklenen | Nerede |
|---|---|---|
| Aktif profil | log'da `profile is active: "prod"` | §3.2 |
| CORS allowlist | listedeki origin 200, liste dışı 403 | §1.1, §3.7 |
| Actuator yüzeyi | anon 401 · tenant 403 · host 200 · readiness 200 | §3.3 |
| OpenAPI | prod'da `/v3/api-docs` erişilemez | §1.3-a |
| Tenant izolasyonu | host-only uçta tenant token'ı → 403, çapraz-tenant veri yok | §3.5 |
| İzin uzlaştırması | host admin izin sayısı = kaynaktaki tanım sayısı | §3.4 |
| E-posta | `LoggingEmailSender` log'da **yok** | §3.6 |
| Gövde sınırı | 1 MB üstü gövde → 413 (ProblemDetail) | §1.3-I |
| Perimeter | `/actuator/**` dışarıdan 404, `/actuator/health` açık | §1.3-J |

**Kalıcı, kapatılmamış kalemler (bilinçli tasarım tercihi — her sürümde geçerli):**
- `MAIL_HOST` boşsa e-posta **sessizce** gönderilmez (fail-fast değil) → telafisi §3.6 smoke'udur.
- Access-token revocation artık **var** (PROD-R16, §1.3-K): logout + parola/2FA-disable token'ları
  iptal eder, `jti` denylist Redis'te. Kalıcı takas revocation'ın **fail-closed** olmasıdır — Redis
  erişilemezse authenticated istekler reddedilir, o yüzden Redis HA gerekir (§1.3-K). HS512 simetrik
  kaldı (RS/ES asimetrik göçü ayrı, breaking bir iş).
- `docker-compose.yml` yalnız dev bağımlılıklarını ayağa kaldırır; prod compose'u ayrı tutulur.
