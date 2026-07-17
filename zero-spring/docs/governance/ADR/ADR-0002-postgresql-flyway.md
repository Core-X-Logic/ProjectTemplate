# ADR-0002: PostgreSQL + Flyway

- **Durum:** Accepted
- **Tarih:** 2026-07-17
- **Faz:** Genel

## Bağlam

Kaynak sistem SQL Server + EF Core migration kullanıyor. Hedef veritabanı ve migration aracı seçilmeli.

## Karar

**PostgreSQL 16** + **Flyway** (versioned SQL migration, `ddl-auto=validate` — şemanın tek kaynağı SQL).

## Gerekçe

- Lisans maliyeti sıfır; bulutta her yerde yönetilen sürüm.
- `NULLS NOT DISTINCT` (tenant+username tekilliği), Row-Level Security (F4 tenant derin savunma) gibi
  doğrudan işe yarayan özellikler.
- Flyway düz SQL: EF migration zihniyetine en yakın, ekip için okunabilir. Liquibase'e göre daha az soyutlama.
- Tarihsel 51 EF migration taşınmaz; tek `V1__baseline.sql` ile temiz başlangıç, sonra forward-only versioned.

## Sonuçlar

- (+) `validate` modu şema kaymasını erken yakalar.
- (−) SQL Server→Postgres tip eşleme + Windows→IANA timezone dönüşümü ETL'de gerekli (RISK-REGISTER R-05).
- (−) Flyway undo yok → forward-fix stratejisi (bilinçli).
