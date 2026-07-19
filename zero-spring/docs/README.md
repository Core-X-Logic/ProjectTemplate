# Doküman haritası

Çok kiracılı SaaS başlangıç şablonu — Java 21 / Spring Boot 3.5 / Spring Modulith backend +
React 19 / Vite / TypeScript admin arayüzü.

## Nereden başlamalı

| Durumun | Oku |
|---|---|
| Şablonu yeni klonladım | Repo kökündeki `README.md`, sonra `RENAME.md` |
| Yeni bir modül/özellik ekleyeceğim | **`ADDING-A-MODULE.md`** — atlanan her adım sessiz bir açık |
| Sistemin nasıl kurulduğunu anlamak istiyorum | `ARCHITECTURE.md` |
| Bir kuralın neden böyle olduğunu merak ediyorum | `ARCHITECTURE-RULES.md`, sonra `governance/ADR/` |
| Güvenlik yüzeyini anlamak / yeni uç yazacağım | **`SECURITY.md`** |
| Üretime çıkacağım | `RELEASE-RUNBOOK.md` |
| Neyin eksik/riskli olduğunu bilmek istiyorum | `governance/RISK-REGISTER.md` |

## Dosyalar

| Dosya | Ne işe yarar |
|---|---|
| `ADDING-A-MODULE.md` | Yeni modül eklemenin tam yordamı: Modulith sınırı, migration, izin kaydı, kiracılık, i18n, test. Kontrol listesiyle. |
| `ARCHITECTURE-RULES.md` | Kodda uyulması zorunlu kurallar, gerekçeleriyle. Çoğu burada bir kez ihlal edilip hataya yol açtığı için yazıldı. |
| `ARCHITECTURE.md` | Backend mimarisi: modül sınırları, çok kiracılık, kimlik doğrulama, veri katmanı. |
| `SECURITY.md` | Güvenlik modeli: kiracı izolasyonu, üçlü kilit, JWT, rate limit, secret yönetimi, log bütünlüğü. Her başlıkta neyin garanti **edilmediği** ve hangi testin tuttuğu yazılı. |
| `SAAS-ARCHITECTURE.md` | Editions / subscriptions / features katmanı: durum makinesi, fiyat snapshot'ı, `BillingProvider` SPI, webhook idempotency. |
| `FRONTEND-ARCHITECTURE.md` | Frontend yığını, provider zinciri, klasör yapısı, feature şablonu. |
| `QUALITY-GATES.md` | "Bitti"nin tanımı, coverage eşikleri, güvenlik ve performans kontrol listeleri. |
| `RELEASE-RUNBOOK.md` | Yayına çıkarma: config kontrol listesi, deploy adımları, rollback, olay müdahalesi. |
| `governance/ADR/` | Mimari kararlar ve gerekçeleri (MADR formatı). |
| `governance/RISK-REGISTER.md` | Bilinen açıklar ve devralınan kısıtlar. **Klonlarken önce buraya bak.** |
| `governance/AGENT-WORKING-AGREEMENT.md` | Çalışma disiplini: kanıtsız "tamamlandı" yok, checkpoint formatı, GO/NO-GO. |
| `governance/CHANGELOG.md` | Sürüm günlüğü. |
| `governance/QUALITY-GATES-RESULTS.md` | Kapı sonuçlarının kaydı. |
| `history/` | **Şablonun türetildiği göçün arşivi. Senin projeni bağlamaz** — bkz. `history/README.md`. |

## Ayrıca

- Repo kökündeki **`CLAUDE.md`** — her oturumda yüklenir; bu depoda gerçekten hataya yol açmış
  tuzakları ve konvansiyonları taşır.
- **`.claude/`** — ajan takımı (`tech-lead`, `backend-engineer`, `frontend-engineer`,
  `stack-reviewer`, `gate-auditor`), komutlar (`/new-module`, `/preflight`) ve skill'ler
  (`migration-safety`, `tenant-isolation`, `permission-model`).
- **`../scripts/ci-local.sh`** — CI kapılarını yerelde koşturur; push etmeden önce.
