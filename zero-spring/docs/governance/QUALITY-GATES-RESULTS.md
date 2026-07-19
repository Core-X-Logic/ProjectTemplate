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
