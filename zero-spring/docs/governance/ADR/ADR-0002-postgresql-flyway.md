# ADR-0002: PostgreSQL + Flyway

- **Durum:** Accepted
- **Tarih:** 2026-07-17

## Bağlam

Veritabanı ve şema versiyonlama aracı seçilmeli. Şemanın tek kaynağının ne olacağı (ORM mi, SQL mi)
ve migration'ların geri alınabilir olup olmayacağı bu kararla birlikte sabitlenir.

## Karar

**PostgreSQL 16** + **Flyway** (versioned SQL migration, `ddl-auto=validate` — şemanın tek kaynağı SQL).

## Gerekçe

- Lisans maliyeti sıfır; bulutta her yerde yönetilen sürüm.
- `NULLS NOT DISTINCT` (tenant+username tekilliği), Row-Level Security (tenant derin savunma) gibi
  doğrudan işe yarayan özellikler.
- Flyway düz SQL: şema değişikliği okunabilir ve gözden geçirilebilir; Liquibase'e göre daha az soyutlama.
- Tek `V1__baseline.sql` ile temiz başlangıç, sonrası forward-only versioned.

## Sonuçlar

- (+) `validate` modu şema kaymasını erken yakalar.
- (+) Şemanın tek kaynağı SQL — ORM anotasyonu ile veritabanı sessizce ayrışamaz.
- (−) Flyway undo yok → forward-fix stratejisi (bilinçli; RELEASE-RUNBOOK §4).
