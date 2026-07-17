# zero-platform — ASP.NET Zero → Java Spring modernizasyonu

ASP.NET Zero (ABP Framework) tabanlı enterprise SaaS iskeletinin Java 21 / Spring Boot 3.5
ekosisteminde fonksiyonel-parite + modernizasyon karşılığı.

## Dokümanlar

| Belge | İçerik |
|---|---|
| [ANALYSIS.md](ANALYSIS.md) | **A) Analiz Raporu** — modül envanteri, 74 satırlık parite matrisi (Mevcut→Spring), riskler, netleştirme soruları, migration stratejisi, frontend değerlendirmesi |
| [ARCHITECTURE.md](ARCHITECTURE.md) | **B) Hedef Mimari** — Modulith kararı, katmanlar, multi-tenancy, auth, veri modeli, event akışları, observability, ADR özeti |
| [IMPLEMENTATION-PLAN.md](IMPLEMENTATION-PLAN.md) | **C) Uygulama Planı** — 4 faz, her faz için görev + kabul + test kriterleri |
| [CONTRACT-phase1.md](CONTRACT-phase1.md) | **D) Kod Üretim Sözleşmesi** — Faz 1 kesin paket/sınıf/DDL/config sözleşmesi (kod üretim ajanları için bağlayıcı) |
| [QUALITY-GATES.md](QUALITY-GATES.md) | **E) Kalite Kapıları** — DoD, coverage hedefleri, security/performance/production-readiness checklist'leri |

## Kod

- `../backend/` — çalışan Faz 1 Spring Boot iskeleti (auth + tenant + RBAC + seed + Testcontainers IT'leri).
  Lokal çalıştırma ve test için bkz. [../backend/README.md](../backend/README.md).

## Faz 1 durum özeti

- Derleme: **BUILD SUCCESS**
- Test: **15/15 yeşil** (ModularityTests + 14 entegrasyon testi, Testcontainers PostgreSQL 16, ~22s)
- Faz-sonu adversaryal güvenlik incelemesinde 1 kritik + 1 yüksek + 2 orta bulgu tespit edilip
  **düzeltildi ve testle kanıtlandı**; re-review'da açık kritik/yüksek kalmadı. Özet:
  - **[Kritik]** Tenant izolasyonu: yeni `AuthenticatedTenantFilter` ile JWT `tenant` claim otoriter
    yapıldı; `X-Tenant` header ile uyuşmazlık / eksik header / yabancı tenant → 403
    (`TenantEscalationIT` 3/3 kanıtlı). Böylece tenant token'ıyla host/başka-tenant erişimi ve
    host-admin yaratma engellendi.
  - **[Yüksek]** `HibernateTenantFilterAspect` sıralaması `@Order` + `TransactionOrderConfig` ile
    transaction içinde garanti edildi (ikinci savunma hattı).
  - **[Orta]** Refresh rotasyonu atomik (`revokeIfActive`) + reuse-detection kaskadı; prod'da seed
    default kapalı + fail-fast.
  - Ayrıntı: ANALYSIS §3.1 ve QUALITY-GATES §3.
- Bilinçli F2'ye ertelenen artık riskler (residual): Hibernate `@Filter`'ın `findById`/lazy-collection'a
  uygulanmaması (birincil savunma explicit tenant-scoped sorgular), host→tenant impersonation yokluğu,
  tenant lookup cache'i, `jti` blacklist. Tümü belgeli.

## Sonraki adım — açık kararlar

Kapsamı daraltmak için ANALYSIS §3.3'teki 8 netleştirme sorusu yanıtlanmalı (chat, SaaS/ödeme,
GraphQL, MAUI/Web.Mvc, LDAP/OIDC, veri migration, hedef bulut, tenant çözümleme modeli). Bunlar
Opsiyonel bloktaki 30 parite kaleminin kaçının F2-F4'e alınacağını belirler.
