# AI ile Çalışma Rehberi

**Hedef kitle:** repoda Claude Code / Codex ile geliştirme yapan herkes.
**Başarı ölçütü:** her AI görevi scope-lock + kanıt ile kapanır; iki ajan aynı dosyayı aynı anda
bozmaz; "yeşil" iddiası log'a dayanır.

> Bu rehber **çalışma modelini** anlatır. Kopyala-yapıştır hazır promptlar: `PROMPT-CATALOG.md`.
> Kanıtın ne sayıldığı: `EVIDENCE-AND-GATES.md`. Bağlayıcı sözleşme:
> `../governance/AGENT-WORKING-AGREEMENT.md`.

## 1. Temel çalışma modeli

Üç kural her görevde geçerli (araç Claude olsun Codex olsun):

1. **Scope-lock.** Görev, verilen kapsamla sınırlıdır. Yol boyunca bulunan istenmeyen ihtiyaç →
   düzeltme, `RISK-REGISTER.md`'ye "sonraki adım" yaz. Kapsam dışına çıkma.
2. **Kanıtsız "tamamlandı" yok.** Done = kod derleniyor + ilgili test geçiyor (ad + sayı) +
   `clean verify` BUILD SUCCESS + Modulith sınır kontrolü geçti. Tahmin/umut kabul değil.
3. **Yeşil ≠ doğruladı.** Bir kapının geçmesi bir şeyi kontrol ettiğini kanıtlamaz. Değişiklikten
   **önce** testi yaz ve eski kodda **düştüğünü gör** (negatif kanıt).

## 2. Hangi ajan/rol, ne zaman

Claude Code'da `.claude/agents/` altında hazır takım. Görevin katman sayısına göre seç:

| Ajan | Ne zaman | Girdi/çıktı |
|---|---|---|
| `tech-lead` | **Tek katmanı aşan** her iş (özellik/epik/modül). | İşi dikey dilimlere böler, mühendislere dağıtır, birleştirir, kanıtla raporlar. |
| `backend-engineer` | Java/Spring tarafı: uç, servis, entity, migration, izin, IT. | Tek backend dilimi. |
| `frontend-engineer` | React tarafı: ekran, feature modülü, typed API, i18n, davranış testi. | Tek frontend dilimi. |
| `stack-reviewer` | Kod değişikliğinden **sonra**, commit'ten **önce**. | Bu yığına özgü tuzakları (çok-kiracılık, üçlü kilit, Hibernate sayfalama, migration, health/readiness) inceler. |
| `gate-auditor` | Yeni test/CI gate ekleyince ya da değiştirince. | Gate'in gerçekten **kırmızıya döndüğünü** kanıtlar. Bu depoda beş kontrol yeşilken hiçbir şey doğrulamıyordu. |

**Karar kuralı:**
- Küçük, **tek katmanlı** iş (tek uç, tek ekran) → doğrudan ilgili mühendisi çağır, `tech-lead`'i atla.
- **Çok katmanlı** iş (backend + frontend + izin + i18n + test) → `tech-lead`.
- Kod yazıldı → `stack-reviewer`. Yeni gate eklendi → `gate-auditor`. İkisi ayrı işlerdir.

**Skill'ler** (`.claude/skills/<ad>/SKILL.md`) — riskli bir alana dokunduğunda yüklenen, bu depoya
özgü kural setleri. Ajanla ya da elle çalış, ilgili skill'i oku:

| Skill | Ne zaman yükle |
|---|---|
| `migration-safety` | Flyway `V<n>__*.sql` eklerken/değiştirirken ya da şema değişikliği planlarken (değişmezlik, rolling deploy güvenliği). |
| `permission-model` | Yeni yetki gerektiren uç/ekran eklerken (AppPermissions + PermissionDefinitions + üçlü kilit). |
| `tenant-isolation` | Kiracıya ait veri okuyan/yazan entity/repository/uç eklerken (tenant filtresi, JWT claim otoritesi, negatif test). |

**Codex kullanıyorsan:** aynı roller senin promptunda **rol tanımı** olarak yaşar — `PROMPT-CATALOG.md`
şablonları "Rol: backend-engineer …" satırıyla başlar. Ajan altyapısı olmadan da aynı disiplin uygulanır.

## 3. Paralel çalışma kuralları

Bağımsız işler paralel koşturulabilir (hız için) — ama yalnız **gerçekten bağımsızsa**.

**Paralel GÜVENLİ:**
- Farklı modüller (ör. `billing` backend dilimi + `users` frontend ekranı).
- Ayrı dosya kümeleri; ortak dosyaya ikisi de yazmıyor.
- Bir üretim + bir review (review yazmaz, sadece okur).

**Paralel TEHLİKELİ (sıraya sok):**
- İki ajan aynı dosyaya yazacak (özellikle `ci.yml`, `application-*.yml`, `AppPermissions`,
  `PermissionDefinitions`, `schema.d.ts`, i18n `en.ts`/`tr.ts`, aynı migration klasörü).
- Backend API değişikliği + onun typed client'ı: **birlikte** landing yapmalı (bkz. §4 drift).
- Aynı migration numarasını iki dilim isteyecek.

## 4. Aynı dosyaya çakışmayı önleyen pratik kurallar

1. **Dosya sahipliği.** Paralel dağıtmadan önce her ajana **ayrık dosya/dizin kümesi** ata.
   Çakışan dosya varsa o işi **tek ajana** ver ya da sıraya sok. `tech-lead` bunu dilim sınırıyla yapar.
2. **Ortak-dosya işlerini serileştir.** İzin sabitleri, i18n sözlükleri, `ci.yml`, prod yml,
   generated `schema.d.ts` → tek seferde tek yazar. Bunlar merge çakışmasının ve sessiz üzerine
   yazmanın kaynağı.
3. **API + client atomik.** Backend controller/DTO değişince frontend `npm run gen:api` **aynı
   commit'te** gelmeli — yoksa `typed-client-drift` kapısı kırmızı. Bunu iki ayrı paralel ajana
   bölme; ya tek ajan yapar ya backend biter, sonra frontend regenerate eder.
4. **Migration append-only.** İki dilim birden migration ekliyorsa numarayı çakıştırma; uygulanmış
   bir `V<n>__` dosyasını **düzenleme** (checksum hatası). Değişiklik daima **yeni** `V<n+1>__`.
5. **Commit'ten önce review.** Paralel üretimden gelen dilimler birleşince, commit öncesi
   `stack-reviewer` tek geçiş yapsın — çakışma ve tuzak burada yakalanır.
6. **Preflight.** Push etmeden `/preflight` ya da `bash zero-spring/scripts/ci-local.sh` — yerel
   kapılar, CI dakikası harcamadan.

## 5. Anti-pattern'ler (yapma)

- ❌ "Şunu da hallettim" — kapsam dışı ekleme. Scope-lock kırılır, review yükü ve risk artar.
- ❌ Kanıtsız "yeşil/çalışıyor". Log/sonuç yoksa done değil.
- ❌ Raporlanan **tek varyantı** düzeltip sınıfı açık bırakmak (bu depoda 4 kez: 415 → wildcard →
   `application/yaml` → sort'un üçüncü şekli). Sınıfı kapat, yazımı değil.
- ❌ Backend API değişikliğini typed client olmadan push etmek → drift kırmızı.
- ❌ İki ajanı aynı ortak dosyaya paralel salmak.

## 6. Başarı ölçütü

Bir AI görevi doğru yürütüldü sayılır ancak: kapsam korundu · değişiklik kanıtlı (test adı + sayı,
hangi risk kapandı) · ortak dosyalar serileştirildi · commit öncesi review + preflight geçti.
Aksi halde iş bitmemiştir — `EVIDENCE-AND-GATES.md`'deki checklist'e dön.
