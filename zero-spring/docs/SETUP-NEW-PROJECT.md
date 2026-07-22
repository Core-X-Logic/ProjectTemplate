# Yeni repo kurulumu

Şablonu klonlayıp kendi adınıza çevirdikten (`RENAME.md`) sonra, deponun kendisini kurmak için
bu liste. Kod tarafı değil, **GitHub tarafı**.

## 1. CI'ı etkinleştirin

Şablonda workflow **kapalı** gelir (`disabled_manually`) — klonladığınız anda dokuz job'lık bir
zincir koşup Actions dakikanızı harcamasın diye.

```bash
gh workflow enable CI
gh workflow list --all          # beklenen: CI  active
```

İlk koşuyu elle tetikleyip yeşil olduğunu görün:

```bash
gh workflow run CI
gh run watch
```

**Gerekli secret yok.** Zincirin tamamı kendi Postgres servisini ayağa kaldırır ve JWT secret'ını
koşu sırasında üretir. `secrets.*` referansı yoktur (doğrulandı). Bu, şablonu klonlayıp hemen
koşturabilmeniz için bilinçli bir tasarım.

## 2. Branch protection — ÖNCE PLANINIZI KONTROL EDİN

CI zinciri sıralıdır ve bir kapı düşerse sonrakiler koşmaz. **Ama bu, main'e push'u
engellemez.** Kırmızı bir check'in gerçekten bloke edebilmesi için branch protection gerekir ve:

```bash
gh api repos/<org>/<repo>/branches/main/protection
```

**Private repo + ücretsiz plan ise 403 döner:**
`"Upgrade to GitHub Pro or make this repository public"`. Yeni rulesets API'si de aynı yanıtı
verir. Yani üç seçeneğiniz var:

| Seçenek | Sonuç |
|---|---|
| Plan yükseltin (Team/Pro) | Branch protection gerçekten çalışır |
| Repo'yu public yapın | Ücretsiz planda da çalışır |
| Olduğu gibi bırakın | CI **raporlar** ama engellemez; blokaj insan disiplinindedir |

Üçüncüsünü seçerseniz **bunu bilerek seçin** ve ekibe söyleyin. "CI yeşil olmadan merge yok"
kuralı o durumda bir sözleşmedir, bir kontrol değil.

Koruma kurabiliyorsanız, required status check adları (zincirdeki 9 job'dan `release` hariç 8'i;
`release` yalnız `push` + `main`'de koşar, PR'da **skipped** olduğu için required check yapılamaz):

```
build · backend · frontend · docker-build · typed-client-drift · migration-drift · live-smoke · security-checks
```

`backend` ve `frontend` job adları bu yüzden kasıtlı olarak sade bırakılmıştır — yeniden
adlandırmak korumayı **sessizce** devre dışı bırakır (check "pending" kalır ya da hiç
raporlanmaz).

## 3. Repo ayarları

- **Default branch:** `main`.
- **Actions izinleri:** varsayılan (`allowed_actions: all`) yeterli. Kısıtlarsanız
  `actions/checkout`, `actions/setup-java`, `actions/setup-node`, `actions/upload-artifact`,
  `actions/download-artifact` izinli olmalı.
- **Merge stratejisi:** tercihe bağlı; şablon bir varsayım yapmaz.
- **Dependabot:** şablonda tanımlı **değil**. `npm audit` kapısı bağımlılık zafiyetini yakalar
  ama güncelleyecek otomasyon yoktur — `.github/dependabot.yml` eklemek ilk işlerinizden olsun.

## 4. Üretim ortamı değişkenleri

CI'ın secret'a ihtiyacı yok, ama **üretim deploy'unuzun var**. Tam liste ve doğrulama
komutları: `RELEASE-RUNBOOK.md` §1.1.

Varsayılanı **olmayan** ve eksikse uygulamanın **açılmadığı** ikisi:

| Değişken | Neden default'u yok |
|---|---|
| `JWT_SECRET` | Bir default, o anahtarı bilen herkese token üretme yetkisi verirdi. `openssl rand -base64 64` |
| `CORS_ALLOWED_ORIGINS` | Bir wildcard, herhangi bir siteye kullanıcının token'ıyla API'yi sürme yetkisi verirdi |

Bu ikisi **fail-closed**'dır ve öyle kalmalıdır. Eksikse container başlamaz — istenen davranış budur.

> Dikkat: çözülmemiş bir `${VAR}` placeholder'ı Spring'de hata vermez, **literal string** olarak
> bağlanır. Yukarıdaki ikisi yakalanır çünkü doğrulayıcıları var (`JwtSecretValidator`,
> `CorsProperties`). Kendi eklediğiniz default'suz bir property'nin de doğrulayıcısı olmalı,
> yoksa `"${MY_VAR}"` değerini sessizce kabul eder.

## 5. İlk deploy öncesi

- `RELEASE-RUNBOOK.md` §1.2 kapısını koşun.
- `RISK-REGISTER.md`'deki **devralınan bilinen kısıtları** okuyun ve kabul edip etmediğinize
  karar verin. Özellikle: rate limit çok-instance'ta bölünür (PROD-R6), access token 15 dk iptal
  edilemez (PROD-R16). (`/api/users` bellek-sayfalama kısıtı Q-03'te kapandı.)
- Reverse proxy kuralları (`client_max_body_size`, `/actuator/**` kapatma, `X-Forwarded-*`
  ezme) uygulama tarafından **garanti edilemez** — runbook §1.3-I ve §1.3-J.

## 6. Deploy — minimum adımlar (klonlayan doldurur)

Pipeline **prod-tetiklemeye hazır** ama varsayılanı **güvenli no-op**: `docker-build` imajı build edip
sertleştirmesini doğrular ve `PUSH_IMAGE=true` olana dek **push ETMEZ**; `release` job'ı
`DEPLOY_ENABLED=true` olana dek yalnız **dry-run plan** yazar, gerçek deploy KOŞMAZ. Hiçbir secret
repoya yazılmaz — hepsi Actions Variables/Secrets ya da prod secret store'undan tüketilir.

### 6.1 Gerekli Actions **Variables** (Settings → Secrets and variables → Actions → Variables)

| Variable | Örnek | Rol |
|---|---|---|
| `IMAGE_REGISTRY` | `ghcr.io/<org>` | İmaj registry prefix'i (host + namespace) |
| `IMAGE_NAME` | `zero-backend` (varsayılan) | İmaj adı |
| `PUSH_IMAGE` | `true` | `true` → docker-build imajı push eder (varsayılan `false` = no-op) |
| `IMAGE_EXTRA_TAG` | `rc` / `prod` / `latest` | (opsiyonel) sha yanında ikinci etiket |
| `DEPLOY_ENVIRONMENT` | `prod` | `dev` \| `stage` \| `prod` |
| `DEPLOY_ENABLED` | `true` | `true` → release gerçek deploy'u koşar (varsayılan `false`) |
| `REGISTRY_USERNAME` | (opsiyonel) | Login kullanıcı adı; boşsa `github.actor` |

### 6.2 Gerekli Actions **Secrets**

| Secret | Rol |
|---|---|
| `REGISTRY_TOKEN` | Registry push kimlik bilgisi. **GHCR'da opsiyonel** — boşsa built-in `GITHUB_TOKEN`'a düşer (`packages: write` yetkisi zaten var) |
| `DEPLOY_COMMAND` | Cloud-agnostic deploy komutu, ör. `kubectl apply -f k8s/`, `flyctl deploy`, `aws ecs update-service …`. `IMAGE_REF` (`<registry>/<name>:sha-<kısa-sha>`) env olarak hazır verilir |

### 6.3 Uygulama sırları — **prod secret store'una** (repoya/Actions'a DEĞİL, orchestrator'a)

`application-prod.yml` env-referanslı; prod profili şunları bekler ve eksikse **boot reddeder**:
`JWT_SECRET` (≥64B base64), `FIELD_ENCRYPTION_KEY` (base64 32B), `DB_URL`/`DB_USER`/`DB_PASSWORD`,
`REDIS_HOST`/`REDIS_PORT` (**revocation fail-closed → Redis auth için zorunlu**), `CORS_ALLOWED_ORIGINS`,
(mail gerekiyorsa `MAIL_*`). `VITE_API_BASE_URL` frontend **build-time**.

### 6.4 Sıra

1. 6.3 sırlarını prod secret store'una koy (K8s Secret / cloud secret manager). **Redis HA** hazır olsun.
2. 6.1/6.2'yi doldur, önce `PUSH_IMAGE=true` (deploy KAPALI) ile push'u doğrula (registry'de `sha-…` tag + digest).
3. `DEPLOY_ENABLED=true` yap. main'e push → önce **dry-run plan** özeti çıkar, sonra `DEPLOY_COMMAND` koşar.
   Eksik `IMAGE_REGISTRY`/`DEPLOY_COMMAND` → job **fail-fast** anlaşılır hata verir (sessiz yanlış deploy yok).
4. **Doğrula:** imajın HEALTHCHECK'i `/actuator/health/readiness`'e bakar; orchestrator readiness
   probe'unu **aynı yola** bağla (liveness'a değil). Zorunlu prod smoke: readiness · login · `/me` ·
   anonim `/me` 401 · tenant negatif · forgot-password · 2FA second-step (RUNBOOK §3).
5. **Branch protection** (ücretli plan) → required checks: **§2'deki 8 job** (`release` hariç).
   **Rollback:** RUNBOOK §4 (imajı önceki tag'e; **şema geri alınmaz** — V1..V10 geriye-uyumlu).
