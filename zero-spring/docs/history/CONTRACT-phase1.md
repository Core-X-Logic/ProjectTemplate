# Phase 1 Code Generation Contract — zero-platform

Bu dosya kod üretim ajanları için BAĞLAYICI sözleşmedir. Paket adları, sınıf adları, imzalar,
DDL ve config anahtarları buradan aynen alınır. Sapma yasak.

## 0. Identity / Coordinates

- Root dir: `D:\Private\Cafer AYDIN\StartupProjectTemplate\zero-spring\backend`
- Maven: groupId `com.mycompanyname`, artifactId `zero-platform`, version `0.1.0-SNAPSHOT`
- Base package: `com.mycompanyname.zero`
- Java 21, Spring Boot **3.5.5** (parent), Spring Modulith BOM **1.4.5**, springdoc **2.8.5**,
  logstash-logback-encoder **8.0**, Testcontainers via `spring-boot-testcontainers` + BOM.
- Build: Maven Wrapper already present (`mvnw.cmd`). Windows host.

## 1. Maven dependencies (exact)

Parent: `spring-boot-starter-parent:3.5.5`.
`dependencyManagement`: `spring-modulith-bom:1.4.5` (pom, import).

Dependencies:
- spring-boot-starter-web, spring-boot-starter-security, spring-boot-starter-oauth2-resource-server
- spring-boot-starter-data-jpa, spring-boot-starter-validation, spring-boot-starter-actuator
- spring-boot-starter-data-redis, spring-boot-starter-cache
- spring-boot-starter-aop
- org.springframework.modulith:spring-modulith-starter-core
- org.flywaydb:flyway-core + org.flywaydb:flyway-database-postgresql
- org.postgresql:postgresql (runtime)
- org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.5
- io.micrometer:micrometer-registry-prometheus (runtime)
- net.logstash.logback:logstash-logback-encoder:8.0
- org.projectlombok:lombok (optional, annotationProcessor da ekle)
- Test: spring-boot-starter-test, spring-security-test, spring-boot-testcontainers,
  org.testcontainers:postgresql, org.testcontainers:junit-jupiter,
  org.springframework.modulith:spring-modulith-starter-test (scope test)

Plugins: spring-boot-maven-plugin; maven-compiler-plugin (release 21, annotationProcessorPaths: lombok);
surefire (default) + failsafe (`*IT.java`, goals integration-test+verify).

## 2. Package / file tree (main)

```
com.mycompanyname.zero
├─ ZeroPlatformApplication.java            @SpringBootApplication @Modulith? → sadece @SpringBootApplication
├─ shared/                                 (modulith OPEN module: package-info @ApplicationModule(type = Type.OPEN))
│  ├─ package-info.java
│  ├─ domain/AbstractAuditedEntity.java    @MappedSuperclass: Long id (IDENTITY), Instant createdAt, String createdBy,
│  │                                        Instant updatedAt, String updatedBy — @CreatedDate vb. + @EntityListeners(AuditingEntityListener)
│  ├─ domain/DomainException.java          RuntimeException; fields: ErrorCode code, String message; static factories
│  ├─ domain/ErrorCode.java                enum: NOT_FOUND, VALIDATION, UNAUTHORIZED, FORBIDDEN, CONFLICT, TENANT_UNKNOWN, LOGIN_FAILED, ACCOUNT_LOCKED
│  ├─ web/GlobalExceptionHandler.java      @RestControllerAdvice → ProblemDetail (RFC 9457). DomainException→mapped status; MethodArgumentNotValidException→400 field errors
│  └─ tenant/TenantContext.java            final class; static ThreadLocal<Long>; methods: setTenantId(Long), getTenantId() → Long|null, isHost() → boolean, clear()
├─ config/
│  ├─ JpaAuditingConfig.java               @EnableJpaAuditing(auditorAwareRef="auditorAware"); AuditorAware<String> bean → SecurityContext'ten username, yoksa "system"
│  ├─ OpenApiConfig.java                   @OpenAPIDefinition + bearer securityScheme ("bearerAuth")
│  ├─ CacheConfig.java                     @EnableCaching
│  └─ JwtProperties.java                   @ConfigurationProperties(prefix="zero.jwt"): String secret; Duration accessTokenTtl; Duration refreshTokenTtl; String issuer
├─ tenancy/
│  ├─ package-info.java
│  ├─ Tenant.java                          @Entity "tenants": extends AbstractAuditedEntity; String name (unique, lowercase), String displayName, boolean active
│  ├─ TenantRepository.java                JpaRepository<Tenant,Long>; Optional<Tenant> findByNameIgnoreCase(String)
│  ├─ TenantService.java                   @Transactional; createTenant(CreateTenantRequest)→TenantDto (identity'ye admin user açtırmaz Faz1; sadece tenant), setActive(id,boolean), list(), getByNameOrThrow(String)
│  ├─ TenantResolverFilter.java            OncePerRequestFilter, order highest: header "X-Tenant" (config zero.multitenancy.header) → TenantRepository lookup (aktif değilse 403 ProblemDetail) → TenantContext.set; finally clear. Header yoksa host context.
│  ├─ HibernateTenantFilterAspect.java     @Aspect @Component; @Around("within(@org.springframework.stereotype.Service *)") + tx aktifse EntityManager.unwrap(Session): tenant varsa enableFilter("tenantFilter").setParameter("tenantId", ...), yoksa enableFilter("hostFilter")
│  └─ web/TenantController.java            /api/tenants: GET list (perm tenants.manage), POST create, PUT /{id}/activate, PUT /{id}/deactivate
│  └─ web/dto: CreateTenantRequest(record: name @NotBlank @Pattern([a-z0-9-]{2,30}), displayName @NotBlank), TenantDto(record: id,name,displayName,active,createdAt)
├─ identity/
│  ├─ package-info.java
│  ├─ domain/User.java                     @Entity "users": extends AbstractAuditedEntity; Long tenantId (nullable=host user); String username, email, passwordHash;
│  │                                        boolean active; int failedLoginAttempts; Instant lockoutEndAt (nullable); Set<Role> roles (ManyToMany, join table user_roles)
│  │                                        @FilterDef(name="tenantFilter", parameters=@ParamDef(name="tenantId", type=Long.class)) @Filter(name="tenantFilter", condition="tenant_id = :tenantId")
│  │                                        @FilterDef(name="hostFilter") @Filter(name="hostFilter", condition="tenant_id is null")   ← FilterDef'ler package-info'da da olabilir; TEK yerde tanımla (package-info.java on identity/domain), entity'lerde @Filter
│  ├─ domain/Role.java                     @Entity "roles": extends AbstractAuditedEntity; Long tenantId nullable; String name; boolean isStatic;
│  │                                        @ElementCollection Set<String> permissions → table role_permissions(role_id, permission); aynı iki @Filter
│  ├─ domain/RefreshToken.java             @Entity "refresh_tokens": Long id; Long userId; String tokenHash (unique); Instant expiresAt; boolean revoked; Instant createdAt
│  ├─ domain/AppPermissions.java           final class, String sabitleri: USERS_READ="users.read", USERS_CREATE="users.create", USERS_UPDATE="users.update", USERS_DELETE="users.delete", ROLES_MANAGE="roles.manage", TENANTS_MANAGE="tenants.manage"; static Set<String> all()
│  ├─ repo/UserRepository.java             Optional<User> findByTenantIdAndUsernameIgnoreCase(Long,String); Optional<User> findByUsernameIgnoreCaseAndTenantIdIsNull(String); List<User> findAll... (filter zaten kısıtlar; ekstra explicit tenant metodları kullan)
│  ├─ repo/RoleRepository.java, repo/RefreshTokenRepository.java (findByTokenHash, deleteByUserId, revokeAllByUserId @Modifying)
│  ├─ auth/JwtService.java                 issueAccessToken(User, Set<String> authorities) → String (Nimbus JWSSigner HS512 veya spring-security-oauth2-jose JwtEncoder — NimbusJwtEncoder + ImmutableSecret KULLAN); claims: sub=userId, username, tenant (nullable long), authorities (list), iss, iat, exp
│  ├─ auth/SecurityConfig.java             SecurityFilterChain: stateless, csrf off, permitAll: /api/auth/login, /api/auth/refresh, /actuator/health/**, /v3/api-docs/**, /swagger-ui/**; anyRequest authenticated; oauth2ResourceServer jwt: NimbusJwtDecoder.withSecretKey(HS512) + jwtAuthenticationConverter → claim "authorities" → SimpleGrantedAuthority (prefix YOK); PasswordEncoder bean: DelegatingPasswordEncoder default bcrypt(12); TenantResolverFilter'ı addFilterBefore(BearerTokenAuthenticationFilter.class)
│  ├─ auth/AuthService.java                @Transactional; login(LoginRequest)→TokenPairDto: tenant çöz (TenantContext'ten), user bul, lockout kontrol (locked→ACCOUNT_LOCKED), pw doğrula (yanlışsa failedAttempts++, >=5 → lockoutEndAt=now+5m), authorities = roles.name(ROLE_ prefix'siz düz "role:Admin" DEĞİL — sadece role adı DEĞİL: authorities = permissions ∪ ("ROLE_"+roleName)), issue access + refresh (SecureRandom 256-bit → SHA-256 hash DB'ye, raw client'a); refresh(RefreshRequest)→ rotate (eskiyi revoke, yenisini yaz; revoked/expired ise UNAUTHORIZED); logout(refreshToken)→revoke
│  ├─ auth/CurrentUser.java                final class: static Long userId() (Jwt principal sub), static Long tenantId(), SecurityContext'ten
│  ├─ user/UserService.java                @Transactional; createUser(CreateUserRequest)→UserDto (TenantContext tenant'ına yazar; username unique per tenant; roller atanır); update, delete (soft değil Faz1 — hard delete + ileride soft), list(pageable), getById; assignRoles
│  ├─ web/AuthController.java              POST /api/auth/login (body LoginRequest: usernameOrEmail @NotBlank, password @NotBlank), POST /api/auth/refresh (RefreshRequest: refreshToken), POST /api/auth/logout, GET /api/auth/me → MeDto(id, username, email, tenantId, roles, permissions)
│  ├─ web/UserController.java              /api/users: GET (perm users.read, Pageable), GET /{id}, POST (users.create), PUT /{id} (users.update), DELETE /{id} (users.delete) — @PreAuthorize("hasAuthority('users.read')") vb.
│  └─ web/dto: LoginRequest, RefreshRequest, TokenPairDto(accessToken, refreshToken, expiresInSeconds), CreateUserRequest(username, email @Email, password @Size(min=8), roleNames Set<String>), UpdateUserRequest, UserDto(record: id, username, email, active, tenantId, roles), MeDto — hepsi record
└─ seed/DataSeeder.java                    @Component @Profile("!test-noseed") ApplicationRunner @Transactional; idempotent (host admin var mı kontrol):
                                            1) host Role "Admin" (static, tüm AppPermissions.all())
                                            2) host user "admin" pw=config zero.seed.host-admin-password, rol Admin
                                            3) Tenant "default" aktif; tenant Role "Admin" (tenants.manage HARİÇ tüm permler); tenant user "admin" aynı pw
                                            Seed sırasında TenantContext'i elle set/clear et.
```

`package-info.java` modulith notu: `shared` → `@ApplicationModule(type = ApplicationModule.Type.OPEN)`.
`identity` → `@ApplicationModule(allowedDependencies = {"shared", "tenancy"})`. `tenancy` → `allowedDependencies = {"shared"}`.
`config` ve `seed` paketleri modulith modülü sayılmasın diye: bunları `shared` altına TAŞIMA — kökte bırak; Modulith kök paketin direkt alt paketlerini modül sayar; `config` ve `seed` OPEN ilan et (package-info ile) ve ArchUnit'e dokunma.

## 3. Flyway — `src/main/resources/db/migration/V1__baseline.sql` (exact DDL)

```sql
create table tenants (
  id bigint generated by default as identity primary key,
  name varchar(30) not null,
  display_name varchar(128) not null,
  active boolean not null default true,
  created_at timestamptz not null default now(),
  created_by varchar(64),
  updated_at timestamptz,
  updated_by varchar(64),
  constraint uq_tenants_name unique (name)
);

create table users (
  id bigint generated by default as identity primary key,
  tenant_id bigint references tenants(id),
  username varchar(64) not null,
  email varchar(256) not null,
  password_hash varchar(256) not null,
  active boolean not null default true,
  failed_login_attempts int not null default 0,
  lockout_end_at timestamptz,
  created_at timestamptz not null default now(),
  created_by varchar(64),
  updated_at timestamptz,
  updated_by varchar(64),
  constraint uq_users_tenant_username unique nulls not distinct (tenant_id, username)
);
create index ix_users_tenant on users(tenant_id);

create table roles (
  id bigint generated by default as identity primary key,
  tenant_id bigint references tenants(id),
  name varchar(64) not null,
  is_static boolean not null default false,
  created_at timestamptz not null default now(),
  created_by varchar(64),
  updated_at timestamptz,
  updated_by varchar(64),
  constraint uq_roles_tenant_name unique nulls not distinct (tenant_id, name)
);

create table role_permissions (
  role_id bigint not null references roles(id) on delete cascade,
  permission varchar(128) not null,
  primary key (role_id, permission)
);

create table user_roles (
  user_id bigint not null references users(id) on delete cascade,
  role_id bigint not null references roles(id) on delete cascade,
  primary key (user_id, role_id)
);

create table refresh_tokens (
  id bigint generated by default as identity primary key,
  user_id bigint not null references users(id) on delete cascade,
  token_hash varchar(64) not null,
  expires_at timestamptz not null,
  revoked boolean not null default false,
  created_at timestamptz not null default now(),
  constraint uq_refresh_tokens_hash unique (token_hash)
);
create index ix_refresh_tokens_user on refresh_tokens(user_id);
```

## 4. application.yml (keys — exact)

```yaml
spring:
  application.name: zero-platform
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/zero}
    username: ${DB_USER:zero}
    password: ${DB_PASSWORD:zero}
  jpa:
    hibernate.ddl-auto: validate
    open-in-view: false
  flyway.enabled: true
  cache.type: simple
  data.redis:
    host: ${REDIS_HOST:localhost}
    port: 6379
zero:
  multitenancy.header: X-Tenant
  jwt:
    secret: ${JWT_SECRET:ZGV2LW9ubHktc2VjcmV0LWtleS1jaGFuZ2UtaW4tcHJvZC1taW4tNjQtYnl0ZXMtbG9uZy1wbGVhc2UtY2hhbmdlLW1lLW5vdy0hIQ==}   # base64, >=64 byte; prod'da ZORUNLU env
    access-token-ttl: 15m
    refresh-token-ttl: 7d
    issuer: zero-platform
  seed:
    enabled: true
    host-admin-password: ${SEED_ADMIN_PASSWORD:Admin123!}
management:
  endpoints.web.exposure.include: health,info,metrics,prometheus
  endpoint.health.probes.enabled: true
```

`application-dev.yml`: logging pretty; `application-prod.yml`: JSON logging aktif (logback-spring profilleri).

## 5. docker-compose.yml (backend/ dizininde)

Services: `postgres` (postgres:16-alpine, env zero/zero/zero, port 5432, healthcheck pg_isready, volume),
`redis` (redis:7-alpine, port 6379), `mailpit` (axllent/mailpit, 1025/8025) — app servisi YOK Faz1 (lokalde mvnw ile koşulur), ama `Dockerfile` (multi-stage: maven build → eclipse-temurin:21-jre-alpine, non-root user) yaz.

## 6. Tests (src/test/java) — exact class names

- `com.mycompanyname.zero.ModularityTests` — `ApplicationModules.of(ZeroPlatformApplication.class).verify()`
- `com.mycompanyname.zero.AbstractIntegrationIT` — @SpringBootTest(webEnvironment=RANDOM_PORT) + @Testcontainers değil: singleton container pattern — static PostgreSQLContainer 16-alpine, @DynamicPropertySource ile datasource; `spring.cache.type=simple`
- `com.mycompanyname.zero.identity.AuthFlowIT` extends AbstractIntegrationIT — TestRestTemplate:
  1) host admin login (admin/Admin123!) → 200, access+refresh dolu
  2) yanlış şifre → 401 ProblemDetail
  3) /api/auth/me bearer ile → username=admin, tenantId=null, permissions içinde users.read
  4) refresh → yeni çift; eski refresh tekrar → 401 (rotation)
  5) token'sız /api/users → 401
- `com.mycompanyname.zero.tenancy.TenantIsolationIT` extends AbstractIntegrationIT:
  1) X-Tenant: default + admin login → tenant admin token
  2) tenant token ile GET /api/users → sadece default tenant kullanıcıları (host admin GÖRÜNMEZ)
  3) host token ile POST /api/tenants (yeni tenant "acme") → 201; X-Tenant: acme login → tenant'ta user yok → 401 (seed yok)
  4) tenant token ile GET /api/tenants → 403 (tenants.manage yok)
- `com.mycompanyname.zero.identity.UserCrudIT` — create/list/update/delete akışı + duplicate username 409

Not: testlerde seed aktif (default profile). Docker gerektiğinden hepsi `*IT` → failsafe.

## 7. CI — `zero-spring/.github/workflows/ci.yml`

jobs.build: ubuntu-latest; checkout; setup-java temurin 21 + cache maven; `./mvnw -B -ntp verify` (working-directory: backend); artifact: surefire+failsafe raporları.

## 8. README.md (backend) — içerik başlıkları

Gereksinimler (JDK21, Docker), `docker compose up -d`, `mvnw.cmd spring-boot:run`, Swagger URL, default kullanıcılar (host admin / tenant default admin, şifre env), test komutu, mimari kısa özet + docs/ linkleri.

## 9. Yasaklar / kurallar

- TÜM dosya yazımları Write tool ile, tam yol.
- Lombok sadece @Getter/@Setter/@RequiredArgsConstructor/@Slf4j; @Data YASAK (entity'lerde equals/hashCode sorunu).
- DTO'lar record. Mapping elle (MapStruct yok Faz1).
- Hiçbir yerde gerçek secret yok; dev default'ları belirgin şekilde "dev-only".
- Exception mesajları İngilizce; kod yorumları minimum.
