# Prompt Kataloğu

**Hedef kitle:** Claude Code / Codex'e görev veren geliştirici.
**Kullanım:** ilgili şablonu **kopyala**, `<...>` alanlarını doldur, gönder.
**Başarı ölçütü:** her prompt beş zorunlu alanı taşır → ajan kapsam dışına çıkamaz, kanıtsız kapatamaz.

## Her promptta zorunlu 5 alan

| Alan | Ne yazılır | Neden |
|---|---|---|
| **Scope-lock** | Nelere dokunulacak + nelere **dokunulmayacak** | Kapsam kayması ve yan etkiyi keser |
| **Kabul kriteri** | Ölçülebilir "bitti" tanımı | "Yeşil sanıyorum"u engeller |
| **Kanıt formatı** | Hangi çıktı istenir (test adı+sayı, log satırı, curl kodu) | Doğrulanabilir kapanış |
| **No-fake-green** | "Değişiklikten önce testi yaz, eski kodda düştüğünü göster" | Vakum-yeşili önler |
| **Non-goals** | Bilinçli **yapılmayacaklar** | Refactor/feature sızmasını durdurur |

> Disiplin kaynağı: `WORKING-WITH-AI.md` + `../governance/AGENT-WORKING-AGREEMENT.md`.
> Kanıt sözlüğü: `EVIDENCE-AND-GATES.md`.

---

## 1. Backend görevi

```
Rol: backend-engineer. Java 21 / Spring Boot 3.5 / Spring Modulith.

GÖREV
<tek cümlede ne: ör. "billing modülüne subscription iptal ucu ekle">

SCOPE-LOCK
- Sadece: <modül/dizin, ör. backend/.../billing>
- Dokunma: başka modül, frontend, ci.yml, izin sabitleri dışındaki ortak dosyalar.
- Yeni izin gerekiyorsa AppPermissions + PermissionDefinitions + @PreAuthorize üçünü birlikte ekle.

KABUL KRİTERİ
- Uç RFC 9457 ProblemDetail döner (entity değil DTO).
- tenant_id + @Filter yeni entity'de var; JWT tenant claim otoriter.
- En az 1 mutlu yol IT + 1 NEGATİF yetki IT (yetkisiz kullanıcı 403).
- ./mvnw -B -ntp clean verify → BUILD SUCCESS; ApplicationModules.verify() geçer.

KANIT FORMATI
- Geçen test sınıf adları + surefire/failsafe sayıları.
- clean verify son satırı (BUILD SUCCESS + süre).
- Kapanan/açılan RISK-REGISTER ID'si (varsa).

NO-FAKE-GREEN
- Negatif testi ÖNCE yaz, eski kodda düştüğünü göster, sonra düzelt. Düşmüyorsa test yanlış şeyi ölçüyor.

NON-GOALS
- Frontend/i18n yok (ayrı dilim). Refactor yok. Yeni kütüphane yok (ADR gerektirir).
```

---

## 2. Frontend görevi

```
Rol: frontend-engineer. React 19 / Vite / TypeScript / Tailwind 4 / shadcn.

GÖREV
<ör. "kullanıcı listesine 2FA durum kolonu + toggle ekle">

SCOPE-LOCK
- Sadece: <feature dizini, ör. frontend/app/src/features/users>
- Dokunma: backend, ci.yml. i18n değişikliği en.ts + tr.ts İKİSİNDE birden.
- Yeni yetki-korumalı UI: <Can permission> + route guard birlikte (üçlü kilidin frontend ayağı).

KABUL KRİTERİ
- Typed API kullanılır (elle fetch değil). Backend API değiştiyse: npm run gen:api aynı işte.
- i18n anahtarları en + tr tam; sabit string yok.
- En az 1 davranış testi (kullanıcı akışı), mutlu yol + yetkisiz görünüm.
- npm run build (tsc -b dahil) + npm run test → 0 fail.

KANIT FORMATI
- Test dosya + geçen test sayısı. build çıktısı (tsc temiz).
- typed-client-drift riski varsa: gen:api sonrası schema.d.ts diff temiz.

NO-FAKE-GREEN
- Yetkisiz-görünüm testini önce yaz, izin varken/yokken farkı gördüğünü kanıtla.

NON-GOALS
- Backend değişikliği yok (varsa ÖNCE backend dilimi biter). Tasarım sistemini elden geçirme yok.
```

---

## 3. Güvenlik / hardening görevi

```
Rol: backend-engineer + adversaryal güvenlik gözü. Fail-closed zorunlu.

GÖREV
<ör. "PROD-Rxx: <risk> kapat">

SCOPE-LOCK
- Sadece <risk>'in kök nedeni. Kapsam dışı bulgu → RISK-REGISTER "sonraki adım".
- Dokunma: alakasız modül, frontend davranışı.

KABUL KRİTERİ
- Fail-CLOSED: bağımlılık (Redis/DB) düşünce güvenli tarafa düş (erişimi REDDET), fail-open ASLA.
- Default'suz her prod property'sinin doğrulayıcısı var (çözülmemiş ${VAR} literal string olarak bağlanır).
- Sınıfı kapat, tek yazımı değil: aynı açığın tüm varyantlarını kapsayan test.
- Secret repoya YAZILMAZ — sadece env / Actions Secrets / secret store.

KANIT FORMATI
- Mutasyon kanıtı: korumayı kaldırınca testin KIRMIZI döndüğü çıktı (gate-auditor mantığı).
- clean verify BUILD SUCCESS. gitleaks etkisi BULGU SAYISIYLA ölç (sözdizimine güvenme).
- RISK-REGISTER'da risk statüsü güncellendi.

NO-FAKE-GREEN
- Fail-closed'u kanıtla: bağımlılığı düşür, erişimin 401/403 olduğunu GÖSTER (degrade IT).

NON-GOALS
- Yeni feature yok. Detection-evasion / saldırı aracı yok. Kapsam dışı sertleştirme sonraki tur.
```

---

## 4. Sadece review / audit

```
Rol: stack-reviewer (kod incelemesi) VEYA gate-auditor (gate doğrulama). SADECE OKU — kod yazma.

GÖREV
<ör. "son commit'i bu yığının tuzaklarına karşı incele"> VEYA
<ör. "yeni migration-drift gate'i gerçekten kırmızıya dönüyor mu kanıtla">

SCOPE-LOCK
- Sadece inceleme/doğrulama. DÜZELTME YAPMA — bulguları raporla.
- Kapsam: <değişen dosyalar / commit aralığı / gate adı>.

KABUL KRİTERİ (stack-reviewer)
- Şu tuzaklar tek tek kontrol edildi: çok-kiracılık (tenant_id + @Filter), üçlü kilit
  (@PreAuthorize + <Can> + route guard), Hibernate @EntityGraph+Pageable birlikte kullanımı,
  migration append-only, health vs readiness, clean verify.
KABUL KRİTERİ (gate-auditor)
- Gate'in korumayı kaldırınca KIRMIZIYA döndüğü kanıtlandı (mutasyon). Yeşil ≠ doğruladı.
  migration-drift gibi boş sette yeşil dönebilen kapılarda log'dan gerçek doğrulamayı göster.

KANIT FORMATI
- Bulgu listesi: dosya:satır · sınıf (correctness/security/...) · somut fail senaryosu · önem.
- Temizse: neyin kontrol edildiği + neden temiz (boş rapor değil).

NO-FAKE-GREEN / NON-GOALS
- "İncelendi, sorun yok" tek başına kabul değil — hangi tuzağın nasıl elendiği yazılır.
- Kod değiştirme, kapsam genişletme yok.
```

---

## 5. Release / go-live

```
Rol: release owner. Kod değişikliği YOK (varsa ayrı iş). Fabrike deploy YASAK.

GÖREV
<ör. "RC freeze" VEYA "go-live preflight → deploy → smoke → GO/NO-GO kararı">

SCOPE-LOCK
- Sadece release adımları + governance kaydı. Domain/API/auth/tenant/frontend davranışı DEĞİŞMEZ.
- Secret repoya yazma; sadece Actions Secrets/Variables + prod secret store üzerinden tüket.

KABUL KRİTERİ
- Preflight (RUNBOOK §1.2): zorunlu prod env/secret + CI yeşil. Biri eksikse NO-GO, deploy'a geçme.
- Prod smoke (RUNBOOK §3): readiness · login · /me · anonim /me 401 · tenant negatif ·
  forgot-password · 2FA ikinci adım.
- GO ancak: kapsam kalemleri "done (evidenced)" + kritik/yüksek bulgu YOK + gates eşiği karşılandı.

KANIT FORMATI
- CI run linki + 9/9 job sonucu. Smoke tablosu (beklenen vs gerçek, HTTP kodları).
- GO/NO-GO kararı + gerekçe. QUALITY-GATES-RESULTS + CHANGELOG + RISK-REGISTER güncel.

NO-FAKE-GREEN
- Env eksikse DUR ve dürüst raporla — "deploy oldu" yazma. Rehearsal ile gerçek deploy'u karıştırma.
  Operatör imzası/kimlik bilgisi gereken adımı fabrike etme, operatör-kapılı olarak işaretle.

NON-GOALS
- Yeni feature/refactor yok. Operatörün prod ortamında imza gerektiren adımı sen yapmazsın
  (bkz. OPERATOR-HANDOFF.md).
```

---

## Prompt seçim tablosu

| İşin | Şablon |
|---|---|
| Tek backend dilimi | §1 |
| Tek frontend dilimi | §2 |
| Risk/güvenlik kapatma | §3 |
| Commit öncesi inceleme / gate doğrulama | §4 |
| Sürüm dondurma / go-live | §5 |
| **Çok katmanlı** (backend+frontend+izin+i18n+test) | §1 + §2'yi `tech-lead`'e ver; dilimlere böler |
