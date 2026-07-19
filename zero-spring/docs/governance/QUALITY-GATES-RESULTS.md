# Kalite kapıları — sonuç kaydı

Eşikler `../QUALITY-GATES.md`'de. Bu dosya **ölçümleri** tutar.

> Şablonun kendi inşa sürecinin kayıtları `../history/QUALITY-GATES-RESULTS-template-build.md`
> altında arşivlendi.

---

## Devraldığınız temel — 2026-07-19 (Dalga 4 kapanışı)

| Kapı | Sonuç |
|---|---|
| Backend `clean verify` | **376 test** (252 IT + 124 unit), 0 fail / 0 error / 0 skip |
| Frontend build + test | **123 test** (24 dosya), typecheck dahil |
| JaCoCo coverage check | geçti |
| ArchUnit cırcırı | 5 kural aktif, donmuş ihlal **58 → 18** |
| Typed client drift | yok — controller imzaları ve DTO'lar değişmedi (bağımsız doğrulandı) |

### CI zinciri — `849e881`, workflow_dispatch, **7/7 yeşil**

"Geçti" bir ölçüm değil. Her kapının **vakum-yeşil olmadığını** gösteren log satırı:

| Gate | Vakum-yeşil riski | Log kanıtı |
|---|---|---|
| `build` | — | jar artifact'ı üretildi, sonraki 4 gate onu **yeniden derlemeden** kullandı |
| `backend` | bayat bytecode | `clean:3.4.1:clean` **koştu** · `124` unit + `252` IT · `All coverage checks have been met` |
| `frontend` | `test` typecheck yapmaz | `tsc -b && vite build` · `24 dosya / 123 test` |
| `typed-client-drift` | şema üretilmeden geçmek | `openapi-typescript → schema.generated.d.ts [739.9ms]`, sonra commit'liyle `diff -u` |
| **`migration-drift`** | `have_base=false` → **boş sette yeşil** | `oldset` = **V1..V6** · `applied 6 migrations, now at v6` · `validated 7 migrations` (drift yok) · **`applied 1 migration, now at v7`** — V7 **boş olmayan** şemaya uygulandı · `No migration necessary` (idempotent) |
| `live-smoke` | assertion koşmadan geçmek | **11 PASS**, içinde **5 negatif**: tenant mismatch `403` · tenant→subscriptions `403` · tenant→editions `403` · anonim `/me` `401` · bilinmeyen tenant `400` |
| `security-checks` | gitleaks `.git` bulamayıp hatayı yutmak | **`45 commits scanned`** · **`no leaks found`** · 5 desen PASS · `application-prod.yml secrets are env-referenced` · `npm audit: 0 vulnerabilities` — üçü de **blocking** |

### `release` — push koşulunda kanıtlandı (`3bd1113`, **8/8**)

İlk koşu `workflow_dispatch` ile yapıldığı için `release` **skipped** olmuştu
(`if: github.event_name == 'push' && ref == main`). Gerçek bir `push` ile tekrarlandı:

| Gate | Sonuç | Bu koşudaki kanıt |
|---|---|---|
| `build` … `security-checks` | 7/7 success | backend `254 IT + 124 unit`; `migration-drift` oldset = **V1..V7**, `applied 7 → v7`, `validated 7` (checksum drift yok), ikinci migrate `No migration necessary` |
| **`release`** | **success** | jar indirildi, step summary'ye commit / artifact adı / boyut / geçilen gate zinciri yazıldı |

> ⚠️ **`release: success` "deploy edildi" DEMEK DEĞİLDİR.** Job'ın kendi log satırı:
> *"Gerçek deploy adımı henüz bağlı değil (placeholder)."* Kanıtladığı şey, zincirin uçtan uca
> tamamlandığı ve artifact'ın hazır olduğudur — bir dağıtım değil. Gerçek deploy adımı
> bağlanana kadar bu satır bu şekilde okunmalıdır.

**İkinci koşunun `migration-drift`'i farklı bir şeyi kanıtladı:** önceki commit'te V7 zaten
vardı, yani oldset = V1..V7 ve mevcut set = V1..V7 → uygulanacak yeni migration yok. Bu, bir
öncekinden **daha zayıf değil, farklı** bir doğrulama: V7'nin checksum'ının **değişmediğini**
gösteriyor. İlk koşu "V7 dolu şemaya uygulanabiliyor mu", ikincisi "V7 uygulandıktan sonra
kararlı mı" sorusunu cevapladı.

**Lokal ↔ CI tutarlılığı:** backend 376/376 · frontend 123/123 · coverage geçti/geçti. **Sapma yok.**
Bu ölçüm önemli: bu depoda iki kez lokal-yeşil/CI-kırmızı yaşandı (mail health indicator, readiness
grubu) ve ikisi de ortama bağlı testlerdi.

### ArchUnit donmuş ihlaller

| Kural | Önce | Sonra | Not |
|---|---|---|---|
| R1 `@EntityGraph` + `Pageable` | 6 | **0** | Q-03; ayrıca `join fetch` şekli test profilinde Hibernate'e fırlattırılarak kapatıldı |
| R2 `tenant_id` var, `@Filter` yok | 3 | **0** | Q-04; `saas` ADR-0015 ile kural dışı |
| R3 ham izin literali | 31 | **0** | Q-02 |
| R4 `package-info` olmayan pakette entity | 12 | 12 | kapsam dışı — ayrı cırcır (R-37) |
| R5 korumasız controller handler | 6 | 6 | kapsam dışı |
| **Toplam** | **58** | **18** | |

Kalan 18 **karar**, başarısızlık değil: Dalga 4'ün kapsam kilidi R1/R2/R3'tü. Cırcır R4 ve R5'i
de tutuyor — büyüyemezler.

---

## Kayıt şablonu

```markdown
## <ne teslim edildi> — <YYYY-AA-GG>

| Kapı | Eşik | Sonuç | Durum |
|---|---|---|---|
| Backend `clean verify` | yeşil | ___ test (___ IT + ___ unit), 0 fail | |
| Frontend build + test | yeşil | ___ test | |
| Typed client senkron | drift yok | `gen:api` sonrası `git diff` boş | |
| ArchUnit donmuş sayı | artmamalı | ___ → ___ | |
| Negatif yetki testi | her yeni uçta | | |
| Kiracılar arası negatif test | çok kiracılı her uçta | | |
| Canlı smoke | gerekiyorsa | | |

**Kalan risk:** (yoksa "yok" yazın — boş bırakmayın)
```

## Kurallar

**"Geçti" bir ölçüm değildir.** Sayı yazın: kaç test koştu, kaç bulgu çıktı, kaç istek atıldı.

**`clean` zorunludur.** Maven, bir dosyanın zaman damgası geriye gittiğinde derlemeyi atlar ve
**bayat bytecode** test eder. Bu depoda hem yanlış yeşil hem yanlış kırmızı üretti.

**Yeşil ≠ doğrulandı.** Bir kapının geçmesi, bir şeyi kontrol ettiği anlamına gelmez. Bu depoda
ölçülmüş örnekler:

- Modül sınırı testi, `package-info` yazmayan modülü geçiriyordu.
- **Modulith, string ile çözülen bağımlılığı hiç görmüyor**: iki modülün entity'si başka bir
  modülde tanımlı bir filtreye bağlandı, `ModularityTests` yeşil kaldı (R-38).
- Migration kapısı boş bir sette yeşil dönüyordu.
- Secret taraması `.git` içermeyen bir dizini tarıyordu.
- Workflow dosyası repo kökünde olmadığı için hiç kaydedilmemişti.
- Bir test, var olmayan bir uca `403` assert ediyordu.
- Bir sayfalama sıra testi **artan** sıralamada geçiyordu; hatayı yalnızca **azalan** yakaladı,
  çünkü Postgres `in (...)` satırlarını o an tesadüfen doğru sırada döndürmüştü.

Şüphelendiğinizde: **korumayı bozun ve kapının kırmızıya döndüğünü görün.** `gate-auditor`
ajanı bunun içindir.

**Testin doğru sebeple yeşil olduğunu da doğrulayın.** Q-04'te bir izolasyon testi
`/api/audit-logs` üzerinden yazılsaydı, servis zaten açık bir tenant predicate'i taşıdığı için
filtre olsun olmasın geçerdi — eklenen korumayı hiç ölçmeden.

**Şema, izin ya da seed değişikliğinde canlı smoke zorunludur.** Temiz veritabanıyla koşan
testler "mevcut kurulum" hatalarını göremez. Bu turda iki örnek çıktı: kaldırılan `roles.manage`
izninin bayat satırları (V7 ile temizlendi) ve `UserNotification` üzerindeki yeni kiracı
filtresinin, sahibinin kiracısıyla uyuşmayan satırları gizleyebilmesi.
