# Klonlayan Ekip için İlk 7 Gün Planı

**Hedef kitle:** şablonu yeni devralan ekip.
**Başarı ölçütü:** 7. günün sonunda ekip lokalde çalıştırabiliyor, bir dikey dilim üretebiliyor,
devraldığı riskleri biliyor ve go-live yolunu anlıyor.

> Sıra önemli: her gün bir öncekinin çıktısına dayanır. "Hemen/ertele" kolonu, gereksiz erken
> yatırımı önler.

## Gün 1 — Çalıştır ve gör

**Amaç:** repo lokalde ayakta, ilk smoke yeşil.
- `QUICKSTART.md` baştan sona: docker compose → backend (dev) → frontend → §4 smoke akışı (4/4 yeşil).
- Arayüze `admin/Admin123!` ile gir; host vs `default` tenant farkını gör.
- **Başarı ölçütü:** readiness UP + login token + `/me` 200 + anonim `/me` 401.
- **Hemen:** sık kurulum hataları tablosunu (QUICKSTART §5) oku — birini yaşarsan tanı.
- **Ertele:** prod, deploy, secret — bugün değil.

## Gün 2 — Neyi devraldın: risk + mimari üst geçiş

**Amaç:** neyin hazır, neyin **hazır olmadığı** ve hangi kısıtları taşıdığın net.
- `../governance/RISK-REGISTER.md` **tümü** — özellikle PROD-R23 (branch protection), PROD-R13
  (Redis hard auth), PROD-R6/R16 durumları, ödeme sağlayıcı kısıtları.
- `../ARCHITECTURE.md` + `../README.md` (doküman haritası) hızlı geçiş.
- **Başarı ölçütü:** "şablon bana ne vermedi?" sorusuna 5 madde sayabiliyorsun (şifre sıfırlama
  ekranı, dosya yükleme, kullanıcı daveti, ödeme entegrasyonu canlı doğrulaması, …).
- **Hemen kapat (erken risk):** yok — önce anla, sonra kapat. Yanlış erken düzeltme borç yaratır.

## Gün 3 — Güvenlik modeli + üçlü kilit

**Amaç:** yeni uç yazmadan önce güvenlik yüzeyini anla — en pahalı hatalar buradan çıkar.
- `../SECURITY.md`: kiracı izolasyonu, üçlü kilit (`@PreAuthorize` + `<Can>` + route guard), JWT
  key-ring + revocation (fail-closed), rate limit, secret yönetimi.
- `CLAUDE.md` tuzaklar tablosu: health vs readiness, `clean verify`, migration append-only,
  `@EntityGraph`+`Pageable` birlikte kullanılmaz.
- **Başarı ölçütü:** "yeni entity'de sızıntıyı ne önler?" (`tenant_id` + `@Filter`) ve "yetkisiz
  kullanıcı neden 403 alır?" (üçlü kilit) sorularını cevaplayabiliyorsun.
- **Hemen:** takımın AI kullanacaksa `WORKING-WITH-AI.md` + `PROMPT-CATALOG.md` oku.

## Gün 4 — Modül ekleme yordamı (kuru okuma)

**Amaç:** dikey dilimin ne olduğunu ve atlanan adımın neden sessiz açık olduğunu anla.
- `../ADDING-A-MODULE.md` baştan sona + `/new-module <ad>` komutunun listelediği 10 koruma.
- Dikey dilim = 5 sütun: backend · frontend · izin+i18n · test (mutlu yol + **negatif yetki**) ·
  risk kaydı. Dördü tamamsa iş bitmemiştir.
- **Başarı ölçütü:** boş bir modülün gerektirdiği tüm dosyaları (entity+migration, uç+DTO, izin
  sabiti+tanım, ekran+guard, en/tr, IT) listeleyebiliyorsun.
- **Ertele:** gerçek modül yazımı yarın.

## Gün 5 — İlk dikey dilim (küçük, gerçek)

**Amaç:** uçtan uca bir küçük dilim üret ve **kanıtla** kapat.
- Küçük bir iş seç (ör. mevcut bir listeye 1 kolon + 1 uç). `PROMPT-CATALOG.md` §1/§2 şablonuyla
  ver ya da elle yaz.
- `EVIDENCE-AND-GATES.md` §3 checklist'i uygula: `clean verify`, API↔client atomik, üçlü kilit,
  i18n en+tr, negatif test.
- Push öncesi `bash zero-spring/scripts/ci-local.sh` + commit öncesi `stack-reviewer`.
- **Başarı ölçütü:** dilim done (evidenced) — test adı + sayı, negatif kanıt gösterildi, yerel
  kapılar yeşil.

## Gün 6 — CI + kanıt disiplini

**Amaç:** CI zincirini ve "yeşil ≠ doğruladı"yı içselleştir.
- İlk push'un CI koşusunu izle: 9 job, her birinin log'da ne doğruladığı (`EVIDENCE-AND-GATES.md` §4).
- Bir gate değiştirdiysen `gate-auditor` ile kırmızıya döndüğünü kanıtla.
- `SETUP-NEW-PROJECT.md`: CI enable, branch protection kararı (planına göre), Actions ayarları.
- **Başarı ölçütü:** her CI kapısının neyi koruduğunu ve nasıl vakum-yeşil olabileceğini söyleyebiliyorsun.

## Gün 7 — Go-live yolu (kuru çalışma)

**Amaç:** prod'a çıkışın adımlarını ve devir sınırını anla — bugün gerçek deploy YOK.
- `OPERATOR-HANDOFF.md` + `../RELEASE-RUNBOOK.md` §1–§4: provisioning checklist, deploy/rollback,
  GO/NO-GO.
- Rehearsal: çalışan dev build üzerinde RUNBOOK §3 smoke setini elle koş (7 akış).
- **Başarı ölçütü:** hangi durumda GO, hangi durumda NO-GO diyeceğini ve secret'ın neden operatörde
  kaldığını anlatabiliyorsun.
- **Ertele:** gerçek go-live — operatör prod ortamı + secret + deploy hedefi sağladıktan sonra.

## Özet: hemen vs ertele

| Hemen (ilk hafta) | Ertele (hazır olunca) |
|---|---|
| Lokalde çalıştır + smoke | Gerçek prod deploy |
| RISK-REGISTER'ı oku, kısıtları kabul et | Ödeme sağlayıcı canlı doğrulama (operatör kimlik bilgisi) |
| Güvenlik modeli + üçlü kilit | Branch protection (plan/repo görünürlüğü kararı) |
| Bir küçük dikey dilim + kanıt | Büyük modüller / kapsamlı feature'lar |
| CI kanıt disiplini | 2FA SMS/WebAuthn, durable revocation outbox (next-phase) |

## Kalıcı alışkanlıklar (7 günden sonra da)

- Kanıtsız "tamamlandı" yok · negatif kanıt önce · sınıfı kapat yazımı değil · yeşil ≠ doğruladı.
- Push öncesi yerel kapılar; commit öncesi review. Governance dosyalarını güncel tut.
