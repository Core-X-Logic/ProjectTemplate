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
