# Agent Çalışma Sözleşmesi (Working Agreement)

Bu sözleşme, zero-platform üzerinde çalışan tüm AI ajanları ve orkestrasyon workflow'ları için
**bağlayıcıdır**. Amaç: enterprise governance — izlenebilir, kanıtlanabilir, kapsam-kilitli üretim.

## 1. Scope lock (kapsam kilidi)

- Kapsam, o iş için verilen görevle tanımlıdır. **Ajan bu kapsamın dışına çıkamaz.** Yol boyunca
  bulunan ama istenmeyen bir ihtiyaç varsa: düzeltme, `RISK-REGISTER.md`'ye yaz ve checkpoint'te
  "sonraki adım" olarak raporla.
- **Teknoloji kilidi:** onaylanmış stack sabit — Java 21, Spring Boot 3.5, Spring Modulith,
  PostgreSQL + Flyway, Redis; frontend **React 19 + Vite + TypeScript** (`ADR-0008`).
  Yeni kütüphane/framework kararı ancak ADR ile ve kullanıcı onayıyla alınır.

## 2. Kanıt zorunluluğu (Definition of "Done")

- **Kanıtsız "tamamlandı" ifadesi YASAKTIR.** Bir modül/özellik ancak şu kanıtlarla "done" sayılır:
  1. Kod derleniyor (`mvnw compile` BUILD SUCCESS).
  2. İlgili modülün en az bir entegrasyon testi (`*IT`) **geçiyor** (test adı + sonuç raporlanır).
  3. `mvnw verify` bütününde BUILD SUCCESS (surefire + failsafe sayıları).
  4. `ApplicationModules.verify()` (Modulith sınır kontrolü) geçiyor.
  5. Parity kalemi PARITY-TRACEABILITY matrisinde `Test Evidence` kolonuna bağlanmış.
- Test sayıları, geçen/kalan, süre; QUALITY-GATES-RESULTS tablosuna yazılır.
- "Yeşil" iddiası, gerçek `verify` çıktısına dayanmalı; tahmin/umut kabul edilmez.

## 3. Checkpoint formatı (her büyük adım sonunda)

Her workflow/faz-dilimi bitiminde şu dört başlık zorunlu:

- **Ne bitti:** somut çıktı (dosya/endpoint/modül).
- **Hangi test geçti:** test sınıfı adları + surefire/failsafe sayıları.
- **Kalan risk:** açık kritik/yüksek/orta bulgular (RISK-REGISTER ID'leri ile).
- **Sonraki adım:** bir sonraki dilim.

Ek olarak her checkpoint **modül durum tablosunu** günceller:

| Module | Backend Status | Frontend Status | Permission/I18n Status | Test Evidence | Risk Level |

Durum değerleri: `Not started` · `In progress` · `Backend done` · `Done (evidenced)`.

## 4. Faz sonu GO/NO-GO

- Faz ancak **GO** kararıyla kapanır. GO şartları: tüm kapsam kalemleri "Done (evidenced)",
  açık **kritik/yüksek bulgu YOK**, quality gates eşikleri karşılandı, parity matrisi tam.
- Aksi halde **NO-GO** + gerekçe + kapatıcı görev listesi.
- Karar RISK-REGISTER ve CHANGELOG'a işlenir; kullanıcıya tek Final Report ile sunulur.

## 5. Governance artefaktları (her büyük adımda güncel tutulur)

| Artefakt | Dosya | Ne zaman güncellenir |
|---|---|---|
| Bu sözleşme | `AGENT-WORKING-AGREEMENT.md` | Kural değişince |
| Mimari karar kayıtları | `ADR/ADR-*.md` | Her mimari karar |
| Değişiklik günlüğü | `CHANGELOG.md` | Her büyük adım |
| Quality gates sonuçları | `QUALITY-GATES-RESULTS.md` | Her verify koşusu |
| Parity-traceability matrisi | `PARITY-TRACEABILITY.md` | Her modül tamamında |
| Risk register + takvim | `RISK-REGISTER.md` | Her yeni/kapanan risk |

## 6. Güvenlik & gizlilik

- Secret sızıntısı yok; audit/log'da PII/şifre maskeli. Dış servise veri gönderilmez.
- Adversaryal güvenlik incelemesi her faz sonu zorunlu; kritik/yüksek bulgu GO'yu bloklar.
