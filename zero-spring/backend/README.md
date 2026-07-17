# zero-platform — Backend

Multi-tenant startup backend template. Java 21, Spring Boot 3.5, Spring Modulith, PostgreSQL, Flyway, JWT auth.

## Requirements

- JDK 21
- Docker (Desktop) — for local infrastructure and integration tests (Testcontainers)
- No local Maven needed — the Maven Wrapper (`mvnw` / `mvnw.cmd`) is included

## Quick start

1. Start local infrastructure (PostgreSQL, Redis, Mailpit):

   ```bash
   docker compose up -d
   ```

2. Run the application (Windows):

   ```bash
   mvnw.cmd spring-boot:run
   ```

   Linux/macOS:

   ```bash
   ./mvnw spring-boot:run
   ```

3. Open the API docs:

   - Swagger UI: http://localhost:8080/swagger-ui/index.html
   - OpenAPI JSON: http://localhost:8080/v3/api-docs
   - Actuator health: http://localhost:8080/actuator/health
   - Mailpit UI: http://localhost:8025

## Default users (seeded)

Seeding is controlled by `zero.seed.enabled` (default `true`). The password comes from the
`SEED_ADMIN_PASSWORD` environment variable (dev-only default: `Admin123!`).

| Context               | Username | How to log in                                             |
|-----------------------|----------|-----------------------------------------------------------|
| Host (no tenant)      | `admin`  | `POST /api/auth/login` without the `X-Tenant` header      |
| Tenant `default`      | `admin`  | `POST /api/auth/login` with header `X-Tenant: default`    |

The host admin has all permissions (including `tenants.manage`). The tenant admin has all
permissions except `tenants.manage`.

## Multi-tenancy

Tenant selection is done per request via the `X-Tenant` header (configurable through
`zero.multitenancy.header`). Requests without the header run in the host context. Data isolation
is enforced with Hibernate filters (`tenantFilter` / `hostFilter`) on tenant-aware entities.

## Authentication

- `POST /api/auth/login` → access token (JWT, HS512) + refresh token
- `POST /api/auth/refresh` → token rotation (old refresh token is revoked)
- `POST /api/auth/logout` → revokes the refresh token
- `GET /api/auth/me` → current user info

In production the `JWT_SECRET` environment variable (base64, at least 64 bytes) is mandatory —
the bundled default is dev-only.

## Tests

Integration tests (`*IT`) use Testcontainers and require Docker:

```bash
mvnw.cmd verify
```

Unit tests only:

```bash
mvnw.cmd test
```

## Architecture (short)

Spring Modulith modules under `com.mycompanyname.zero`:

- `shared` — open module: audited base entity, error model, `ProblemDetail` exception handling, `TenantContext`
- `tenancy` — tenant entity/service, `X-Tenant` resolver filter, Hibernate tenant-filter aspect
- `identity` — users, roles, permissions, JWT auth (login / refresh rotation / lockout), user CRUD
- `config` / `seed` — cross-cutting configuration and idempotent data seeding

Database schema is managed by Flyway (`src/main/resources/db/migration`), starting with
`V1__baseline.sql`. Module boundaries are verified by `ModularityTests`.

More documentation: see [`../docs/`](../docs/) — including the binding Phase 1 contract
([`CONTRACT-phase1.md`](../docs/CONTRACT-phase1.md)).
