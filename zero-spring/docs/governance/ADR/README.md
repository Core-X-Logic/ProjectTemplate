# Mimari Karar Kayıtları (ADR)

Her ADR bir mimari kararı, bağlamını, gerekçesini ve sonuçlarını kaydeder. Format: MADR (lite).
Durum: `Accepted` · `Superseded by ADR-X` · `Proposed`.

Buradaki kararlar **çalışan kodun tasarım gerekçesidir**. Bir kararı değiştirdiğinizde ADR'yi
silmeyin: yeni bir ADR yazın ve eskisini `Superseded by` ile işaretleyin — kodun neden bugünkü
şeklinde olduğu bilgisi, kararın kendisi kadar değerlidir.

| # | Başlık | Durum |
|---|---|---|
| [0001](ADR-0001-modular-monolith.md) | Modüler monolit (Spring Modulith), mikroservis değil | Accepted |
| [0002](ADR-0002-postgresql-flyway.md) | PostgreSQL + Flyway; şemanın tek kaynağı SQL, forward-only | Accepted |
| [0003](ADR-0003-tenant-isolation.md) | Shared-DB + Hibernate `@Filter` + JWT-claim otoriter tenant izolasyonu | Accepted |
| [0004](ADR-0004-jwt-auth.md) | Kendi JWT auth (Nimbus) + rotate-eden refresh; kısa access | Accepted |
| [0005](ADR-0005-entity-history-custom.md) | Entity history: custom listener (Hibernate Envers değil) | Accepted |
| [0008](ADR-0008-frontend-react-vite.md) | Frontend — React + Vite + TypeScript (Metronic starter) | Accepted |
| [0009](ADR-0009-explicit-subscription-status.md) | Explicit `SubscriptionStatus` + domain geçiş metotları | Accepted |
| [0010](ADR-0010-billing-provider-spi.md) | `BillingProvider` SPI + registry | Accepted |
| [0011](ADR-0011-webhook-idempotency.md) | Webhook idempotency (`webhook_events` UQ; duplicate/kalıcı hatada 200) | Accepted |
| [0012](ADR-0012-subscription-price-snapshot.md) | Abonelikte fiyat snapshot; edition düzenlenebilir kalır | Accepted |
| [0013](ADR-0013-billing-period-java-time.md) | `BillingPeriod` + `java.time` (30/365 gün sabitleri yok) | Accepted |
| [0014](ADR-0014-server-authoritative-activation.md) | Server-authoritative aktivasyon (webhook + reconciliation) | Accepted |
| [0015](ADR-0015-saas-tenant-isolation.md) | SaaS'ta `@Filter` yok; host-only izin + explicit sorgu izolasyonu | Accepted |

## Numara boşlukları

- **0006** — silindi. Frontend için Angular kararıydı; aynı gün ADR-0008 ile değiştirildi ve hiç
  uygulanmadı. Yürürlükteki frontend kararı **[ADR-0008](ADR-0008-frontend-react-vite.md)**'dir.
- **0007** — `docs/history/` altına taşındı. Bu şablonun türetildiği projeye özgü bir geçiş
  stratejisi kararıydı (greenfield parity build + tek seferlik ETL + big-bang cutover); yeni bir
  projede karşılığı yoktur.

Yeni ADR eklerken numarayı **0016**'dan devam ettirin; boşlukları doldurmayın.
