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

## Dalga 5 — 2026-07-19

| Kapı | Sonuç |
|---|---|
| Backend `clean verify` | **389 test** (258 IT + 131 unit), 0 fail / 0 error / 0 skip |
| JaCoCo coverage check | geçti |
| ArchUnit cırcırı | 5 kural, donmuş ihlal **18 → 0** |
| Frontend | değişmedi (kapsam dışı, 123 test sabit) |
| Modül grafiği | **genişlemedi** — `ModularityTests` 1/1, `audit/package-info.java` diff'i boş |

### CI — `c3b7673` (dalga kapanışı), gerçek `push`, **8/8**

| Gate | Bu koşudaki kanıt |
|---|---|
| `backend` | **131 unit + 258 IT** · `All coverage checks have been met` |
| `migration-drift` | `applied 7 migrations, now at v7` · ikinci migrate **`No migration necessary`** (idempotent) |
| `live-smoke` | **11 PASS**, içinde **5 negatif** (tenant mismatch 403 · tenant→subscriptions 403 · tenant→editions 403 · anonim `/me` 401 · bilinmeyen tenant 400) |
| `security-checks` | **`51 commits scanned`** · **`no leaks found`** · 5 desen PASS · `npm audit: 0 vulnerabilities` |
| `release` | success (placeholder — deploy değil) |

**Lokal ↔ CI:** **389 = 389** (131 unit + 258 IT). **Sapma yok.**

### Önceki koşu — `2bedf66`, gerçek `push`, **8/8**

| Gate | Vakum-yeşil riski | Bu koşudaki log kanıtı |
|---|---|---|
| `build` | — | jar üretildi, sonraki 4 gate onu **yeniden derlemeden** kullandı |
| `backend` | bayat bytecode | `124` unit + `254` IT · `All coverage checks have been met` |
| `frontend` | `test` typecheck yapmaz | `tsc -b && vite build` · 24 dosya / 123 test |
| `typed-client-drift` | şema üretilmeden geçmek | `openapi-typescript` üretti, commit'liyle `diff -u` |
| `migration-drift` | boş sette yeşil | oldset **V1..V7** · `applied 7 → v7` · `validated 7` (checksum drift yok) · ikinci migrate `No migration necessary` (idempotent) |
| `live-smoke` | assertion koşmadan geçmek | **11 PASS**, içinde **5 negatif**: tenant mismatch 403 · tenant→subscriptions 403 · tenant→editions 403 · anonim `/me` 401 · bilinmeyen tenant 400 |
| `security-checks` | gitleaks `.git` bulamayıp hatayı yutmak | **`49 commits scanned`** · **`no leaks found`** · 5 desen PASS · `npm audit: 0 vulnerabilities` |
| `release` | `workflow_dispatch`'te **skipped** olur | gerçek `push`'ta **success**, `dist/app.jar` hazır. ⚠️ **"deploy edildi" DEMEK DEĞİL** — job'ın kendi satırı: *"Gerçek deploy adımı henüz bağlı değil (placeholder)."* |

**Lokal ↔ CI:** `2bedf66` anında backend **378 = 378** (124 unit + 254 IT). **Sapma yok.**

### W5-3 — bir davranış testinin göremediği şey

W5-3'ün ilk turu yeşildi ve **yanlış sebeple** yeşildi. Gate auditor'ın mutasyonu:
`Pageable`'ı yok sayan, tüm satırları okuyup sınırı **Java'da** uygulayan bir fetcher.
Dışarıdan **birebir aynı**: limitte 200, bir üstünde 400.

```
ExportsAreBoundedTest  Tests run: 2, Failures: 0
ExportRowBoundIT       Tests run: 2, Failures: 0
BUILD SUCCESS
```

**Dört testin dördü de yeşil kaldı.** Ret davranışı kilitliydi; görevin var olma nedeni olan
**tahsis** davranışı değil. `PagedListingIsNotSlicedInMemoryIT` de göremezdi — koleksiyon fetch
olmadığı için `HHH90003004` hiç yayınlanmaz.

Kapatan şey: `org.hibernate.SQL`'e `ListAppender` bağlayıp sorgunun satır limiti taşıdığını assert
etmek. Eşleşen gerçek SQL:

```
select u1_0.id from users u1_0 where u1_0.tenant_id = ? and (u1_0.deleted = false)
  and u1_0.tenant_id=? order by u1_0.id fetch first ? rows only
```

Mutation tekrar koşuldu: **her iki export'ta ayrı ayrı RED**, iki sınır testi **yeşil kaldı** —
yani yeni testler tek yük taşıyıcı. Assertion'ın önünde vacuity guard var: sıfır statement
yakalanırsa test *"aşağıdaki assertion hiçbir şeyi belgelemezdi"* diyerek kendini düşürür.

**Kalan (papered over edilmedi):** assertion *"bir limit var"* der, *"limit tam olarak `maxRows+1`"*
demez — `org.hibernate.SQL` bind parametrelerini basmaz. Farklı bir limit uygulayan fetcher geçer.
R-41'e kaydedildi.

### Reddedilen "düzeltme" — kısmi guard'ı fix diye raporlamak

Stack reviewer `BoundedExport.fetch` içine `rows.size() > maxRows+1` guard'ı önerdi. **Eklendi ama
fix sayılmadı:** yalnız veri kümesi `maxRows+1`'den büyükse ateşlenir. IT'de (limit 5, 6 satır)
yok sayan fetcher tam 6 = `maxRows+1` döner ve guard **sessiz kalır** — mutation 6'yı yakalamazdı.
Ölçüldü, öyle davrandı. Javadoc'u bunu söylüyor ve SQL testlerini işaret ediyor.

### Kapsam ihlali — bulundu ve geri alındı

İlk tur `BoundedExport`'u `config`'e koydu, bu da `audit`'i
`allowedDependencies = {"shared"}` → `{"shared", "config"}` genişletmeye zorladı. Yasak listesinde
*"mimari genişletme yok"* yazıyor. Gerekçe olarak yazılan *"`shared`'a konulamazdı, döngü olurdu"*
iddiası da **yanlıştı**: döngü yalnızca sınıf `config`'te kalırsa var; her ikisi de `shared`'a
taşınınca `DomainException`/`ErrorCode` zaten orada olduğu için döngü yok. Taşındı, `audit`
geri alındı, `ModularityTests` yeşil.

---

## Dalga sonrası sabitleme — 2026-07-20, `c59cd5a`, gerçek `push`, **8/8** (run 29704567348)

Kapanış (`c3b7673`) sonrası iki commit daha koştu: `f879a63` (anonim maruziyet artık build'in
görebildiği bir şey — `FilterChainReachabilityIT` + `@EndpointPolicy` iki yönlü mutabakatı) ve
`c59cd5a` (yönetişim kaydı). Backend test sayısı bu yüzden 389 → **410**.

| Kapı | Bu koşudaki kanıt |
|---|---|
| `backend` | **138 unit + 272 IT = 410**, 0 fail / 0 error / 0 skip · `All coverage checks have been met` |
| `frontend` | 24 dosya / **123 test** |
| `build` · `typed-client-drift` · `migration-drift` · `live-smoke` · `security-checks` · `release` | 6/6 success (API üzerinden job sonuçları ölçüldü). **Log seviyesinde kanıt bu koşu için yalnız backend/frontend'den çekildi**; diğer kapıların vakum-yeşil kanıtı `c3b7673` koşusundan devralınıyor — o koşudan bu yana ilgili girdiler (migration seti, smoke betiği, gitleaks konfigi) değişmedi. `release` hâlâ placeholder, deploy değil |

**Lokal ↔ CI (2026-07-20, HEAD `c59cd5a`):** backend **410 = 410** (138 unit + 272 IT),
`BUILD SUCCESS`, coverage geçti/geçti. **Sapma yok.**

---

## Billing dilimi P2-A (Stripe webhook + idempotency) — 2026-07-20, `678bfd9`, gerçek `push`, **8/8** (run 29737933182)

| Kapı | Eşik | Sonuç | Durum |
|---|---|---|---|
| Backend `clean verify` | yeşil | **428 test (145 unit + 283 IT)**, 0 fail / 0 skip | ✅ |
| Frontend build + test | yeşil | 123 test + `schema.d.ts` yeniden üretildi (+95 satır, yalnız billing yüzeyi) | ✅ |
| Typed client senkron | drift yok | `typed-client-drift` success | ✅ |
| `migration-drift` | boş sette yeşil riski | oldset **V1..V7** üzerine V8 uygulandı (mevcut-kurulum simülasyonu); backend job'da `Successfully validated 8 migrations` | ✅ |
| Negatif yetki testi | her yeni uçta | checkout: tenant admin → **403** + payment satırı yok; anonim → 401 | ✅ |
| Canlı smoke | şema değişikliğinde zorunlu | `live-smoke` 11 PASS + dev boot: V8 uygulandı, readiness UP, billing-kapalı webhook → 404 | ✅ |

**Lokal ↔ CI:** **428 = 428** (145 + 283). **Sapma yok.**

**Negatif kanıtlar (mutasyonla, ikisi de kayıtlı):** (1) dedup `on conflict do nothing` kaldırıldı →
duplicate-teslimat testi **kırmızı** (409, beklenen 200) — kaynak sistemin "duplicate → 400 → sonsuz
retry" bug'ının kapandığının kanıtı. (2) işleme istisnası yutulup 200 dönüldü → rollback testi
**kırmızı** (200, beklenen 500) — "başarısız işleme dedup satırını geri sarar, retry temiz işler"
iddiasının kanıtı.

**Stack-review:** 3 bulgu (PROD-R36 yanlış sınıflandırma; rollback iddiasının negatif kanıtı yok;
farklı-event-id yarışı) — üçü de commit'ten **önce** kapatıldı. 7 alan temiz raporlandı.

**Kalan risk:** PROD-R36..R40 (RISK-REGISTER) — en önemlisi: canlı Stripe session çağrısı hiç
koşmadı (SPI arkasında, sandbox smoke bekliyor) ve 413 gövde-sınırı modu kalıcı-izsiz kayıp
(runbook §3.9 mutabakatı ara önlem).

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
