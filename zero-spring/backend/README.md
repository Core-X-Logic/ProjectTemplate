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

2. Run the application with the `dev` profile (Windows):

   ```bash
   mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=dev
   ```

   Linux/macOS:

   ```bash
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
   ```

   The profile is **required**, not a convenience. The base configuration deliberately supplies no
   JWT signing key and no seed password (see [Configuration](#configuration-that-has-no-default)),
   and the container ports above (5433 / 6380) only match `application-dev.yml`. Starting without a
   profile fails immediately with an unresolved `JWT_SECRET` — by design, so a deployment cannot
   silently fall back to a key that is public in this repository.

3. Open the API docs:

   - Swagger UI: http://localhost:8080/swagger-ui/index.html
   - OpenAPI JSON: http://localhost:8080/v3/api-docs
   - Actuator health: http://localhost:8080/actuator/health
   - Mailpit UI: http://localhost:8025

## Default users (seeded)

Seeding is controlled by `zero.seed.enabled`, which defaults to **`false`**. The `dev` and `test`
profiles switch it on explicitly; every other environment, including one that starts with no
profile at all, provisions nothing. That default is deliberate — a lost `SPRING_PROFILES_ACTIVE`
must not create a host admin against a production database. Enable it with `SEED_ENABLED=true`.

The password comes from `SEED_ADMIN_PASSWORD`; the `dev` and `test` profiles set it to `Admin123!`
for local use, and that value is rejected on every other profile.

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

Access tokens carry an `aud` claim (`zero.jwt.audience`) and the decoder rejects a token that does
not match it, so a token signed with the same secret by another service is not accepted here.

## Configuration that has no default

These settings intentionally have **no fallback value**. The application refuses to start without
them rather than substituting something that would appear to work:

| Setting | Environment variable | Why there is no default |
|---|---|---|
| `zero.jwt.secret` | `JWT_SECRET` | A committed default means a missing or misspelled `SPRING_PROFILES_ACTIVE` silently signs tokens with a key that is public in this repository's history — anyone could forge a host-admin token. Generate one with `openssl rand -base64 64`. |
| `zero.cors.allowed-origins` (prod) | `CORS_ALLOWED_ORIGINS` | Comma-separated exact origins (`scheme://host[:port]`, no path). Empty means every cross-origin request is refused, which is the safe failure mode. **There is no wildcard form and the application refuses to start if you supply one** — `*`, `https://*.example.com`, or any entry containing `*` fails fast with an explanatory message, because a wildcard would let any site on the internet drive this API with a victim's `Authorization` header. |
| `zero.seed.host-admin-password` | `SEED_ADMIN_PASSWORD` | Only read when seeding is enabled. A blank or dev-default value is refused on every profile. |

The dev and test signing keys are committed and therefore public. They are listed in
`JwtSecretValidator` and refused whenever the `prod` profile is active; the original leaked default
is refused on **every** profile.

### Deployment requirements

- **Terminate TLS at a proxy that overwrites client-supplied `X-Forwarded-*` headers.**
  `server.forward-headers-strategy=framework` is enabled so HSTS is emitted and the rate limiter
  sees the real client IP. Without such a proxy in front, a client can forge both.
- **Set `zero.ratelimit.trusted-proxy-count` to the number of proxies in front of the
  application** (default `1`). The standard nginx idiom
  `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;` *appends* the address it saw, so
  the real client is the **rightmost** entry and everything to its left is client-supplied and
  unverifiable. The limiter counts that many entries from the right. A CDN in front of nginx means
  `2`. A directly exposed deployment must set `0`, which ignores the header entirely — otherwise
  every client picks its own bucket. Note that Spring's own `getRemoteAddr()` reads the *leftmost*
  entry, which is why the limiter resolves the address itself rather than trusting it.
- **PostgreSQL 15 or newer.** The schema uses `UNIQUE ... NULLS NOT DISTINCT`; a Flyway
  `BEFORE_MIGRATE` callback checks this and fails with an explicit message. The check is
  fail-closed: if the server version cannot be read at all, migration is refused rather than
  attempted.
- **Rate limiting is per instance.** Buckets live in each JVM's heap, so N replicas allow
  N x the configured limit in aggregate (`zero.ratelimit.*`).
- **Request bodies on throttled endpoints are capped** at `zero.ratelimit.max-body-bytes`
  (default 16 KB) and refused with `413`. A body the limiter cannot parse is a body whose username
  bucket cannot be charged, so it is rejected rather than exempted.
- **The OpenAPI description is disabled under the `prod` profile.** `/v3/api-docs` and
  `/swagger-ui/**` are both turned off in `application-prod.yml` *and* dropped from the
  `permitAll` list in `SecurityConfig`, so they are not anonymously readable in production. They
  remain available on every other profile, which is what the CI typed-client gate uses.

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
