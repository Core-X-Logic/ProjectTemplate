# Başlangıç Rehberi (Quickstart)

**Hedef kitle:** şablonu yeni klonlayan geliştirici.
**Süre:** 15–30 dk (ilk indirmeler dahil).
**Başarı ölçütü:** aşağıdaki §4 smoke akışının 4 adımı da yeşil — o an repo lokalde çalışıyor demektir.

> Bu doküman **lokalde çalıştırmayı** anlatır. GitHub tarafı repo kurulumu (CI enable, branch
> protection, secrets) ayrı: `SETUP-NEW-PROJECT.md`. Adını değiştirme: `RENAME.md`.

## 1. Zorunlu bağımlılıklar

| Araç | Sürüm | Doğrula |
|---|---|---|
| JDK | **21** | `java -version` → `21.x` |
| Node | **22** | `node -v` → `v22.x` |
| Docker | güncel + `compose` | `docker compose version` |

Biri eksikse dur — sonraki adımlar sessizce yanlış çalışır (yanlış JDK'da `clean verify` derlenmez).

## 2. Altyapıyı kaldır (Postgres · Redis · Mailpit)

```bash
cd zero-spring/backend
docker compose up -d
docker compose ps          # üçü de "running/healthy" olmalı
```

Portlar: Postgres **5433**, Redis **6380**, Mailpit **1025** (SMTP) / **8025** (arayüz).
`application-dev.yml` tam bu portları bekler.

**Konteynere servis adıyla eriş** (konteyner adı bilinçli yok — R-01b):
```bash
docker compose exec postgres psql -U postgres -d zero -c '\dt'
docker compose exec redis redis-cli ping        # PONG
```

Port çakışırsa: `POSTGRES_PORT=5434 docker compose up -d` — ⚠️ `DB_URL`'i de elle güncelle,
otomatik senkron **değil**. İki klonu aynı makinede koşturuyorsan:
`COMPOSE_PROJECT_NAME=musteri-x docker compose up -d`.

## 3. Backend + Frontend çalıştır

```bash
# Backend — dev profili ZORUNLU (aşağıdaki nota bak)
cd zero-spring/backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# Frontend — ayrı terminal
cd zero-spring/frontend/app
cp .env.example .env        # VITE_API_BASE_URL=http://localhost:8080
npm ci
npm run dev
```

Arayüz <http://localhost:5173> · API <http://localhost:8080> · Swagger
<http://localhost:8080/swagger-ui.html> · gelen e-posta <http://localhost:8025>.

**İlk giriş** (yalnız dev profilinde seed'lenir): kullanıcı `admin`, şifre `Admin123!`,
kiracı **boş** = host yöneticisi (`default` yazarsan kiracı yöneticisi).

> **`dev` profili neden zorunlu:** temel config hiçbir şey seed etmez, CORS boş, API dokümanı
> kapalı — bilinçli fail-closed varsayılanlar. Profilsiz başlatırsan uygulama **açılmaz**
> (JWT secret default'u yok). Bu doğru davranış, hata değil.

## 4. İlk smoke akışı — "çalışıyor" kanıtı

Backend ayaktayken sırayla koş. Dördü de beklenen sonucu verirse **kurulum sağlıklı**:

```bash
# 1) Readiness — trafik kapısı (aggregate /health DEĞİL; §sık-hatalar'a bak)
curl -s http://localhost:8080/actuator/health/readiness      # {"status":"UP"}

# 2) Login → token al
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"usernameOrEmail":"admin","password":"Admin123!"}' | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
echo "${TOKEN:0:20}..."                                       # boş DEĞİL

# 3) /me — kimlikli çağrı 200
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/api/users/me \
  -H "Authorization: Bearer $TOKEN"                            # 200

# 4) NEGATİF — anonim /me 401 (güvenlik kapısı gerçekten kapalı mı)
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/api/users/me   # 401
```

Adım 4 (negatif) atlanmaz: 200/UP görmek kapının **açık** olduğunu kanıtlamaz; 401 görmek
kapının **kapandığını** kanıtlar. Detaylı prod smoke: `RELEASE-RUNBOOK.md` §3.

## 5. Sık yapılan kurulum hataları

Hepsi bu depoda gerçekten yaşandı. Belirti → kök neden → çözüm:

| Belirti | Kök neden | Çözüm |
|---|---|---|
| Uygulama açılmıyor, log'da `JWT secret` / placeholder hatası | Profil verilmedi; prod'da JWT default'u yok (fail-closed) | `-Dspring-boot.run.profiles=dev` ekle |
| Frontend **beyaz ekran**, konsolda `VITE_API_BASE_URL undefined` | `.env` yok | `cp .env.example .env` (gitignore'lu; build-time değişken) |
| Login "network error" ama backend ayakta | CORS: dev backend origin `:5173` bekler, Vite `:5174`'e kaymış | `:5173`'ü boşalt ya da `CORS_ALLOWED_ORIGINS`'a doğru portu ekle |
| `/actuator/health` **503** ama uygulama sağlıklı | Aggregate health tali bağımlılığı (redis/mail) yoklar; biri düşünce 503 | Trafik kapısı için **daima** `/actuator/health/readiness` kullan |
| forgot-password `400` beklerken | Payload alanı yanlış: `email` değil `usernameOrEmail` | `{"usernameOrEmail":"admin"}` gönder |
| Test yeşil ama davranış eski | Maven artımlı derleme bayat `.class` ile koştu | **Daima** `./mvnw -B -ntp clean verify` |
| DB health `DOWN`, startup `FATAL: password authentication failed` | Docker kalkmadı ya da port/şifre uyuşmuyor | `docker compose ps`; `DB_*` env'i 5433/`zero`/`zero` ile hizala |
| `docker compose exec zero-postgres ...` "no such service" | Konteyner adıyla erişim; `container_name` bilinçli yok | Servis adı kullan: `postgres`, `redis`, `mailpit` |

## 6. Sonraki adım

- Kod geliştireceksen → `WORKING-WITH-AI.md` (AI ajanlarıyla doğru iş verme) + `../ADDING-A-MODULE.md`.
- Push etmeden önce → `bash zero-spring/scripts/ci-local.sh` (CI dakikası harcamadan yerel kapılar).
- Neyin hazır **olmadığını** öğren → `../governance/RISK-REGISTER.md`. Klonlarken önce buraya bak.
