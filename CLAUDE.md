# CLAUDE.md

Bu dosya her oturumda otomatik yüklenir. Kısa tutulmalı: buraya yazılan her satır, her
oturumun bağlam bütçesinden düşer. Uzun anlatım `zero-spring/docs/` altına gider; buraya
yalnızca **her seferinde gerekli olan** ve **kodu okuyarak anlaşılamayacak** şeyler yazılır.

## Proje

Çok kiracılı (multi-tenant) SaaS başlangıç şablonu. Java 21 / Spring Boot 3.5 / Spring
Modulith backend + React 19 / Vite / TypeScript frontend. PostgreSQL + Flyway, Redis, JWT,
izin tabanlı RBAC, editions/subscriptions/features SaaS katmanı.

## Depo yerleşimi — DİKKAT

```
<repo-kökü>/
├── .github/workflows/ci.yml     ← BURADA olmak ZORUNDA (aşağıdaki tuzağa bakın)
├── .gitleaks.toml
├── CLAUDE.md
└── zero-spring/                 ← ürün kodu bu alt dizinde
    ├── backend/                 (Maven, ./mvnw)
    ├── frontend/app/            (npm)
    ├── scripts/ci-local.sh
    └── docs/
```

Ürün `zero-spring/` altında ama **GitHub Actions yalnız repo kökündeki `.github/workflows/`
dizinini okur**. CI bu yüzden `defaults.run.working-directory: zero-spring` kullanır ve
yollar iki ayrı kurala tabidir (aşağıya bakın).

## Komutlar

```bash
# backend — clean ZORUNLU, aşağıdaki tuzağa bakın
cd zero-spring/backend && ./mvnw -B -ntp clean verify

# frontend
cd zero-spring/frontend/app && npm ci && npm run build && npm run test

# typed client (backend dev profilde ayakta olmalı)
npm run gen:api

# CI gate'lerini LOKALDE koşturmak (push etmeden önce; Actions dakikası pahalı)
bash zero-spring/scripts/ci-local.sh            # hepsi
bash zero-spring/scripts/ci-local.sh readiness  # tek gate

# bağımlılıklar
cd zero-spring/backend && docker compose up -d   # postgres:5433, redis:6380, mailpit:1025/8025

# Konteynerlere SERVİS adıyla eriş, konteyner adıyla değil — `container_name` bilinçli olarak
# yok (R-01b: bu şablondan türetilmiş iki proje aynı makinede çalışabilsin diye).
# Owner/superuser = `postgres` (şifre `postgres`); uygulama `zero_app` ile bağlanır (RLS taban çizgisi).
docker compose exec postgres psql -U postgres -d zero
docker compose exec redis redis-cli ping

# Portlar çakışırsa (varsayılanlar 5433/6380/1025/8025 — application-dev.yml bunları bekler):
#   POSTGRES_PORT=5434 docker compose up -d   ⚠️ DB_URL'i de güncelle, otomatik senkron DEĞİL
#   REDIS_PORT=6381    docker compose up -d   (app da aynı env'i okur, tek hamlede tutarlı)
# Proje adı çakışırsa (varsayılan = dizin adı, yani her klonda `backend`):
#   COMPOSE_PROJECT_NAME=musteri-x docker compose up -d
```

## Tuzaklar — hepsi bu projede canlı olarak yaşandı

Bunlar tercih değil, **ölçülmüş** davranışlar. Her biri en az bir kez yanlış yeşil ya da
yanlış kırmızı üretti.

| Tuzak | Kural |
|---|---|
| Maven artımlı derleme, geriye giden dosya zaman damgasında (`Copy-Item`, stash pop) derlemeyi **atlar** → bayat `.class` ile test koşar | Her zaman `clean verify`. CI de öyle yapar. |
| `.github/workflows/` repo kökünde değilse Actions dosyayı **hiç kaydetmez** — hata vermez, sessizce koşmaz | Workflow kökte kalmalı. Değiştirirken `gh api .../actions/runs --jq .total_count` ile teyit et. |
| CI'da adım seviyesi `working-directory` ve action `with:` girdileri **workspace köküne** göre çözülür; `run:` içindeki yollar **PWD'ye** göre | ci.yml'de yol değiştirirken bu ikisini karıştırma. `download-artifact path:` ile onu okuyan `run:` adımı birlikte hareket eder. |
| `/actuator/health` **tüm** indicator'ları yoklar; tali bir bağımlılık (redis, mail) düşünce 503 döner | Trafik kapısı için **daima** `/actuator/health/readiness` (= `readinessState + db`). LB/probe/CI hazır-olma kontrolü buraya bakar. |
| springdoc, `Page`/`Pageable`'ı reflection ile gezer; `getDeclaredMethods()` sırası JVM'ler arası **kararsız** | `springdoc.writer-with-order-by-keys: true` açık kalmalı, yoksa typed-client-drift gate'i rastgele kırmızıya döner. |
| Uygulanmış bir Flyway migration dosyasını **düzenlemek**, mevcut kurulumlarda checksum hatası verir | Değişiklik daima **yeni** bir `V<n>__` dosyası. CI'daki `migration-drift` gate'i bunu yakalar. |
| gitleaks v8.18.4 **tekil** `[allowlist]` okur; çoğul `[[allowlists]]` sessizce yok sayılır (hata vermez) | `.gitleaks.toml` değişirse etkisini **bulgu sayısıyla** ölç, sözdizimine güvenme. |
| Çözülmemiş `${VAR}` placeholder'ı hata vermeden **literal string** olarak bağlanır | Default'suz her prod property'sinin bir doğrulayıcısı olmalı (bkz. `JwtSecretValidator`, `CorsProperties`). |
| Temiz veritabanıyla koşan testler, **mevcut kurulum** hatalarını göremez | Şema/izin/seed değişikliklerinde canlı smoke zorunlu — temiz-DB suite'i yeşilken çalışan kurulum bozuk olabilir (bu bir kez gerçekleşti). |
| PowerShell 5.1: `git commit -m @'...'@` here-string'i çalışmaz; `ConvertTo-Json` tek elemanlı diziyi düzleştirir | Commit mesajını dosyaya yazıp `git commit -F <dosya>`. |
| IDE'nin Java dil sunucusu (VS Code / JDT / ECJ) Maven ile **aynı `target/`** dizinine derler ve komut satırı build'i **sürerken** class dosyalarını ezer. İki farklı yanıltıcı belirti: `NoClassDefFoundError: X$1` (ECJ'nin enum-switch lowering'i javac'tan farklı → "flaky test" görünümü) ve runtime'da `java.lang.Error: Unresolved compilation problem` (ECJ derleyemediği metodu patlayan bytecode ile yazar — suçluyu ele veren imza) | Bu iki hatada önce **timestamp**'e bak: build süresiyle uyuşmayan toplu yazma = IDE. Kesin kanıt: `grep -rl "Unresolved compilation" target/test-classes` — eşleşme varsa o class'lar ECJ ürünü. Çözüm: build sırasında projeyi IDE'de kapalı tut ya da `java.import.exclusions` ile dışla, sonra `clean verify` tekrarla. |
| Node **≥ 25** deneysel Web Storage global'i, Vitest'in jsdom ortamında (`globalThis === window`) jsdom'un `localStorage`'ını gölgeler ve getter `undefined` döndürür → `localStorage`'a dokunan her test `Cannot read properties of undefined (reading 'clear')` ile düşer. CI Node 22'ye sabitli, yani **kapı bunu hiç görmez** — yalnız geliştiricinin makinesi görür | `src/test/setup.ts` bellek-içi bir `Storage` ile değiştirir; kontrol sürüm numarasına değil **yeteneğe** bakar (`typeof existing.clear !== 'function'`), Node 22'de stub devreye girmez. Ölçüm: Node 26.5'te 146/168 test kırmızıydı. |

## Takım

| Ajan | Ne zaman |
|---|---|
| `tech-lead` | Tek katmanı aşan her iş. Dikey dilimlere böler, mühendislere dağıtır, birleştirir, kanıtla raporlar. |
| `backend-engineer` | Java/Spring tarafı: uç, servis, entity, migration, izin, IT. |
| `frontend-engineer` | React tarafı: ekran, feature modülü, typed API, i18n, davranış testi. |
| `stack-reviewer` | Kod değişikliğinden **sonra**, commit'ten önce. Bu yığına özgü tuzaklar. |
| `gate-auditor` | Yeni test/CI gate'inden sonra. **Gate'in gerçekten kırmızıya döndüğünü kanıtlar** — bu depoda beş kontrol yeşilken hiçbir şey doğrulamıyordu. |

Küçük ve tek katmanlı işte `tech-lead`'i atla, doğrudan mühendisi çağır. Bağımsız işleri
**paralel** çağır.

| Komut | Ne zaman |
|---|---|
| `/new-module <ad>` | Yeni modül eklerken. Unutulduğunda **sessiz kalan** 10 korumanın listesi. |
| `/preflight` | Push etmeden önce. CI dakikası harcamadan yerel kapılar. |

**Dikey dilim = "bitti"** — beş sütun: backend · frontend · izin+i18n · test (mutlu yol +
**negatif yetki**) · risk kaydı. Dördü tamamsa iş bitmemiştir.

## Konvansiyonlar

- **İzinler:** `AppPermissions` sabitleri + `PermissionDefinitions` ağacı. Yeni uç →
  `@PreAuthorize` **ve** frontend `<Can>` **ve** route guard (üçlü kilit). String literal yazma.
- **Kiracılık:** JWT `tenant` claim'i otoriter; header uyuşmazlığı 403. Yeni entity'lerde
  `tenant_id` + Hibernate `@Filter`. Filtre eklemeyi unutmak sessizce sızıntı üretir.
- **Modulith:** modüller arası erişim yalnız `@NamedInterface` üzerinden. `ApplicationModules.verify()`
  test zamanında sınırları zorlar; döngü yasak.
- **API:** hatalar RFC 9457 `ProblemDetail`. Entity doğrudan dönülmez, DTO kullanılır.
- **Zaman:** `Clock` bean'i enjekte edilir (testlerde zaman ileri alınabilsin diye).
  Kolonlar `timestamptz` — belgelenmiş tek istisna ShedLock.
- **Sayfalama:** `@EntityGraph` + `Pageable` **birlikte kullanılmaz** — Hibernate koleksiyon
  fetch ile pagination'ı birlikte göremez, tüm satırları çekip bellekte diler
  (`HHH90003004`). İki aşamalı sorgu ya da `@BatchSize` kullan.

## Çalışma kuralları

- **Kanıtsız "tamamlandı" yok.** Her düzeltme için: hangi test/smoke geçti, hangi risk kapandı.
- **Negatif kanıt.** Düzeltmeden önce testi yaz ve **eski kodda düştüğünü gör**. Düşmüyorsa
  test yanlış şeyi ölçüyordur.
- **Sınıfı kapat, yazımı değil.** Bu projede dört kez, raporlanan tek varyantı düzeltmek bir
  sonrakini açık bıraktı (415 → wildcard → `application/yaml` → sort'un üçüncü şekli).
- **Yeşil ≠ doğruladı.** Bir gate'in geçmesi, bir şeyi kontrol ettiği anlamına gelmez;
  `migration-drift` boş bir sette hiçbir şey doğrulamadan yeşil dönebilir. Log'dan teyit et.
- Yönetişim dosyaları güncel tutulur: `docs/governance/` altında RISK-REGISTER,
  QUALITY-GATES-RESULTS, ADR, CHANGELOG.

## Bilinen açıklar

`docs/governance/RISK-REGISTER.md` tek doğru kaynak. Şablonu klonlarken bakılacaklar:
`PROD-R23` (branch protection ücretsiz planda
kurulamıyor — kırmızı check push'u engellemez), `PROD-R27` (Dockerfile'ı hiçbir gate build
etmiyor).
