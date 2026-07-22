# Kanıt ve Kalite Kapısı Rehberi

**Hedef kitle:** "bitti" demeden önce herkes (insan ya da AI).
**Başarı ölçütü:** her kapanış kanıtla gelir; test/CI/governance senkron; yeşil bir kapı gerçekten
bir şeyi doğruluyor.

> İlke kaynağı: `../governance/AGENT-WORKING-AGREEMENT.md`. Eşikler: `../QUALITY-GATES.md`.
> Ölçümlerin kaydı: `../governance/QUALITY-GATES-RESULTS.md`.

## 1. "Tamamlandı" için minimum kanıt

Bir iş ancak şu **beşi** varsa done — biri eksikse iş bitmemiştir:

1. **Derleniyor** — `./mvnw compile` / `npm run build` BUILD SUCCESS.
2. **İlgili test geçiyor** — test sınıf/dosya **adı** + geçen sayısı raporlandı (tahmin değil).
3. **Bütün suite yeşil** — backend `clean verify` (surefire + failsafe sayıları), frontend
   `npm run test` 0 fail.
4. **Sınır kontrolü** — `ApplicationModules.verify()` geçer (Modulith; döngü yok).
5. **Governance senkron** — QUALITY-GATES-RESULTS + (varsa) RISK-REGISTER + CHANGELOG güncellendi.

"Yeşil sanıyorum / çalışmalı" **kanıt değildir**. İddia gerçek çıktıya dayanmalı.

## 2. Negatif kanıt (no-fake-green'in kalbi)

Bir test bir şeyi koruduğunu iddia ediyorsa, o korumayı **kaldırınca kırmızıya dönmeli**. Yordam:

1. Testi **önce** yaz.
2. Eski kodda / korumayı kaldırılmış halde **koş → DÜŞTÜĞÜNÜ gör**.
3. Sonra düzelt → yeşile döner.

Test adım 2'de düşmüyorsa **yanlış şeyi ölçüyor** — sil ya da düzelt.

**Örnekler (bu depodan):**
- Yetki: yetkisiz kullanıcıyla uca vur → `403` bekle. İzin kontrolü kaldırılınca test kırmızı olmalı.
- Fail-closed: Redis'i düşür → kimlikli çağrı `401` (revocation degrade IT). Fail-open olsaydı `200`
  dönerdi ve test bunu yakalardı.
- Alg-confusion: JWT algoritmasını değiştir → doğrulayıcı reddetsin. HS512 pin'i kaldırınca kırmızı.
- Tenant izolasyonu: başka tenant'ın verisine `X-Tenant` header'ıyla ulaşmayı dene → `403` (JWT
  claim otoriter). Filtre unutulursa sızıntı → test yakalar.

## 3. Test / CI / governance senkron kontrol listesi

Push öncesi tek tek işaretle:

- [ ] `clean` koştu mu? (`clean` olmadan bayat `.class` ile yanlış yeşil olur)
- [ ] Backend API değişti mi? → `npm run gen:api` **aynı işte**, `schema.d.ts` diff temiz
      (yoksa `typed-client-drift` kırmızı)
- [ ] Yeni migration mı? → **yeni** `V<n>__` dosyası; uygulanmış dosyayı düzenleme
      (yoksa `migration-drift` checksum hatası)
- [ ] Yeni uç mı? → `@PreAuthorize` **ve** `<Can>` **ve** route guard (üçlü kilit) — üçü de var mı?
- [ ] Yeni entity mi? → `tenant_id` + Hibernate `@Filter` var mı?
- [ ] i18n değişti mi? → `en.ts` **ve** `tr.ts` ikisi de tam mı? Sabit string kaldı mı?
- [ ] Secret/pattern eklendi mi? → gitleaks etkisini **bulgu sayısıyla** ölç (sözdizimine güvenme)
- [ ] Yerel kapılar geçti mi? → `bash zero-spring/scripts/ci-local.sh` (readiness · smoke · secrets · migration)
- [ ] Commit öncesi `stack-reviewer`, yeni gate varsa `gate-auditor` geçti mi?
- [ ] QUALITY-GATES-RESULTS + CHANGELOG + (varsa) RISK-REGISTER güncel mi?

## 4. CI kapıları — her biri neyi doğrular, nasıl vakum-yeşil olur

CI zinciri 9 job. "Geçti" bir ölçüm değil; her kapının **boş yere yeşil olmadığını** gösteren log
satırı aranır (kayıt: QUALITY-GATES-RESULTS).

| Gate | Doğrular | Vakum-yeşil riski (dikkat) |
|---|---|---|
| `build` | jar üretilir, sonraki job'lar yeniden derlemez | — |
| `backend` | `clean` koşar + unit + IT + coverage | Bayat bytecode → `clean:clean` log'da görünmeli |
| `frontend` | `tsc -b && vite build` + test | `test` tek başına typecheck yapmaz — build şart |
| `typed-client-drift` | üretilen şema commit'liyle birebir | Şema üretilmeden geçmek → `diff -u` görünmeli |
| `migration-drift` | **mevcut kuruluma** migrate | **Boş sette yeşil dönebilir** — log'da "applied N, now at vN" olmalı |
| `live-smoke` | jar'ı boot eder, kritik + negatif akışlar | Assertion koşmadan geçmek → PASS sayısı + negatifler görünmeli |
| `security-checks` | gitleaks + prod-yml env-ref + npm audit | gitleaks `.git` bulamayıp hatayı yutmak → "N commits scanned" |
| `docker-build` | imaj build + hardening assert (PROD-R27) | Hardening assert push'tan ÖNCE koşmalı |
| `release` | dry-run plan + guarded deploy | `DEPLOY_ENABLED=false` iken no-op; push kapalı iken skip |

## 5. Drift ve false-green önleme

**Drift** = kaynak ile üretilen/beklenen arasında sessiz sapma.
- **Typed-client drift:** backend imzası değişti, frontend şeması eski. → API + `gen:api` **atomik** landing.
- **Migration drift:** uygulanmış dosya düzenlendi ya da mevcut kuruluma uymayan değişiklik. → append-only `V<n>__`.
- **Springdoc sıralama drift:** `springdoc.writer-with-order-by-keys: true` açık kalmalı, yoksa
  typed-client-drift rastgele kırmızıya döner (reflection sırası JVM'ler arası kararsız).

**False-green önleme:**
- Bir kapının geçmesi bir şeyi kontrol ettiği **anlamına gelmez** — `migration-drift` boş sette
  hiçbir şey doğrulamadan yeşil döndü (bu depoda gerçek). **Log'dan teyit et.**
- Yeni bir gate ekledin → `gate-auditor` ile korumayı kaldırınca **kırmızıya döndüğünü kanıtla**.
- Temiz-DB testleri **mevcut kurulum** hatalarını göremez — şema/izin/seed değişiminde canlı smoke
  zorunlu (temiz suite yeşilken çalışan kurulum bozuk olabilir; bu bir kez oldu).

## 6. Başarı ölçütü

Kapanış geçerli ancak: minimum 5 kanıt tam (§1) · negatif kanıt gösterildi (§2) · senkron checklist
işaretlendi (§3) · yeşil kapılar log'dan doğrulandı (§4/§5). Aksi halde "tamamlandı" denmez.
