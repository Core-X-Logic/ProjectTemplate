# Operatör Handoff Rehberi

**Hedef kitle:** ürünü prod'a çıkaran/işleten ekip + devri yapan geliştirici.
**Başarı ölçütü:** sorumluluk sınırı net; secret geliştiriciden geçmeden operatörde; prod provisioning
checklist eksiksiz; deploy/rollback tek sayfadan yürütülebilir.

> Tam detay: `../SETUP-NEW-PROJECT.md` §6 (Actions Variables/Secrets), `../RELEASE-RUNBOOK.md`
> (§1 config, §2 deploy, §3 smoke, §4 rollback). Bu rehber **devir sınırını** çizer.

## 1. Sorumluluk ayrımı

| Konu | Geliştirici | Operatör |
|---|---|---|
| Kod + test + CI yeşil | ✅ | — |
| CI pipeline hazırlığı (kapılı push/deploy) | ✅ | — |
| Prod secret **değerleri** (JWT_SECRET, DB şifresi…) | ❌ görmez/yazmaz | ✅ secret store'a koyar |
| Actions Variables/Secrets doldurma | referans verir | ✅ doldurur |
| `DEPLOY_ENABLED=true` / gerçek deploy | ❌ | ✅ |
| Prod altyapı (Redis HA, DB, reverse proxy) | gereksinimi yazar | ✅ sağlar |
| Rollback tetikleme | prosedürü yazar | ✅ yürütür |

**Kesin kural:** geliştirici (ve AI ajanı) prod secret **değerini görmez, repoya yazmaz**. Secret
yalnız operatörün secret store'unda + Actions Secrets'ta yaşar. Bu bir tercih değil, güvenlik sınırı.

## 2. Secret yönetimi sınırları

- **Repoya asla:** JWT_SECRET, FIELD_ENCRYPTION_KEY, DB_PASSWORD, REDIS şifresi, registry token,
  deploy komutu kimlik bilgisi. Hiçbiri commit'lenmez — `security-checks` (gitleaks) bunu kapıda tutar.
- **Nereye:** çalışma-anı sırları → prod secret store (K8s Secret / cloud secret manager);
  CI tetik sırları → GitHub Actions Secrets/Variables.
- **`docker login`** `--password-stdin` ile — kimlik bilgisi log'a düşmez (pipeline böyle yazıldı).
- **Çözülmemiş `${VAR}` hata vermez**, literal string olarak bağlanır. Default'suz her prod
  property'sinin doğrulayıcısı var (`JwtSecretValidator`, `CorsProperties`) → eksik secret'ta boot reddedilir.

## 3. Prod provisioning checklist

Deploy'dan **önce** operatör tamamlar (kanıt kolonu = nasıl doğrulanır):

### 3.1 Çalışma-anı sırları → prod secret store (RUNBOOK §1.1)
- [ ] `SPRING_PROFILES_ACTIVE=prod` — log ilk satırı `profile is active: "prod"`
- [ ] `JWT_SECRET` (base64 çözümü **≥64 bayt**) — `echo -n "$JWT_SECRET" | base64 -d | wc -c`
- [ ] `FIELD_ENCRYPTION_KEY` (base64 32 bayt)
- [ ] `DB_URL` / `DB_USER` / `DB_PASSWORD` — health `"db":{"status":"UP"}`; prod'da şifre `zero` **değil**
- [ ] `REDIS_HOST` / `REDIS_PORT` — health `"redis":{"status":"UP"}`
- [ ] `CORS_ALLOWED_ORIGINS` (default YOK) — listedeki origin `200`, dışı `403`
- [ ] `MAIL_*` (e-posta gerekiyorsa) — forgot-password smoke; log'da `LoggingEmailSender` **görünmemeli**
- [ ] `VITE_API_BASE_URL` (**build-time** — imaj/dist üretilirken)

### 3.2 Altyapı
- [ ] **Redis HA** ayakta — revocation fail-closed: Redis düşerse auth reddedilir (tasarım). Tek instance risk.
- [ ] Reverse proxy: `client_max_body_size`, `/actuator/**` dışa kapalı, `X-Forwarded-*` ezme (RUNBOOK §1.3-I/J)
- [ ] Readiness probe **`/actuator/health/readiness`**'e bağlı (liveness'a/aggregate'e değil)

### 3.3 CI tetik değişkenleri → Actions (SETUP §6)
- [ ] Variables: `IMAGE_REGISTRY`, `IMAGE_NAME`, `DEPLOY_ENVIRONMENT`
- [ ] Secrets: `DEPLOY_COMMAND` (+ GHCR değilse `REGISTRY_TOKEN`)
- [ ] Push doğrula: `PUSH_IMAGE=true` (deploy KAPALI) → registry'de `sha-<kısa>` tag + digest
- [ ] Hazırsa `DEPLOY_ENABLED=true`

## 4. Deploy kısa akışı (RUNBOOK §2)

```
1. Sürüm sabitle — image sha-<kısa-sha> (RC tag'i biliniyorsa ondan).
2. PUSH_IMAGE=true → docker-build imajı push eder (hardening assert push'tan ÖNCE koşar).
3. DB migration stratejisi: Flyway; rolling'e geçince migration'ı ayrı koş (RUNBOOK §2.3).
4. DEPLOY_ENABLED=true → release önce dry-run plan yazar, sonra DEPLOY_COMMAND koşar.
   Eksik IMAGE_REGISTRY/DEPLOY_COMMAND → fail-fast (sessiz yanlış deploy yok).
5. Readiness bekle: /actuator/health/readiness UP olana kadar.
6. Post-deploy smoke (RUNBOOK §3): readiness · login · /me · anonim /me 401 · tenant negatif ·
   forgot-password · 2FA ikinci adım. Hepsi geçerse GO.
```

## 5. Rollback kısa akışı (RUNBOOK §4)

```
1. Uygulama sürümünü geri al: imajı ÖNCEKİ tag'e döndür (DEPLOY_COMMAND ile).
2. Readiness UP bekle + §4 smoke tekrar.
3. ŞEMA GERİ ALINMAZ: V1..V10 geriye-uyumlu (yeni kolonlar defaulted/nullable, yeni tablolar
   additive). Eski imaj yeni şemayla çalışır. Migration'ı geri almaya ÇALIŞMA.
```

## 6. GO / NO-GO

**GO** ancak: provisioning checklist (§3) tam · CI 9/9 yeşil · post-deploy smoke (§4) hepsi geçti ·
açık kritik/yüksek bulgu yok.
**NO-GO:** herhangi bir zorunlu env/secret eksik, ya da smoke'ta bir negatif kapı açık (ör. anonim
`/me` `200` döndü) → deploy'a geçme, eksiği kapat. Rehearsal (dev/8080) gerçek deploy **değildir** —
karıştırma.

> Bilinen operatör-bağımlı kısıtlar: `../governance/RISK-REGISTER.md` — özellikle PROD-R23 (branch
> protection ücretsiz planda kurulamaz), PROD-R13 (Redis hard auth bağımlılığı).
