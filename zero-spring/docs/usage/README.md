# Kullanım Rehberleri (usage/)

Şablonu klonlayan ekibin **kullanım + AI ile çalışma** disiplini. "Nasıl başlarım, nasıl geliştiririm,
nasıl güvenli deploy ederim, AI ajanlarına nasıl doğru görev veririm?" sorularının cevabı.

> Mimari/kurallar için üst dizine bak (`../README.md` doküman haritası). Bu klasör **operasyonel
> netlik** verir: adım adım, her bölümde başarı ölçütü.

| Doküman | Amaç | Kime |
|---|---|---|
| [QUICKSTART.md](QUICKSTART.md) | 15–30 dk'da lokalde ayağa kaldırma + ilk smoke + sık kurulum hataları | Yeni klonlayan geliştirici |
| [WORKING-WITH-AI.md](WORKING-WITH-AI.md) | Codex/Claude çalışma modeli, hangi ajan/rol ne zaman, paralel + çakışma önleme | AI ile geliştiren herkes |
| [PROMPT-CATALOG.md](PROMPT-CATALOG.md) | Kopyala-kullan role-based prompt seti (backend/frontend/security/review/release) | Ajanı olan/olmayan geliştirici |
| [EVIDENCE-AND-GATES.md](EVIDENCE-AND-GATES.md) | "Tamamlandı" için minimum kanıt, negatif kanıt, drift + false-green önleme | Kapatan herkes |
| [OPERATOR-HANDOFF.md](OPERATOR-HANDOFF.md) | Geliştirici↔operatör sınırı, secret sınırları, prod provisioning, deploy/rollback | Operatör + devreden geliştirici |
| [FIRST-7-DAYS.md](FIRST-7-DAYS.md) | Gün bazlı onboarding: önce ne anlaşılmalı, hangi risk erken kapanmalı | Yeni devralan ekip |
| [CHEAT-SHEET.md](CHEAT-SHEET.md) | 1 sayfalık ilk gün komut + karar özeti (yazdır/açık tut) | Herkes, her gün |

## Önerilen okuma sırası

1. **İlk saat:** CHEAT-SHEET → QUICKSTART (çalıştır).
2. **İlk gün AI kullanacaksan:** WORKING-WITH-AI → PROMPT-CATALOG.
3. **Kod yazmadan önce:** EVIDENCE-AND-GATES + `../SECURITY.md`.
4. **Onboarding boyunca:** FIRST-7-DAYS (gün bazlı).
5. **Prod'a çıkarken:** OPERATOR-HANDOFF + `../RELEASE-RUNBOOK.md`.
