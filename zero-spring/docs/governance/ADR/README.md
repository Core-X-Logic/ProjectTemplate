# Mimari Karar Kayıtları (ADR)

Her ADR bir mimari kararı, bağlamını, gerekçesini ve sonuçlarını kaydeder. Format: MADR (lite).
Durum: `Accepted` · `Superseded by ADR-X` · `Proposed`.

| # | Başlık | Durum | Faz |
|---|---|---|---|
| [0001](ADR-0001-modular-monolith.md) | Modüler monolit (Spring Modulith), mikroservis değil | Accepted | Genel |
| [0002](ADR-0002-postgresql-flyway.md) | PostgreSQL + Flyway (SQL Server + EF yerine) | Accepted | Genel |
| [0003](ADR-0003-tenant-isolation.md) | Shared-DB + Hibernate @Filter + JWT-claim otoriter tenant izolasyonu | Accepted | F1 |
| [0004](ADR-0004-jwt-auth.md) | Kendi JWT auth (Nimbus) + rotate-eden refresh; kısa access | Accepted | F1 |
| [0005](ADR-0005-entity-history-custom.md) | Entity history: custom listener (Hibernate Envers değil) | Accepted | F2 |
| [0006](ADR-0006-frontend-angular.md) | Frontend Angular ile devam | ~~Superseded~~ by 0008 | F2/F4 |
| [0007](ADR-0007-migration-strategy.md) | Greenfield parity build + tek seferlik ETL + big-bang cutover | Accepted | Genel |
| [0008](ADR-0008-frontend-react-vite.md) | Frontend React + Vite + TypeScript (Metronic starter) | Accepted | F2 |
| [0009](ADR-0009-explicit-subscription-status.md) | Explicit `SubscriptionStatus` + domain geçiş metotları | Accepted | F5-A |
| [0010](ADR-0010-billing-provider-spi.md) | `BillingProvider` SPI + registry; gerçek gateway Slice C'de | Accepted | F5-A/C |
| [0011](ADR-0011-webhook-idempotency.md) | Webhook idempotency (`webhook_events` UQ; duplicate/kalıcı hatada 200) | Accepted | F5-C |
| [0012](ADR-0012-subscription-price-snapshot.md) | Abonelikte fiyat snapshot; edition düzenlenebilir | Accepted | F5-A |
| [0013](ADR-0013-billing-period-java-time.md) | `BillingPeriod` + `java.time` (30/365 gün sabitleri terk) | Accepted | F5-A |
| [0014](ADR-0014-server-authoritative-activation.md) | Server-authoritative aktivasyon (webhook + reconciliation) | Accepted | F5-C |
| [0015](ADR-0015-saas-tenant-isolation.md) | SaaS'ta `@Filter` yok; host-only izin + explicit sorgu izolasyonu | Accepted | F5-A |
