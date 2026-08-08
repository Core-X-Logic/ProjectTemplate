# Kalite kapıları — sonuç kaydı

Eşikler `../QUALITY-GATES.md`'de. Bu dosya **ölçümleri** tutar.

> Şablonun kendi inşa sürecinin kayıtları `../history/QUALITY-GATES-RESULTS-template-build.md`
> altında arşivlendi.

---

## R-44 kapanışı + kullanıcı daveti dilimi — 2026-08-08 (scoped ölçüm)

| Kapı | Sonuç |
|---|---|
| Backend scoped `verify` (`-Dit.test=InvitationFlowIT,PasswordPolicyIT,SecurityPathBindingIT,SubscriptionExemptPathBindingIT,RlsCoverageIT`) | **230 unit + 31 IT** (InvitationFlowIT 9 · PasswordPolicyIT 8 · SecurityPathBindingIT 6 · SubscriptionExemptPathBindingIT 4 · RlsCoverageIT 4), 0 fail / 0 error / 0 skip · `BUILD SUCCESS` |
| Frontend `tsc -b` + `eslint` (etkilenen 17 dosya) + `vitest` (account + users testleri) | typecheck + lint temiz · **30 test / 5 dosya** yeşil (invite-dialog 4, accept-invitation 6, reset-password 6 — yenileri dâhil) |
| Negatif kanıt (R-44 expired) | `AccountService.isUsable` süre kontrolü kasten devre dışı bırakıldığında `PasswordPolicyIT`'nin iki expired testi **KIRMIZI**: `[an expired reset code must be refused …] expected: 400 BAD_REQUEST but was: 204 NO_CONTENT` (confirmation'da aynı desen); geri alınınca 2/2 yeşil — testler gerçekten süre koşulunu ölçüyor |

> ⚠️ TAM `clean verify` bu dilimde koşulmadı (ana süreçte koşulacak); yukarıdaki sayılar scoped
> koşunun ölçümüdür.

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

## PayTR dilimi P2'-A (çoklu-sağlayıcı + PayTR intake) — 2026-07-20, `ea0e13f`, gerçek `push`, **8/8** (run 29743681191)

> Kapsam kararı ADR-0017: güncel sağlayıcılar **PayTR + iyzico**; Stripe uyuyan global-pazar
> adaptörü (bu dilimde sıfır Stripe işi — beş sınıfı ve IT'leri dokunulmamış); PayPal dışarıda.

| Kapı | Eşik | Sonuç | Durum |
|---|---|---|---|
| Backend `clean verify` | yeşil | **461 test (168 unit + 293 IT)**, 0 fail / 0 skip | ✅ |
| Frontend | değişiklik yalnız üretilmiş `schema.d.ts` | build + 123/123 | ✅ |
| Typed client senkron | drift yok | `typed-client-drift` success | ✅ |
| Negatif yetki | her yeni uçta | paytr webhook: geçersiz hash → 400 + 0 satır; disabled → 404 | ✅ |
| Canlı smoke | — | `live-smoke` 11 PASS | ✅ |

**Lokal ↔ CI:** **461 = 461** (168 + 293). **Sapma yok.**

**Altı mutasyon kanıtı (hepsi koşuldu-kırmızı-geri alındı):** dedup silme → 409≠200 · hash atlama →
200≠400 · ack `"ok\n"` → byte-eşit `OK` testi kırmızı (tahsilat sözleşmesi) · PAID koruması →
FAILED≠PAID · failed→success aktivasyonu geri alınınca → PAID bekleyen test kırmızı · per-pair
form parse eski kodda → 200≠429. Ek: registry mutasyonu ilk koşuda **bayat bytecode yüzünden
yanlış yeşil** kaldı — CLAUDE.md tuzağı bire bir; `test-compile` ile tekrarlanıp kırmızı görüldü.

**Stack-review:** 1 orta-yüksek (failed→success meşru sırada para tahsil edilip aktivasyon
kaçıyordu — üç katmanda kapatıldı: durum makinesi + IT + runbook SQL'i `NOT_PAID,FAILED`),
2 düşük (deterministik `+`'lı hash vektörü `ZPPLUSVEC00`; form parser uyumu) — üçü de commit'ten
**önce** kapatıldı.

**Kalan risk:** PROD-R41..R44 — PayTR retry takvimi belgesiz (§3.9 mutabakat ağı genişletildi);
`OK` sözleşmesi kırılgan (byte-eşit test nöbette); iyzico + sorguyla-mutabakat sonraki dilim;
get-token alıcı alanları placeholder, ilk canlı smoke ölçecek. Canlı PayTR çağrısı **hiç koşmadı**.

---

## P2'-B iyzico + Issue #1 + tenant dialog — 2026-07-20, `7914373`, gerçek `push`, **8/8** (run 29762445008)

Dört commit tek push'ta: `ef82ef0` (iyzico + mutabakat), `20247d5` (tenant bootstrap, worktree'den
cherry-pick), `b1297c9` (gitlink temizliği), `7914373` (dialog + F4/F5 sertleştirme). Ara adımda
`schema.d.ts` bir commit erken landığı için (F2 bulgusu) push **birlikte** yapıldı; tekrarlanmayacak.

| Kapı | Sonuç |
|---|---|
| Backend `clean verify` | **502 test (186 unit + 316 IT)**, 0 fail / 0 skip |
| Frontend | **129 test** (24→25 dosya), build + typecheck yeşil |
| `migration-drift` | V9 dahil; dev boot V9'u **gerçek V8 kurulumuna** uyguladı (mevcut-kurulum kuralı) |
| Diğer 5 job | success (`release` = placeholder) |

**Lokal ↔ CI:** **502 = 502** (186 + 316) · frontend **129 = 129**. **Sapma yok.**

**Negatif kanıtlar:** iyzico — imza-atla, dedup-sil, huni-atla (payload'la aktivasyon) üçü de
mutasyonla kırmızı; **HIGH yarış bulgusu** `[CONFIRMED_ACTIVATED, CONFIRMED_ACTIVATED]` olarak
önce kırmızı ölçüldü, scalar-peek düzeltmesiyle kazanan + `ALREADY_PAID`'e döndü. Issue #1 —
çekirdek IT eski kodda `expected: 200 OK but was: 401 UNAUTHORIZED`; dialog suite'i düzeltilmemiş
UI'da 6/6 kırmızı. **Canlı smoke (Issue #1 kapanış barı):** create 201(+tek-seferlik parola) →
tenant login 200 → `/api/users` 200 → yanlış tenant 403 → re-boot → aynı parola 200.

**Stack-review:** iyzico 4 bulgu (1 HIGH dahil) + Issue #1 5 bulgu (F1 frontend, F4/F5 sertleştirme)
— hepsi push **öncesi** kapatıldı; iki review da temiz alanları isimlendirdi.

**Kalan risk:** PROD-R44/R47 (sandbox canlı çağrılar — **operatör-bağımlı**: merchant hesapları),
PROD-R45/R46/R48, checkout UI kesildi (sözleşme §5), PROD-R36..R42 önceki kayıtlar.

---

## Checkout UI + sandbox harness — 2026-07-20/21, `a750227`, gerçek `push`, **8/8** (run 29778953412)

| Kapı | Sonuç |
|---|---|
| Backend | **502** (186 + 316) — değişmedi, UI-only push; CI yine tam koştu |
| Frontend | **135 test / 27 dosya** (lokalde ölçüldü; CI frontend job success), `tsc -b` + build yeşil |
| Davranış kanıtı | Submit gövdesi 6 alanla assert'li; provider radyosu `provider`'ı değiştiriyor; 400'de dialog açık kalıp detail gösteriyor; sonuç sayfası **"activated" DEMEZ** (negatif assert: `queryByText(/activated/i)` boş) — redirect'in hiçbir şey kanıtlamadığı sözleşmesi UI'da da kilitli |
| RBAC | "Pay & assign" yalnız `subscriptions.manage` ile görünür (testli); sonuç rotaları bilinçli izinsiz (gerekçe rotada yorum olarak) |

**Kalan tek açık kapanış kalemi:** sandbox canlı smoke — `scripts/sandbox-smoke.sh` + runbook
§3.10 hazır; **PayTR mağaza + iyzico sandbox merchant kimlik bilgileri operatörden bekleniyor.**
Script PASS'i buraya işlenip PROD-R44/R47 kapatılana kadar TR ödeme fazı COMPLETE değildir.

---

## PayTR token formülü — PayTR'nin resmî aracıyla harici doğrulama, 2026-07-21

Canlı get-token/bildirim/aktivasyon **koşulamadı**: PayTR paylaşılan test kimlik bilgisi yayınlamıyor
(resmî Postman env `XXXXXX`; `test_mode=1` gerçek mağaza hesabına biniyor) — kredensiyalsiz canlı
çağrı imkânsız (kaynak: dev.paytr.com sayfaları, oturum içinde fetch edildi). Bu yüzden **yapılabilecek
en güçlü kredensiyalsiz kanıt** üretildi: token formülünün PayTR'nin **kendi** hesaplayıcısına karşı
çapraz-kontrolü — kendine-tutarlılık değil, harici oracle.

| Adım | Sonuç |
|---|---|
| Araç | `dev.paytr.com/servis-test-araclari/hash-hesaplama`, **iFrame API** sekmesi (PayTR'ın kendi kodu) |
| Girdi | `PayTRTokenRequestTest`'in pinlediği birebir vektör (merchant_id=123456, oid=ZP42TESTOID01, amount=999 kuruş, user_basket=`W1siUHJvIChNT05USExZKSIsIjkuOTkiLDFdXQ==`, currency=TL, test_mode=1, salt/key=test-*) |
| PayTR aracı çıktısı | `G0IZ3V/qo38nReuI/yukiPXL0LAjzu/1WtEbFX7h7nQ=` |
| Bizim `checkoutToken(...)` (commit'li beklenti) | `G0IZ3V/qo38nReuI/yukiPXL0LAjzu/1WtEbFX7h7nQ=` |
| Karşılaştırma | **byte-birebir EŞİT** |

Kapsam: HMAC dizilimi + salt-sonda konumu + `user_basket` base64 serileştirmesi PayTR'ın beklediğiyle
aynı → **hash artık canlı bir başarısızlık nedeni değil** (PROD-R44a kapandı). **Doğrulanmayan (açık):**
canlı ağ round-trip'i, gerçek bildirim teslimi, gerçek aktivasyon, alıcı/adres alanları — tümü
operatör mağaza hesabına bağlı (PROD-R44b). Bildirim-hash'i (webhook tarafı) bu aracın kapsamında
DEĞİL — yalnız `PayTRWebhookIT`'in gerçek-imzalı offline testleriyle doğrulanıyor. **iyzico canlı:
sonraki faza ertelendi (PROD-R47).**

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

---

## Release hardening — 2026-07-21, `03d3315`, gerçek `push`, **8/8** (run 29822177991)

Kod/işlev değişikliği yok; yalnız CI/config uyarı temizliği. Kanıt:
[run 29822177991](https://github.com/Core-X-Logic/ProjectTemplate/actions/runs/29822177991).

| Metrik | Hedef | Ölçülen | Kanıt |
|---|---|---|---|
| Node 20 deprecation satırı (tam log) | 0 | **0** | `grep -ci "Node 20 is being deprecated\|Node.js 20 is deprecated"` → 0 (önceki run 29820420095'te her job'da vardı) |
| Frontend build warning | 0 | **0** | `tsc -b && vite build` exit 0, `grep -c warn` 0; eski `@media var()` uyarısı kalktı |
| Frontend test | pass | **140/140** (28 dosya) | `frontend` job: `Tests 140 passed (140)` |
| Backend test | pass | **502** (186 unit + 316 IT), 0 fail/error/skip | `backend` job: `Tests run: 186…` + `316…` |
| CI zinciri | 8/8 | **8/8** | build · backend · frontend · typed-client-drift · migration-drift · live-smoke · security-checks · release |

**Artifact handoff (upload-artifact@v7 → download-artifact@v8) — digest eşleşmeli:**
`build` job `backend-jar` (ID **8491827278**, 109 247 020 bytes) yükledi; `typed-client-drift`,
`migration-drift`, `live-smoke`, `release` job'ları aynı ID'yi indirdi ve **dördünde de
`sha256:f429bcb70a0a…955992f` birebir eşleşti** → aynı jar, v7→v8 major sınırından bozulmadan
geçti; sequential gate mantığı korundu.

**Değişen:** ci.yml action'ları (checkout v4→v7, setup-node v4→v7, setup-java v4→v5,
upload-artifact v4→v7, download-artifact v4→v8; yalnız version tag'i) · `tsconfig.app.json`
(baseUrl kaldırıldı, paths TS5+'ta göreli çözülür) · `scrollable.css` (`var(--breakpoint-lg)` →
`64rem`, media-feature'da CSS var geçersizdi). **Davranış değişmedi** (build 0 warning, 502+140 test yeşil).

---

## Foundation hardening — Docker gate + deploy scaffold — 2026-07-21, `77a9fa9`, gerçek `push`, **9/9** (run 29831107658)

Uygulama/domain/API/permission değişikliği yok; yalnız CI/CD + deploy scaffold. Amaç: template'i
klonlayan her ekip için **container güveni** (imaj gerçekten build oluyor + sertleştirmesi doğrulanıyor)
ve **deploy edilebilirlik** (placeholder yerine parametrik iskelet). Kanıt:
[run 29831107658](https://github.com/Core-X-Logic/ProjectTemplate/actions/runs/29831107658).

| Gate | Amaç | Bu koşudaki kanıt |
|---|---|---|
| **`docker-build`** (YENİ) | PROD-R27: imaj hiçbir kapıda build/doğrulanmıyordu | buildx ile backend imajı build edildi (GHA layer cache); `docker image inspect` ile **dört sertleştirme assert edildi** → log: *"Image hardening doğrulandı: non-root · healthcheck · prod profil · heap tavanı."* Push YOK |
| **`release`** (scaffold) | Placeholder → parametrik deploy | `needs: [security-checks, docker-build]`; "Deploy plan (dry-run)" env/image-ref/secret noktalarını yazdı; guarded adım *"DEPLOY_ENABLED != true → scaffold no-op … Deploy KOŞMADI"* — **gerçek deploy koşmadı**, güvenli |

**Zincir 8 → 9 job**, tamamı success: build · **docker-build** · frontend · backend · typed-client-drift ·
migration-drift · live-smoke · security-checks · release. Mevcut artifact zinciri ve `needs:` sırası
korundu; `docker-build` **paralel** (`needs: build`), release ikisini birden bekliyor → **bozuk/sertleşmemiş
imaj release'i bloklar** (kabul kriteri 2, needs grafiğiyle garantili).

**Negatif taraf (tasarımla):** `docker-build` başarısız olursa release job'ı hiç tetiklenmez (`needs`).
**Lokal ön-doğrulama:** imaj lokalde de build edildi ve dört assert (User=zero, Healthcheck=yes,
prod-profile=OK, heap=OK) **PASS** — CI'dan önce ölçüldü.

---

## PROD-R6 — dağıtık (Redis-backed) rate limit — 2026-07-21, `da1c207`, gerçek `push`, **9/9** (run 29841476694)

Foundation hardening; API/auth/tenant/permission değişikliği yok. Rate-limit bucket store'u
JVM-local ConcurrentHashMap'ten Redis'e taşındı (bucket4j-redis 8.10.1, Spring'in Lettuce'u
yeniden kullanılır) → N replikada N×limit sapması kapandı. Kanıt:
[run 29841476694](https://github.com/Core-X-Logic/ProjectTemplate/actions/runs/29841476694).

| Kapı | Bu koşudaki kanıt |
|---|---|
| `backend` | **190 unit + 322 IT = 512**, 0 fail/error/skip |
| `DistributedRateLimitIT` (Testcontainers Redis) | 3/3 — iki "replika" tek Redis'i paylaşınca **paylaşık limit 5**; eski iki-heap-bucket store **2×=10** sızdırıyor (PROD-R6 negatif kanıtı, in-test asserted) |
| `DistributedRateLimitWiringIT` (SpringBootTest + Redis, 6.2s = gerçek konteyner) | 3/3 — sayaç `zero:rl:` anahtarlarında Redis'te **ve** heap map boş (dağıtık, vakum-local değil); forged leading `X-Forwarded-For` gerçek istemciye yazılıyor |
| `RateLimitDegradeTest` | 4/4 — Redis kesintisi → **429@capacity+1 (fail-open değil), 503 yok (fail-closed değil)**, dedup WARN |
| Diğer 8 job (build · frontend · docker-build · typed-client-drift · migration-drift · live-smoke · security-checks · release) | success |

**Negatif kanıt (mutasyon):** `tryConsume` catch'i fail-open yapıldı → `RateLimitDegradeTest`
kırmızı: `expected: 429 but was: 200`; geri alındı, yeşil. Dağıtık sızıntı karşıtlığı (5 vs 10)
`DistributedRateLimitIT`'te in-test.

**Degrade policy (bilinçli, testli):** Redis-primary; herhangi bir Redis hatası → per-instance
local bucket (eski davranış). Asla fail-open-sınırsız, asla fail-closed-503. Tek `Bandwidth`
tanımı iki yolu da besler (divergence yok). Redis readiness grubunda değil (PROD-R13 gerekçesi);
lazy proxy manager → Redis kapalıyken boot ayakta. dependency:tree: yalnız Spring'in Lettuce'u
(jedis/redisson sızmıyor).

**Stack-review:** güvenlik-odaklı; degrade tasarımı sağlam, iki arıza modu da önlenmiş, 6 alan
temiz; 2 LOW commit öncesi kapatıldı (tek-kaynak Bandwidth; `@ConditionalOnBean` ölçülerek
feature'ı kırdığı görülüp doc düzeltmesine dönüldü).

**Kalan:** `X-Forwarded-For` proxy varsayımı — proxy başlığı **ezmeli**, kodla garanti edilemez
(operasyonel, RISK-REGISTER PROD-R6 + runbook).

---

## 2FA (TOTP + kurtarma kodları) — foundation identity hardening — 2026-07-21, **9/9**

Backend `91ed0a1` + frontend `4d48d36` + gitleaks allowlist `94645bc`. Son yeşil koşu:
[run 29853078314](https://github.com/Core-X-Logic/ProjectTemplate/actions/runs/29853078314) — **9/9 success**.

| Kapı | Kanıt |
|---|---|
| `backend` | **202 unit + 345 IT = 547** (512→547, +35 2FA); V10 uygulandı (`migration-drift` yeşil) |
| `frontend` | **147 test** (135→147, +12); login second-step + profil 2FA kartı; `qrcode.react` QR |
| `typed-client-drift` | yeşil — `schema.d.ts` yeniden üretildi, API yüzeyiyle senkron |
| `security-checks` | yeşil — gitleaks **no leaks** (field-key allowlist), `npm audit` 0 |
| `docker-build`/`live-smoke`/`release` | success |

**Akış (fail-closed):** `login` şifre-OK + 2FA-açık → **token BASILMAZ**, kısa-ömürlü tek-kullanımlık
attempt-limitli challenge → `/api/auth/two-factor/verify` (TOTP veya kurtarma kodu) geçince
`issueTokenPair`. Her hata = jenerik 401 (oracle yok). Non-2FA login birebir aynı; `refresh` re-gate
edilmedi. TOTP secret AES-256-GCM şifreli (per-encryption rastgele IV); kurtarma kodları BCrypt(12)
hashli, tek-kullanım.

**Negatif kanıtlar (mutasyon):** (1) `login`'deki 2FA kapısı kaldırıldı → 2FA kullanıcısı token
alıyor: `Expecting true but was false`. (2) HIGH review: birinci-faktör başarısı lockout sayacını
sıfırlıyordu → sınırsız TOTP brute-force; sayaç sıfırlaması yalnız tam-kimlik başarısına taşındı,
interleaved-relogin testi eski kodda kilitlenmiyor. (3) MEDIUM review: challenge/kurtarma consume
+ attempts decrement TOCTOU → `PESSIMISTIC_WRITE` lookup + guarded UPDATE; concurrency IT double-spend
(`1 beklenirken 2`) ve lost-update (`4 beklenirken 1`) eski kodda kırmızı.

**Stack-review (güvenlik):** fail-closed gate, AES-GCM (nonce reuse yok), tenant_id sapması (SOUND —
challenge user_id/256-bit hash ile çözülür, token tenant User'dan otoriter), migration, secret handling
6 alan temiz; 2 gerçek bulgu (HIGH+MEDIUM) commit öncesi kapatıldı.

**gitleaks dersi (CLAUDE.md):** 2FA'nın dev/test field-key'leri (self-documenting `not-in-prod`/
`never-deploy`, `FieldEncryptionKeyValidator` prod'da reddediyor) allowlist'e eklendi. Etki **bulgu
sayısıyla ölçüldü**: allowlist'i yazarken bir base64 typo'su mevcut bir JWT girdisini sessizce bozdu,
4→0 yerine 4→1 ölçümü yakaladı; düzeltildi → 0.

**Kalan:** PROD-R49 (field-key rotasyon/re-encrypt + KMS), R51 (kurtarma UX + SMS/WebAuthn/QR — sonraki
faz), R52 (admin 2FA-reset ucu yok — self-lock). *(Takip `/me` `twoFactorEnabled` yansıtması
`fc67dfe`'de KAPANDI — aşağıya bakın.)*

---

## Mini-hardening — `/me` twoFactorEnabled yansıması — 2026-07-21, `fc67dfe`, **9/9** (run 29856589951)

2FA diliminin küçük takip maddesi: profil 2FA kartı mevcut durumu backend'den okuyamıyordu (heuristic
kullanıyordu). `MeDto`'ya `twoFactorEnabled` eklendi (`AuthService.me` → `user.isTwoFactorEnabled()`),
typed client yeniden üretildi, kart artık **yalnız** `user.twoFactorEnabled`'dan render ediliyor
(kapalı → yalnız enable akışı; açık → yalnız manage). Davranış değişikliği yok; yalnız görünürlük.

| Kapı | Kanıt |
|---|---|
| `backend` | **549** (202u + 347IT, +2 me-flag IT); additive alan, mevcut /me tüketicileri (AuthFlowIT/JwtAudienceIT/ImpersonationIT/MeShouldChangePasswordIT) değişmeden yeşil |
| `frontend` | **149** (+2 state-driven kart testi); heuristic + `idleHint`/`manageExisting` anahtarları kaldırıldı |
| `typed-client-drift` | **yeşil** — backend + `schema.d.ts` **birlikte** landing (geçen dilimin dersi uygulandı, ara drift yok) |
| Diğer 6 job | success |

**Negatif kanıt:** true-case IT eski (alan-yok) kodda kırmızı — `Expecting true but was false` (Jackson eksik alanı `false`'a düşürüyor). Frontend "açıkken yalnız manage" testi eski kartta kırmızı (Disable butonu yok).

---

## PROD-R16 — JWT key rotation (kid key-ring) + token revocation — 2026-07-21/22, **9/9**

Backend `42ef088` + live-smoke Redis servisi `00a52ad`. Son yeşil koşu:
[run 29867700291](https://github.com/Core-X-Logic/ProjectTemplate/actions/runs/29867700291) — **9/9 success**.
Security-critical; API non-breaking (kid = JWT header, jti = additive claim; HS512 korundu).

| Kapı | Kanıt |
|---|---|
| `backend` | **219 unit + 362 IT = 581** (549→581, +32) |
| `KeyRotationIT` | active-key geçer · previous(grace)-key geçer · unknown/retired kid **red** · no-kid → legacy fallback (rolling-deploy) · **alg-confusion**: `none`/`HS256`-downgrade/`RS256` hepsi red |
| `TokenRevocationIT` (Testcontainers Redis) | jti revoke → aynı token 401 · `revokeAllForUser`(notBefore) → eski token 401, yeni token geçer · logout access jti'yi revoke eder · şifre değişimi + 2FA disable outstanding token'ları revoke eder |
| `TokenRevocationDegradeIT` | Redis erişilemez → authenticated istek **401 (fail-closed)**, allow DEĞİL |
| `RevocationWiringTest` | `enabled=true` + servis yok → decoder **boot'u reddeder** (enabled⟹enforced fail-fast) |
| `live-smoke` | Redis servisi eklendi (aşağı bak) → authenticated smoke geçer (`/me` 401 negatif dâhil) |
| Diğer 5 job | success |

**Negatif kanıtlar (mutasyon):** (1) `isRevoked→false` → `TokenRevocationIT` **5/6 kırmızı** (`401 beklenirken 200`). (2) enabled-but-unenforced → `RevocationWiringTest` kırmızı (`Expecting code to raise a throwable`). (3) HS512 pin silindi → `KeyRotationIT` HS256-downgrade kırmızı (`Tests run: 8, Failures: 1`; `none`/`RS256` bağımsız reddediliyor — pin **downgrade sınıfı** için tekil taşıyıcı, dürüst not).

**Fail-closed (bilinçli, testli):** Redis revocation'a ulaşılamazsa token **reddedilir** — fail-open YASAK; rate-limit'in aksine local fallback yok (neyin revoke olduğunu store'suz bilemezsin). Redis readiness grubunda değil.

**live-smoke öğrenmesi:** revocation fail-closed olunca Redis **auth için sert bağımlılık** oldu — Redis'siz jar authenticated her isteği 401'ler. live-smoke yalnız Postgres taşıyordu → authenticated assertion'lar düştü. Doğru fix: smoke ortamına Redis eklendi (güvenlik kontrolünü zayıflatmak değil, ortamı gerçek deploy'a uydurmak; deploy zaten Redis şart). Trafik kapısı bilinçle hâlâ `/readiness`.

**Stack-review (güvenlik):** fail-closed / alg-pinning / TTL matematiği rijitçe temiz doğrulandı; 2 bulgu (F1 enabled⟹enforced fail-fast, F2 alg-confusion testi) commit öncesi kapatıldı; F3 (aynı-saniye iat granülaritesi) + F4 (write best-effort vs read fail-closed asimetrisi) RISK-REGISTER'a dokümante.

**Kalan (RISK-REGISTER PROD-R16 residual):** Redis kesintisi auth'u reddeder (fail-closed trade; access token kısa, operatör Redis HA); asimetrik JWKS + iki granülarite limiti kayıtlı, blocker değil. Rotasyon prosedürü: RELEASE-RUNBOOK §1.3-K.

---

## PROD-R16 F3 + F4 daraltması — 2026-07-22, `8c1cd42`, **9/9** (run 29904735484)

JWT revocation'ın iki kayıtlı residual'ı daraltıldı; yeni feature yok, API non-breaking (`ims`
opaque JWT içinde). Kanıt: [run 29904735484](https://github.com/Core-X-Logic/ProjectTemplate/actions/runs/29904735484).

| Konu | Eski → yeni |
|---|---|
| **F3** aynı-saniye `iat` granülaritesi | notBefore artık **millis**; access token'a additive `ims` (issued-millis) claim'i; karşılaştırma ms çözünürlükte. Aynı-saniye ama-önce basılan token (`ims < notBefore`) artık **revoke**, sonraki re-login (`ims > notBefore`) hayatta — pencere 1sn → saat çözünürlüğü, **login-loop yok** (strict `<`). Legacy (ims'siz) token → `iat+999ms` = pre-F3 saniye davranışı → deploy-window loop da yok, kendini iyileştirir |
| **F4** revocation-write fail-open asimetrisi | Redis write **3 denemeli bounded retry** (50/100ms, ~150ms en kötü), tükenince throw etmez (credential-change DB-committed), Micrometer counter `jwt.revocation.write_failures` (operation tag) + greppable WARN `REVOCATION_WRITE_FAILED` (jti/token yok). **Read yolu değişmedi, hâlâ fail-closed** |

| Kapı | Kanıt |
|---|---|
| `backend` | **222 unit + 364 IT = 586** (581→586, +5) |
| `TokenRevocationSubSecondIT` | aynı-saniye token revoke + sonraki re-login hayatta (loop yok); **legacy no-ims token** aynı-saniye re-login hayatta (deploy-window loop yok), önceki-saniye legacy revoke |
| `TokenRevocationWriteRetryTest` | transient write → retry ile kaydedilir; sürekli hata → counter++ + WARN, throw yok; access-token write ayrı tag'li, jti loglamaz |
| Diğer 6 job | success (live-smoke Redis'li) |

**Üç mutasyon kanıtı:** saniyeye çökert → SubSecond kırmızı (401≠200); write no-op → revoke IT kırmızı; `+999` fallback geri alındı → legacy no-ims IT kırmızı (`200 beklenirken 401`).

**Stack-review (güvenlik):** steady-state sıfır loop + `asMillis` 1e11 eşiği (yıl ~5138'e dek net) doğrulandı; 1 bulgu (legacy no-ims deploy-window loop) commit öncesi kapatıldı (fallback `+999`), 2 doküman düzeltmesi (150ms; geri-NTP-step ms hassasiyeti).

**Kalan (RISK-REGISTER PROD-R16 residual):** canlı çok-node rolling deploy (karışık ims/no-ims) forge'lu IT dikişinde kanıtlı ama gerçek iki-sürüm cluster'ında koşulmadı → **durable outbox** ertelenmiş; geri-NTP-step ms hassasiyeti (düşük). Hiçbiri blocker değil.

---

## RELEASE CANDIDATE — v1.0.0-rc.1 — 2026-07-22

**Aday commit:** `513ede6` · **Tag:** `v1.0.0-rc.1` (annotated) · **CI:**
[run 29907487912](https://github.com/Core-X-Logic/ProjectTemplate/actions/runs/29907487912) — **9/9 success**.

### Release-readiness — tek tablo

| Kapı | Sonuç | Kanıt |
|---|---|---|
| `build` | ✅ | jar üretildi, sonraki gate'ler yeniden derlemeden kullandı |
| `backend` | ✅ | **222 unit + 364 IT = 586**, 0 fail/error/skip; coverage geçti |
| `frontend` | ✅ | **149 test / 30 dosya**, tsc + vite build |
| `docker-build` | ✅ | imaj build + *"Image hardening doğrulandı: non-root · healthcheck · prod profil · heap tavanı"* |
| `typed-client-drift` | ✅ | `schema.d.ts` API yüzeyiyle senkron |
| `migration-drift` | ✅ | V1..V10 `Successfully validated 10 migrations` (mevcut-kurulum simülasyonu) |
| `live-smoke` | ✅ | authenticated + negatif tenant assertion'ları (Redis'li ortam, fail-closed revocation hizmet veriyor) |
| `security-checks` | ✅ | gitleaks **no leaks** · npm audit **0 vulnerabilities** (frontend + build) |
| `release` | ✅ | artifact hazır + deploy-plan dry-run (DEPLOY_ENABLED=false → gerçek deploy KOŞMADI; placeholder+scaffold) |

**Lokal ↔ CI:** backend 586 = 586 · frontend 149 = 149. Sapma yok.

### Blocker durumu — **BOŞ (kod blocker'ı yok)**

RISK-REGISTER status/mitigation tablosu: PROD-R1..R16 **hepsi Closed** (R6/R16 residual'lı). Genuine
açık kalanlar org-policy / operatör-ops / kayıtlı next-phase — hiçbiri **template kod blocker'ı değil**
(sınıflandırma RISK-REGISTER RC bölümünde).

### Karar

**GO** — RC v1.0.0-rc.1. Blocker listesi boş; kanıt tek yerde (bu tablo + tag notu); dokümanlar tutarlı.

---

## Go-Live yürütme — 2026-07-22 — **PREFLIGHT'TA DURDU (gerçek deploy KOŞMADI)**

RC `v1.0.0-rc.1` (`513ede6`) tabanından go-live denendi. **Sonuç: prod deploy YAPILMADI** — dürüst kayıt.

**1) Preflight (RUNBOOK §1.2): DURDU.** 10/10 zorunlu prod env/secret **EKSİK**
(`SPRING_PROFILES_ACTIVE, DB_URL/USER/PASSWORD, JWT_SECRET, FIELD_ENCRYPTION_KEY, REDIS_HOST/PORT,
CORS_ALLOWED_ORIGINS, VITE_API_BASE_URL`), deploy hedefi **bağlı değil** (`IMAGE_REGISTRY` boş,
`DEPLOY_COMMAND` boş, `DEPLOY_ENABLED=false`). `release` job bilinçle placeholder+scaffold; prod ortam,
secret ve deploy komutu **operatör tarafından sağlanmalı** (SETUP §6). §1.2 gate'i uyarınca deploy'a
GEÇİLMEDİ. **Gerçek deploy fabrike edilmedi.**

**2) Dry-run plan:** scaffold'un CI-kanıtlı çıktısı (RC run 29907487912): *"Deploy plan yazıldı
(dry-run). DEPLOY_ENABLED=false"* + *"scaffold no-op … Deploy KOŞMADI"*. Bağlandığında koşacak
adımlar: image `sha-<kısa-sha>` → `DEPLOY_COMMAND` (cloud-agnostic). Bugün: no-op.

**3) Go-live PROVASI (çalışan RC build, dev/8080 — prod DEĞİL):** artifact'ın kritik akışları sağlıklı.

| Smoke | Beklenen | Sonuç |
|---|---|---|
| readiness UP | 200 | ✅ 200 |
| login → token (key-ring `kid=legacy`) | token | ✅ |
| /me (revocation validator + Redis) | 200 | ✅ 200 |
| NEG anonim /me | 401 | ✅ 401 |
| NEG bilinmeyen tenant | 400 | ✅ 400 |
| forgot-password (non-disclosure) | 204 | ✅ 204 |
| 2FA verify gate wired (bogus challenge) | 401 | ✅ 401 |

**7/7 yeşil.** (2FA açık kullanıcı ikinci-adım zorlaması: dev'de kayıtlı 2FA kullanıcısı yok; gate'in
wired olduğu bogus-challenge→401 ile + CI `TwoFactorLoginIT` bypass-mutasyonuyla kanıtlı.)

**5) Ops güvenlik:** Redis ayakta → revocation fail-closed auth'a hizmet veriyor (login→/me 200 bunu
kanıtlar; Redis-down→401 fail-closed CI `TokenRevocationDegradeIT`'te). gitleaks/audit **yeniden
koşulmadı** — en son yeşil kanıt RC run 29907487912 (no leaks · npm audit 0). Kritik log anomalisi yok.

**6) Rollback hazır (RUNBOOK §4):** uygulama sürüm geri alma (imajı önceki tag'e) hazır komut; **şema
GERİ ALINMAZ** (V1..V10 geriye-uyumlu: yeni kolonlar defaulted/nullable, yeni tablolar additive). Bugün
deploy olmadığı için rollback tetiklenecek bir şey yok.

**Karar:** RC artifact **deploy-ready ve sağlıklı** (prova 7/7 + CI 9/9), ama **gerçek go-live için
NO-GO** — operatör prod ortamı + secret + deploy hedefi sağlamadan çıkış yapılamaz. Ne GO-LIVE SUCCESS
(hiçbir şey canlıya çıkmadı) ne ROLLBACK (geri alınacak deploy yok): **BLOCKED-AT-PREFLIGHT**.

---

## CI release hattı prod-tetiklemeye hazırlık — 2026-07-22, `a8a8262`, gerçek `push`, **9/9** (run 29910265282)

Sadece pipeline hazırlığı — **gerçek deploy YOK**, domain/API/auth/tenant/frontend değişmedi (yalnız
`.github/workflows/ci.yml` + SETUP §6). `docker-build` job'ına **kapılı** registry login + push eklendi;
varsayılan **güvenli no-op** (`PUSH_IMAGE` yok → push yok). Secret repoya yazılmadı — hepsi Actions
Variables/Secrets üzerinden tüketiliyor.

**Vakum-yeşil riski + log kanıtı** (bu koşuda gerçekten ölçülen):

| Gate/adım | Vakum-yeşil riski | Bu koşudaki kanıt (step conclusion) |
|---|---|---|
| `docker-build` → Build backend image | — | **success** — imaj `load:true` ile build edildi |
| → Assert image hardening (PROD-R27) | — | **success** — `Image hardening doğrulandı: non-root · healthcheck · prod profil · heap tavanı` (push'tan **ÖNCE**) |
| → **Registry login** | doğrulanmamış imajı push etmek | **skipped** — `if: env.PUSH_IMAGE == 'true'`, varsayılan false → **koşmadı** |
| → **Push backend image** | varsayılan push açık | **skipped** — aynı kapı → **koşmadı** |
| → **Push skipped (safe default)** | sessiz atlama | **success** — echo: *"PUSH_IMAGE != 'true' → imaj build + sertleştirme doğrulandı ama registry'e PUSH EDİLMEDİ (varsayılan güvenli no-op)"* |
| `release` → Deploy plan (dry-run) | — | **success** — plan yazıldı |
| `release` → Deploy (guarded — no-op) | `DEPLOY_ENABLED=false` iken deploy | **success** — no-op (koşacak komut yok) |

**Sıralama garantisi kanıtlı:** hardening assert **success** → login/push **skipped**. Yani imaj önce
sertleştirmesiyle doğrulanır, ancak ondan sonra (ve yalnız `PUSH_IMAGE=true` iken) push edilir —
doğrulanmamış imaj hiçbir koşulda registry'e çıkamaz.

**Fail-fast (kod-tamamlandı, bu koşuda tetiklenmedi):** `PUSH_IMAGE=true` + `IMAGE_REGISTRY` boş →
login adımı anlaşılır hata; `DEPLOY_ENABLED=true` + `IMAGE_REGISTRY`/`DEPLOY_COMMAND` boş → release
`: "${VAR:?...}"` ile durur. Bu koşu her iki bayrak da kapalı (varsayılan) olduğu için ilgili adımlar
**skipped** — fail-fast'in kendisi operatör imzasız çalıştırılamaz (registry kimlik bilgisi gerekir),
bu yüzden **fabrike edilmedi**; SETUP §6.4'te operatörün doğrulayacağı adım olarak yazıldı.

**Push-ON kanıtı (operatör-kapılı, BU KOŞUDA YOK):** gerçek registry push'u (tag `sha-<kısa>` + digest'in
step summary'e basılması) operatörün `PUSH_IMAGE=true` + registry kimlik bilgisi ile ilk koşusunda
üretilir. Kimlik bilgisi olmadan (ve secret-handling yasağı gereği) burada **koşturulamadı** — dürüst
kayıt: push-OFF no-op CI-kanıtlı ✅, push-ON kod-tamamlandı ama operatör-doğrulamalı ⏳.

---

## Dashboard widget sistemi + login stale-tenant düzeltmesi — 2026-07-22, `41dafbd`+`8f8452b`, gerçek `push`, **9/9** (run 29919645849)

Yalnız frontend (backend/`schema.d.ts`/API sözleşmesi değişmedi — typed-client-drift yeşilliği bunu
bağımsız doğrular). Yeni npm bağımlılığı yok.

| Kanıt | Ölçüm |
|---|---|
| Frontend suite | **31 dosya / 161 test, 0 fail** (lokalde 2× + CI `frontend` job) |
| Dashboard suite | 10 test: izinli KPI render · **negatif izin = widget yok VE sorgu hiç atılmadı** (mock not-called assert) · widget-başına hata izolasyonu + retry refetch · boş durumlar · host/tenant bağlam ayrımı (`/subscriptions/me` host'ta çağrılmıyor, tenants KPI tenant'ta izne rağmen yok) · 404→empty · **örneklem beyanı iki yönlü** (totalElements>sample → gösterge var; == → yok) |
| Login testleri | 5 test; **negatif kanıt eski kodda ölçüldü**: `clears a STALE persisted tenant` eski kodda `expected 'stale-tenant' to be null` ile KIRMIZI, düzeltmeyle yeşil |
| Build | `tsc -b` + vite temiz |
| Review | stack-reviewer 5 bulgu (1 yüksek: trend örneklemi sunucunun sessiz `max-page-size:100` tavanına takılıyordu) — commit ÖNCESİ 5/5 kapatıldı |

**Vakum-yeşil notu:** trend bulgusu tam bu sınıftandı — 500'lük istek sunucuda sessizce 100'e
kırpılıyor, dev'in küçük verisinde ve mock'lu testte görünmüyordu. Kapanış: sabit 100'e hizalandı
**ve** kısmi örneklem UI'da beyan ediliyor **ve** iki yönlü test mock'u `totalElements=250` ile
kırpmayı gerçekten simüle ediyor.

**Ekran kanıtı alınamadı (dürüst):** tarayıcı uzantısı oturum ortasında koptu; ayrıca canlı denemede
bayat `X-Tenant` bug'ı bulundu (yukarıdaki fix'in kaynağı — otomasyonun "giremiyorum" hali kullanıcının
raporuyla birebir aynı kök nedendi). Görsel önce/sonra yerine kanıt: test + build + CI + review zinciri.

---

## SaaS parite kapanışı — 2026-07-22, `21609ce`+`df7ab28`, gerçek `push`, **9/9** (run 29932283294)

Parite ölçümü `docs/SAAS-PARITY-MATRIX.md`'de kalıcı (kanıt-linkli). Bu koşuda ölçülenler:

| Kanıt | Ölçüm |
|---|---|
| Backend `clean verify` | **BUILD SUCCESS — 222 unit + 367 IT** (0 fail/skip); +3 yeni IT |
| Yeni IT'ler | `SaasNotificationBridgeIT` (aktivasyon+iptal → tenant Admin bildirimi; **provisioning bildirmez** negatifi) · `SubscriptionExpiryNoticeIT` (pencere dışı 0 → içi 1 → saatlik tekrar koşu **yine 1**, ledger idempotency) |
| **Negatif kanıt (ölçüldü)** | İlk koşu KIRMIZI: `Expecting [] to contain ["saas.subscription.activated"]` + `Expected size: 1 but was: 0` — teslimat yokken testler düşüyor. Kök neden: tenancy `hostFilter` alıcı sorgusunu gizliyordu; filtre yalnız o sorgu için askıya alınıp geri yüklenerek yeşile döndü. Yani testler tam da korumanın (teslimatın) kalkmasını yakalıyor |
| Modulith | `ArchitectureRulesTest` 9/9 — saas modülü YENİ bağımlılık almadı (event saas::api'de; köprü identity'de mevcut kenarlarla) |
| Frontend | build (`tsc -b`) temiz; **32 dosya / 168 test** (+3: detay sheet — geçmiş render, boş-geçmiş, hata+retry; endpoint'in doğru tenant'la çağrıldığı assert'li) |
| İzolasyon güvenliği | Filtre askıya alma tek sorgu kapsamında ve `finally` ile geri yükleniyor; sorgunun kendisi zaten explicit tenant-parametreli (birincil savunma). `TenantIsolationIT` 4/4 yeşil |

Deferred kalemler matrise gerekçeli işlendi: toplu tenant taşıma (edition düzenlenebilir + snapshot
fiyat ihtiyacı düşürdü), otomatik kart-tahsilatlı recurring (TR sağlayıcıları re-checkout modeli;
Stripe SPI uykuda), fatura üretimi/PDF (şema hazır; kaynaktaki race'li manuel üretim taşınmadı),
host gelir istatistikleri.

**CI notu (dürüst kayıt):** ilk koşu (`21609ce`, run 29931261884) `backend`'de KIRMIZI —
`BillingReconciliationJobIT.twoBackToBackJobTriggersRunOnce` "expected 1 but was 0". Kök neden yeni
kod DEĞİL: paylaşılan context'te daha önce koşan bir sınıf aynı job'ın ShedLock'unu almış,
`lockAtLeastFor` (PT30S) yavaş CI makinesinde iki tetiği de atlatmış — yeni IT sınıflarının sırayı
kaydırmasıyla açığa çıkan **gizli sıra-bağımlılığı**. Düzeltme test tarafında (`df7ab28`): test kendi
kilit satırını sıfırlayıp ölçüyor (senkron tetik, mid-run job yok → bypass değil temiz reset).
İlgili 4 sınıf lokalde birlikte yeşil + CI 9/9 ile mühürlendi.

---

## Tab-bazlı dashboard yönetim merkezi — 2026-07-22, `85c42e8`, gerçek `push`, **9/9** (run 29927220897)

Yalnız frontend; `schema.d.ts` dokunulmadı (typed-client-drift yeşili bağımsız kanıt); yeni bağımlılık yok.

| Kanıt | Ölçüm |
|---|---|
| Frontend suite | **31 dosya / 165 test, 0 fail** (lokal + CI `frontend`) |
| Dashboard suite | **14 test**: sekme görünürlüğü **iki yönlü** (izinsiz → Aktivite/Finans/Yönetim sunulmuyor; izinli → sunuluyor) · **tembel sekme verisi** (`listNotifications` Operasyon açılana dek çağrılmıyor, mock not-called) · negatif izin = widget yok + sorgu yok · sekmeler-arası hata izolasyonu + Retry refetch · boş durumlar · host/tenant Finans ayrımı (host'ta `/subscriptions/me` hiç çağrılmıyor; host+`subscriptions.read` → abonelik özeti) · örneklem beyanı iki yönlü |
| Bundle | recharts `lazy()` chunk'ı: index **1557→1166 kB** (gzip 439→331); `activity-trend` chunk'ı 396 kB talep-üzerine |
| Build | `tsc -b` + vite temiz |

**Davranış korunumu:** auth/tenant/permission davranışı değişmedi — aynı izin literalleri, aynı
`enabled` disiplini; sekme kapısı da aynı izinlerden türetiliyor (ayrı bir izin kaynağı İCAT EDİLMEDİ).
Redis şartı: yeni backend çağrısı yok → dashboard'ın Redis'e bağımlılığı bu turda da sıfır.
