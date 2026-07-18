# Release Runbook — zero-spring

Kapsam: Spring Boot 3.5 backend (`backend/`) + React/Vite admin (`frontend/app/`), çok kiracılı SaaS.
Hedef okuyucu: release owner + on-call. Bu doküman **çalıştırılabilir** olacak şekilde yazıldı —
her adımın komutu ve beklenen çıktısı var.

> **Ortam gerçekliği (Varsayım):** proje bugün tek-instance dev kurulumunda çalışıyor. Kubernetes yok.
> Aşağıdaki "rolling deploy" bölümü iki-instance + reverse proxy varsayımıdır; tek-instance
> kurulumda kısa kesinti (recreate) kabul edilir ve bu açıkça işaretlendi.

> **Denetim notu:** Bu runbook yazılırken backend kaynağı başka bir ajan tarafından değiştiriliyordu
> (F5 Slice B: lifecycle job, feature enforcement, proration, subscription guard). §1'deki
> **BLOKER** işaretli maddeler o sırada koddaki gerçek durumdur (dosya:satır kanıtlı) ve Slice B
> kapanışında yeniden doğrulanmalıdır.

---

## 1. Ön koşullar / config checklist

### 1.1 Zorunlu ortam değişkenleri (prod)

| Değişken | Zorunlu | Varsayılan davranış | Nasıl doğrulanır |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE=prod` | **EVET — en kritik** | Yoksa base profil çalışır: dev JWT secret + `seed.enabled=true` | `curl -s -u … /actuator/info` yerine log ilk satırı: `The following 1 profile is active: "prod"`. Yoksa **deploy'u durdur.** |
| `DB_URL` | EVET | `jdbc:postgresql://localhost:5432/zero` (`application.yml:4`) | Başlangıç logunda `HikariPool-1 - Start completed`; `/actuator/health` içinde `"db":{"status":"UP"}` |
| `DB_USER` | EVET | `zero` (`application.yml:5`) | Aynı health çıktısı; yanlışsa startup'ta `FATAL: password authentication failed` |
| `DB_PASSWORD` | EVET | `zero` (`application.yml:6`) | Aynı. **Prod'da `zero` görürsen deploy'u durdur.** |
| `JWT_SECRET` | EVET | `application-prod.yml:5` default **yok** → prod'da eksikse startup patlar (istenen davranış) | Base64 çözümü ≥64 bayt olmalı; kod zorluyor (`JwtService.java:75-90`). Yerel doğrulama: `echo -n "$JWT_SECRET" \| base64 -d \| wc -c` → **≥64** |
| `REDIS_HOST` | EVET | `localhost` (`application.yml:24`) | `/actuator/health` içinde `"redis":{"status":"UP"}` |
| `REDIS_PORT` | EVET (6379 değilse) | `6379` — **PROD-R18'de düzeltildi**, artık `${REDIS_PORT:6379}` okunuyor | `/actuator/health` içinde `"redis":{"status":"UP"}` |
| `MAIL_HOST` | EVET (e-posta gerekiyorsa) | **boş** → `LoggingEmailSender`, e-posta sessizce gönderilmez (`application.yml:15`) | Post-deploy forgot-password smoke (§3.6). Log'da `LoggingEmailSender` görürsen prod'da e-posta YOK |
| `MAIL_PORT` | EVET | `1025` (mailpit) | Aynı smoke |
| `MAIL_USERNAME`/`MAIL_PASSWORD` | EVET (auth isteyen relay ise) | boş — **PROD-R19'da düzeltildi**; ayrıca `MAIL_SMTP_AUTH=true` ve genelde `MAIL_SMTP_STARTTLS=true` gerekir | §3.6 forgot-password smoke; log'da `LoggingEmailSender` görünmemeli |
| `SEED_ENABLED` | `false` (önerilen) | prod'da zaten `false` (`application-prod.yml:9`) | Deploy sonrası log'da seeding satırı olmamalı |
| `SEED_ADMIN_PASSWORD` | `SEED_ENABLED=true` ise **zorunlu ve güçlü** | boş; `DataSeeder.java:71-79` prod profilinde boş/dev-default parolada fail-fast | İlk kurulumda bilinçli `true` yap, ilk login sonrası `SEED_ENABLED=false` ile yeniden deploy et |
| `CORS_ALLOWED_ORIGINS` | **EVET — default YOK** | **PROD-R3'te kapatıldı.** `application-prod.yml` default vermiyor → boşsa startup patlar; boş liste fail-closed, `*` reddedilir | Preflight: listedeki origin `200` + `Access-Control-Allow-Origin`; liste dışı origin `403`. Kanıt: `CorsPolicyIT` (4) |
| `VITE_API_BASE_URL` (frontend build-time) | EVET | `frontend/app/.env.example:1` → `http://localhost:8080` | `dist/assets/*.js` içinde prod API host'u geçmeli: `grep -o 'https://api[^"]*' dist/assets/*.js` |

**Vite değişkenleri build-time'dır** — imaj/dist üretilirken enjekte edilmeli, çalışma anında değiştirilemez.

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

### 1.3 Deploy öncesi kapatılması gereken config açıkları

> **Durum notu (2026-07-18, hardening kapanış turu).** Bu bölüm ilk denetimde açılan sekiz kalemle
> yazılmıştı; A–H o günün fotoğrafıdır. Kapanan kalemler **silinmedi**, kapandı olarak işaretlendi:
> operatörün bir maddeyi "hâlâ açık" sanıp gereksiz manuel önlem alması, kapanmış bir maddeyi
> görmemesi kadar zararlı. Her satırın karşısında kanıt var; kanıtı olmayan kalem kapalı sayılmadı.

**A — `REDIS_PORT` env'i okunmuyordu** — ✅ **KAPANDI (PROD-R18)**
`application.yml` artık `port: ${REDIS_PORT:6379}`. Daha önce literal `6379` yazılıydı ve yalnızca
dev profili override ediyordu; yani `REDIS_PORT` bu runbook'ta *zorunlu değişken* olarak listelenirken
hiçbir yerde okunmuyordu. Prod cache'i Redis olduğu için (§D) bağlantıya en çok muhtaç olan ortam,
portunu ayarlayamayan ortamdı.

**B — SMTP auth desteklenmiyordu** — ✅ **KAPANDI (PROD-R19)**
`spring.mail.username` / `password` artık env'den okunuyor, `auth` ve `starttls.enable`
`${MAIL_SMTP_AUTH:false}` / `${MAIL_SMTP_STARTTLS:false}` ile ayarlanabiliyor. Varsayılanlar
değişmedi, dolayısıyla dev/mailpit ve GreenMail testleri aynen çalışıyor.
*Kalan risk (kapatılmadı, bilinçli):* `MAIL_HOST` boşsa uygulama **hata vermeden**
`LoggingEmailSender`'a düşer — şifre sıfırlama sessizce ölür. Prod'da bunun fail-fast olması
tercih edilirdi; feature freeze kapsamında değiştirilmedi. **Deploy kontrolü: §3.6 smoke zorunlu.**

**C — CORS yapılandırması yoktu** — ✅ **KAPANDI (PROD-R3)**
`CorsConfigurationSource` bean'i var, allowlist `zero.cors.allowed-origins` üzerinden geliyor,
prod'da **default yok** (eksikse startup patlar), boş liste fail-closed, `*` `CorsProperties`
tarafından reddediliyor, `allowCredentials=false`. Kanıt: `CorsPolicyIT` (4),
`CorsPropertiesValidationTest`. Canlı: listedeki origin 200, `evil.example.com` 403.
*Not:* tek origin (proxy arkasında aynı host) hâlâ en basit kurulum — o durumda liste yalnızca
o host'u içermeli, boş bırakılmamalı.

**D — `spring.cache.type` prod'da `redis` değildi** — ✅ **KAPANDI (PROD-R7)**
`application-prod.yml` → `spring.cache.type: ${CACHE_TYPE:redis}`. Çok-instance'ta stale feature
değeri riski (**F5-R2**) kapandı.

**E — Swagger/OpenAPI prod'da public'ti** — ✅ **KAPANDI (B6 + C5)**
İki kilit: prod'da `springdoc.api-docs.enabled=false`, ve `SecurityConfig` `permitAll`'ı yalnızca
`dev`/`test` profillerinde veriyor. Kapı "prod değilse aç" idi ve **profilsiz boot'ta açık**
kalıyordu; yön tersine çevrildi. Kanıt: `ApiDocsExposureIT`, `ProdApiDocsExposureIT`,
`DefaultProfileApiDocsExposureIT`. Canlı: profilsiz boot'ta `/v3/api-docs` → 401.

**F — `/actuator/prometheus` kimlik doğrulama istiyor** — ⚙️ **DAVRANIŞ DEĞİŞTİ (PROD-R17)**
Artık yalnız kimlik doğrulama değil, **yetki** de istiyor: `settings.host.manage` (host-only).
Bunun sebebi kapanış smoke'unda ölçülen şuydu — anonim 401 alıyordu (herkesin kontrol ettiği
durum), ama **sıfır izinli bir tenant kullanıcısı 200 alıyordu**: heap/JVM durumu, tüm route
isimleri, istek sayaçları ve `spring.security.filterchains.RateLimitFilter.*` gibi *hangi
korumaların devrede olduğunu* sayan metrikler. `/actuator/health/**` bilerek anonim kaldı (probe'lar).
Scrape kurulumu için §1.3-J ve §3.3.

**G — Dockerfile'da profil, healthcheck ve heap sınırı yoktu** — ✅ **KAPANDI (PROD-R20)**
`ENV SPRING_PROFILES_ACTIVE=prod`, `ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"`,
readiness üzerinden `HEALTHCHECK`, ve `exec` ile shell-form entrypoint (JAVA_OPTS genişlesin, JVM
PID 1 kalıp SIGTERM'i alsın diye).
*Düzeltme notu:* bu madde eskiden "profil verilmezse dev JWT secret ile prod'a çıkılır" diyordu.
**Bu artık doğru değil** — PROD-R1'den beri base config'te JWT secret'ın default'u yok ve sızmış dev
anahtarı her profilde reddediliyor, yani profilsiz boot *sessizce güvensiz* değil, **hiç açılmıyor**.
Yine de imajın kendi varsayılanının doğru olması gerekiyordu.

**H — `docker-compose.yml` uygulama servisi içermiyor**
Sadece `postgres` / `redis` / `mailpit` var (dev bağımlılıkları). `docker compose up` **uygulamayı
ayağa kaldırmaz**. Prod compose'u ayrı bir dosya olarak tutun (§2.3).

**I — Reverse proxy'de gövde (body) sınırı ayarlanmamış (F1 — asıl kontrol burada)**

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
        # PROD-R4 / B3 ile ayni guven siniri: istemcinin yolladigi X-Forwarded-* EZILMELI.
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

Anonim uçlardaki **16 KB**'lık sınır (`zero.ratelimit.max-body-bytes`, B2) bundan bağımsızdır ve
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

**J — `/actuator/**` perimeter'de kapatılmalı; scrape iki yoldan biriyle kurulur (PROD-R17)**

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

*Bilinen kısıt (kabul edilmiş):* access token 15 dakikada bir yenilenmeli (PROD-R16: rotasyon/
revocation yok), yani 2. yol scrape tarafında bir token tazeleyici gerektirir. Uzun vadeli
doğru çözüm `management.server.port`'u ayrı bir porta almak ve o portu **hiç yayınlamamaktır**;
o durumda ana security chain o porta uygulanmaz, koruma tamamen ağ katmanına geçer — bu yüzden
port yayınlanırsa açık bir regresyondur.

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

> Not: `Dockerfile:9` `package -DskipTests` ile build ediyor — **imaj testleri koşmaz.**
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

**Mevcut durum:** `application.yml:10` `spring.flyway.enabled: true` → migration **uygulama
başlangıcında** çalışıyor. Migration'lar: `V1__baseline` … `V5__shedlock` (`backend/src/main/resources/db/migration/`).

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
Ön koşul: §1.3-D (Redis cache) kapalı olmalı, yoksa iki instance farklı feature değeri görür.

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

**Üç ucun rolü ayrıdır — LB/probe'u yanlış olana bağlamak kesinti üretir (PROD-R29).**

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
`redis` DOWN ise → §5.2 (uygulama servis etmeye **devam eder**, cache bypass + WARN — PROD-R13).
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
**`prod` değilse: derhal durdur** — dev JWT secret ve `seed.enabled=true` ile çalışıyorsun.

### 3.3 Prometheus erişimi
```bash
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/actuator/prometheus
```
Beklenen: **`401`** (kimliksiz). Kurulum §1.3-J'de.

**Üç durumu birden doğrula — ikisi geçip biri kalırsa açık kapanmamıştır (PROD-R17):**
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
Beklenen: `anon=401`, `tenant=403`, host `200`, `readiness=200`. Kanıt: `ActuatorExposureIT` (5).

### 3.4 Login smoke (host admin)
```bash
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"usernameOrEmail":"admin","password":"'"$ADMIN_PASSWORD"'"}' | jq -r .accessToken)
[ ${#TOKEN} -gt 100 ] && echo "LOGIN OK" || echo "LOGIN FAIL"

curl -s localhost:8080/api/users/me -H "Authorization: Bearer $TOKEN" | jq '.permissions | length'
```
Beklenen: `LOGIN OK`; host admin izin sayısı **22** (F5-A canlı kanıtı, QUALITY-GATES-RESULTS.md).
Sayı düşükse (ör. 17) → **F5-R9 nüksü**: izin uzlaştırması çalışmamış. Bloker; §4'e git.

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
(status + currentPeriodEndAt). Feature limiti doğrulaması (Slice B'den sonra):
plan limitini aşan bir kullanıcı oluşturma denemesi **403/400** dönmeli.

> **Varsayım:** feature enforcement / subscription guard uçları Slice B ile geliyor; kesin
> endpoint ve hata kodu Slice B kapanışında bu bölüme yazılmalı.

### 3.7 Frontend smoke
Tarayıcıda prod URL → login → kullanıcı listesi. DevTools Network'te **CORS hatası olmamalı**
(§1.3-C). Console'da 401 döngüsü olmamalı (refresh singleflight çalışıyor olmalı).

### 3.8 Lifecycle job (Slice B)
```bash
docker exec -it zero-postgres psql -U zero -d zero -c "select * from shedlock;"
```
Beklenen: job ilk tetiklendikten sonra bir satır; `lock_until` geçmişte → job serbest.
`locked_at` çok eski + `lock_until` gelecekte takılıysa → §5.5.

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
    │   │       (ornek: V5__shedlock — saf ekleme, eski surum tabloyu hic gormez)
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
1. `docker exec zero-postgres pg_isready -U zero -d zero` → DB ayakta mı?
2. Ayakta ise kimlik bilgisi: `DB_USER`/`DB_PASSWORD` env'i doğru mu (§1.2 komutu)?
3. `FATAL: too many connections` ise → Hikari pool boyutu × instance sayısı > PG `max_connections`.
   İlk müdahale: fazla instance'ı durdur; kalıcı: `spring.datasource.hikari.maximum-pool-size` ayarla.
4. Ağ/DNS ise: `DB_URL` host'una `nc -zv <host> 5432`.

### 5.3 Senaryo 2 — Redis down
*Belirti:* health `redis: DOWN`. **Etki:** impersonation token store (`ImpersonationTokenStore`) ve
SaaS feature cache Redis'e dayanıyor → impersonation kırılır; feature çözümü cache'siz kalır.
1. `docker exec zero-redis redis-cli ping` → `PONG` bekleniyor.
2. `PONG` geliyor ama app DOWN diyorsa → **§1.3-A**: `REDIS_PORT` env okunmuyor, app 6379'a gidiyor.
   İlk müdahale: Redis'i 6379'da yayınla ya da `spring.data.redis.port` prod yml'de düzelt.
3. Redis gerçekten çökmüşse: yeniden başlat. Cache kaybı zararsız (yeniden ısınır);
   **impersonation oturumları düşer** — beklenen davranış, kullanıcıya bildir.

### 5.4 Senaryo 3 — JWT secret yanlış
*Belirti A:* startup patlıyor → `zero.jwt.secret must decode to at least 64 bytes` /
`must be valid base64` (`JwtService.java:83-89`). → Secret'ı düzelt, yeniden başlat.
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
   docker exec -it zero-postgres psql -U zero -d zero \
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
   docker exec -it zero-postgres psql -U zero -d zero -c "select * from shedlock;"
   ```
2. `lock_until` **gelecekte** ve `locked_by` artık yaşamayan bir instance ise → kilit takılmış
   (job crash etti, lock süresi dolmadı). `lock_at_most_for` varsayılanı **PT10M**
   (`SchedulingConfig.java:30`) → normalde 10 dk içinde kendiliğinden serbest kalır.
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

## Ek — açık bloker özeti (deploy öncesi kapatılmalı)

| # | Konu | Kanıt | Şiddet |
|---|---|---|---|
| G | `SPRING_PROFILES_ACTIVE` imajda set değil → dev JWT secret + seed açık | `Dockerfile:22`, `application.yml:34,39` | **Kritik** |
| C | CORS yapılandırması hiç yok | `SecurityConfig.java:42-59` | **Kritik** (ayrı origin ise) |
| A | `REDIS_PORT` env okunmuyor | `application.yml:25` | **Yüksek** (6379 dışında ise) |
| B | SMTP auth desteklenmiyor; `MAIL_HOST` boşsa sessiz no-op | `application.yml:15-17` | **Yüksek** |
| E | Swagger/OpenAPI prod'da public | `SecurityConfig.java:50` | **Orta** |
| D | `spring.cache.type` prod'da `redis` değil (F5-R2) | `application.yml:12`, `application-prod.yml` | **Orta** (rolling'de Yüksek) |
| F | `/actuator/prometheus` 401 → scrape çalışmaz | `application.yml:42` + `SecurityConfig.java:50` | **Orta** |
| H | `docker-compose.yml` app servisi içermiyor | `docker-compose.yml:1-40` | **Düşük** (işletme netliği) |
