# TR Ödeme Fazı — Scope-lock sözleşmesi (P2')

**Statü:** bağlayıcı. Kapsam dışı iş yapılacaksa **önce bu dosya değişir**.
**Giriş hattı (ölçüldü):** backend **461 test** (168 unit + 293 IT), CI 8/8 (`ea0e13f`, run 29743681191), lokal = CI.
**Karar kaynağı:** ADR-0017 — sağlayıcılar **PayTR + iyzico**; Stripe uyuyan global-pazar adaptörü (**iş yasak**); PayPal yok.

## 1. Kapsam

| # | İş | Ne kapatır | Durum |
|---|---|---|---|
| **P2'-A** | Çoklu-sağlayıcı registry + PayTR intake | PayTR bildirim hattı, `OK` sözleşmesi, failed→success | ✅ `ea0e13f` |
| **P2'-B** | iyzico: `IyzicoBillingProvider` (SDK 2.0.142), `/api/billing/webhook/iyzico` (`X-IYZ-SIGNATURE-V3`), CF initialize, **retrieve-otoriter** doğrulama, `iyziReferenceCode` dedup, **mutabakat job'u** (ShedLock; `NOT_PAID/FAILED` tara → sağlayıcı sorgusu; PayTR tarafı sorgu API'si yoksa yalnız iyzico-retrieve + PayTR runbook ağı) | PROD-R43 | ✅ **BİTTİ** (`ef82ef0`; review: 1 HIGH double-activation yarışı dahil 4 bulgu commit öncesi kapandı) |
| **Issue #1** | Tenant create → idempotent admin user+role bootstrap. **Migration YOK** (runtime seed). | Issue #1 | ✅ **BİTTİ** (`20247d5` backend + `7914373` frontend; öncesi-kırmızı 401 kanıtı + canlı smoke + re-boot smoke) |
| **P2'-C** | Checkout UI (PayTR iframe + iyzico CF sayfası; typed client, üçlü kilit) + **iki sandbox'ta canlı smoke** | PROD-R44, PROD-R40 (yeni biçimi) | **UI ✅ BİTTİ** (`b3c8c4a`: dialog + hand-off + sonuç rotaları, "activated" asla denmez, 135 FE testi; PROD-R40 kapandı) · smoke: **operatör-bağımlı AÇIK** — harness hazır (`scripts/sandbox-smoke.sh` + runbook §3.10); PayTR mağaza + iyzico sandbox merchant hesaplarını yalnız operatör açabilir; script PASS kaydı girmeden PROD-R44/R47 kapanamaz ve faz COMPLETE ilan edilemez |
| **P2'-D** | Recurring (PayTR kayıtlı-kart tekrarlayan + iyzico `/v2/subscription`) — `SubscriptionLifecycleJob`'a bağlanır | PROD-R38 sınıfı | backlog, bu sözleşmede AÇILMAZ |

## 2. Kapsam dışı (yasak)

Stripe'a herhangi bir dokunuş (mekanik registry hariç) · PayPal · yeni ürün özelliği · yeni Modulith
modülü (`saas.billing` alt paketi kalır) · TR e-fatura/e-arşiv entegrasyonu (ayrı iş kararı) ·
frontend'e checkout dışı dokunuş.

## 3. Done kriterleri — sayılabilir

| # | Kriter |
|---|---|
| P2'-B | `/webhook/iyzico` dörtlü gate kaydı tam (permitAll + EndpointPolicy + INTENTIONALLY_ANONYMOUS + ratelimit). Webhook TEK BAŞINA aktive etmez ise retrieve-doğrulama, callback TEK BAŞINA hiç aktive etmez — otoriter adım retrieve. Duplicate → 200 tek işleme. Mutabakat job'u stuck kaydı bulur, ShedLock kilitli, boş sette **vacuity-guard'lı**. |
| Issue #1 | Tenant create → admin login mutlu yol IT; **negatif kanıt eski kodda: login imkânsız**; canlı smoke (seed değişikliği kuralı). Idempotent: aynı tenant'a ikinci create/seed ikinci admin ÜRETMEZ. |
| P2'-C UI | İki sağlayıcı için ödeme başlatma ekranı; `<Can>` + route guard + `@PreAuthorize` üçlüsü; davranış testi. |
| P2'-C smoke | Sandbox'ta gerçek get-token / CF-initialize → bildirim/webhook → aktivasyon zinciri, iki sağlayıcıda. Kimlik bilgisi yoksa: hazırlık scripti + runbook adımı teslim edilir, PROD-R44 **AÇIK kalır** ve yapı tamamlanma kararında blocker sayılır. |

## 4. Negatif kanıt zorunlulukları

| Neyi bozacaksın | Ne kırmızıya dönmeli |
|---|---|
| iyzico V3 imza doğrulamasını atla | invalid-signature IT |
| iyzico dedup'ı sil | duplicate IT |
| Retrieve'i atlayıp webhook'la aktive et | retrieve-otoriter IT (webhook-only akış aktive etmemeli) |
| Mutabakat job'unu kapat | stuck-payment IT (kayıt NOT_PAID kalır) |
| Issue #1 seed'ini kaldır | admin-login IT eski kodda kırmızı (bu, düzeltme ÖNCESİ ölçülür) |
| Checkout UI'da guard'ı kaldır | route-guard davranış testi |

## 5. Risk sınıfları

Para yolu değişiklikleri **yüksek**: her biri mutasyon kanıtı + stack-review + tam `clean verify` +
CI koşusu ister. UI **orta**. Doc **düşük**. `release` job'ı hâlâ placeholder — hiçbir yeşil "deploy edildi" demek değildir.

**Kesim sırası:** zaman biterse önce P2'-C UI düşer, sonra mutabakat job'u. P2'-B çekirdeği ve Issue #1 kesilemez.
