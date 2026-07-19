# Yeni repo kurulumu

Şablonu klonlayıp kendi adınıza çevirdikten (`RENAME.md`) sonra, deponun kendisini kurmak için
bu liste. Kod tarafı değil, **GitHub tarafı**.

## 1. CI'ı etkinleştirin

Şablonda workflow **kapalı** gelir (`disabled_manually`) — klonladığınız anda sekiz job'lık bir
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

Koruma kurabiliyorsanız, required status check adları:

```
build · backend · frontend · typed-client-drift · migration-drift · live-smoke · security-checks
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
  karar verin. Özellikle: rate limit çok-instance'ta bölünür, access token 15 dk iptal edilemez,
  `/api/users` bellekte sayfalar.
- Reverse proxy kuralları (`client_max_body_size`, `/actuator/**` kapatma, `X-Forwarded-*`
  ezme) uygulama tarafından **garanti edilemez** — runbook §1.3-I ve §1.3-J.
