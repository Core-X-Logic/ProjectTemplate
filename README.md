# Çok kiracılı SaaS başlangıç şablonu

Java 21 · Spring Boot 3.5 · Spring Modulith · PostgreSQL + Flyway · Redis
React 19 · Vite · TypeScript · Tailwind 4 · shadcn/ui

Yeni bir SaaS ürününe sıfırdan başlamak yerine buradan başlarsınız: kimlik doğrulama, izin
tabanlı yetkilendirme, çok kiracılılık, kullanıcı/rol/organizasyon yönetimi, denetim kaydı,
ayarlar, i18n, bildirimler ve editions/subscriptions/features SaaS katmanı hazır gelir.

**330 backend + 90 frontend testi**, 8 kapılı bir CI zinciri ve yayına çıkarma runbook'u ile.

---

## 1. Çalıştır (ilk 10 dakika)

Gerekli: **JDK 21**, **Node 22**, **Docker**.

```bash
# 1) Bağımlılıklar (postgres 5433, redis 6380, mailpit 1025/8025)
cd zero-spring/backend
docker compose up -d

# 2) Backend — dev profili ZORUNLU (aşağıdaki nota bakın)
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# 3) Frontend (ayrı terminal)
cd zero-spring/frontend/app
cp .env.example .env
npm ci
npm run dev
```

Arayüz: <http://localhost:5173> · API: <http://localhost:8080> · Swagger:
<http://localhost:8080/swagger-ui.html> · Gelen e-postalar: <http://localhost:8025>

**İlk giriş** (yalnızca dev profilinde seed edilir):

| Alan | Değer |
|---|---|
| Kullanıcı | `admin` |
| Şifre | `Admin123!` (`SEED_ADMIN_PASSWORD` ile değiştirilebilir) |
| Kiracı | boş bırakın = host yöneticisi · `default` = kiracı yöneticisi |

> **`dev` profili neden zorunlu:** temel yapılandırma **hiçbir şey** seed etmez, CORS listesi
> boştur ve API dokümanı kapalıdır — hepsi bilinçli fail-closed varsayılanlar, çünkü kayıp bir
> profilin sessizce güvensiz bir kurulum üretmesi istenmiyor. `dev` bunları açıkça açar.
> Profilsiz başlatırsanız uygulama **açılmaz** (JWT secret'ın varsayılanı yoktur) — bu doğru
> davranıştır.

## 2. Kendi projeniz yapın

```bash
./scripts/rename-project.sh <groupId> <artifactId>   # örn. com.acme.crm acme-crm
```

Ayrıntı ve elle yapılması gerekenler: **`RENAME.md`**. Yeniden adlandırmadan önce
`NOTICE.md`'yi okuyun — frontend teması ticari bir ürüne (Metronic) dayanır ve ayrıca
lisanslanmalıdır.

Yeni bir repo kurarken (branch protection, CI, secret'lar):
**`zero-spring/docs/SETUP-NEW-PROJECT.md`**.

## 3. Yol haritası

| Ne yapacaksınız | Nereye bakın |
|---|---|
| **Ekibi hızlı başlatmak / AI ile çalışmak** | `zero-spring/docs/usage/` — quickstart, prompt kataloğu, kanıt disiplini, ilk 7 gün, cheat sheet |
| **Yeni modül/özellik eklemek** | `zero-spring/docs/ADDING-A-MODULE.md` — atlanan her adım sessiz bir açıktır |
| Mimariyi anlamak | `zero-spring/docs/ARCHITECTURE.md` |
| Uyulması gereken kurallar | `zero-spring/docs/ARCHITECTURE-RULES.md` |
| Bir kararın gerekçesi | `zero-spring/docs/governance/ADR/` |
| Üretime çıkmak | `zero-spring/docs/RELEASE-RUNBOOK.md` |
| **Devraldığınız bilinen açıklar** | `zero-spring/docs/governance/RISK-REGISTER.md` |
| Tüm doküman haritası | `zero-spring/docs/README.md` |

## 4. Dizin yerleşimi

```
<repo>/
├── .github/workflows/ci.yml   ← BURADA olmalı; Actions yalnız repo kökünü okur
├── .claude/                    ajan takımı, komutlar, skill'ler
├── CLAUDE.md                   her oturumda yüklenir: tuzaklar + konvansiyonlar
├── LICENSE · NOTICE.md
└── zero-spring/
    ├── backend/                Maven (./mvnw), Spring Modulith modülleri
    ├── frontend/app/           React uygulaması
    ├── scripts/ci-local.sh     CI kapılarını yerelde koştur
    └── docs/                   mimari, runbook, ADR, risk kaydı
```

Ürün `zero-spring/` altında ama workflow dosyası **repo kökünde** — GitHub Actions başka bir
yere bakmaz. Bu depoda bir kez öğrenildi: dosya alt dizindeyken CI **hiç kaydedilmedi** ve
sekiz kapılık zincir aylarca kâğıt üstünde kaldı.

## 5. Geliştirirken

```bash
# push etmeden önce — CI dakikası harcamadan
bash zero-spring/scripts/ci-local.sh

# backend testleri — `clean` ZORUNLU (bkz. CLAUDE.md)
cd zero-spring/backend && ./mvnw -B -ntp clean verify

# frontend
cd zero-spring/frontend/app && npm run build && npm run test

# backend API değiştiyse typed client'ı yeniden üret
npm run gen:api
```

Claude Code kullanıyorsanız `/preflight` ve `/new-module` komutları ile `tech-lead`,
`backend-engineer`, `frontend-engineer`, `stack-reviewer`, `gate-auditor` ajanları hazır gelir.

## 6. Neyin hazır olduğu

| Alan | Durum |
|---|---|
| Kimlik doğrulama | JWT (15dk access + rotating refresh, reuse tespiti), BCrypt(12) |
| Yetkilendirme | İzin tabanlı RBAC, host/tenant ayrımı, üçlü kilit |
| Çok kiracılılık | Paylaşımlı şema + `tenant_id`, JWT claim otoriter |
| Kullanıcı yönetimi | Kullanıcı, rol, organizasyon birimi, impersonation |
| SaaS | Editions, subscriptions, feature gating, proration, yaşam döngüsü işi |
| Operasyon | Denetim kaydı, entity history, ayarlar, i18n (en/tr), bildirim, e-posta |
| Güvenlik | Rate limit, gövde sınırı, güvenlik başlıkları, CORS allowlist, secret taraması |
| Gözlemlenebilirlik | Actuator (yetkili), Prometheus metrikleri, yapısal log |

**Hazır olmayanlar** `RISK-REGISTER.md`'de açıkça listelenmiştir — özellikle şifre sıfırlama
ekranı, dosya yükleme, kullanıcı daveti ve ödeme sağlayıcı entegrasyonu. Şablon size ne
verdiğini olduğu kadar **ne vermediğini** de söyler.
