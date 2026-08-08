package com.mycompanyname.zero;

import com.mycompanyname.zero.tenancy.HibernateTenantFilterAspect;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base class for integration tests. Uses the singleton-container pattern: one
 * PostgreSQL container is started once per JVM and shared by every subclass,
 * so the Spring context (and the idempotent seed) is reused across IT classes.
 *
 * <p><b>Two database identities, on purpose</b> (RLS baseline step 1; ADR-0018). Flyway connects as
 * the container's superuser — migrations create tables, roles and policies. The application
 * connects as {@link #APP_DB_USERNAME}, which {@code V11__app_role.sql} declares
 * {@code NOSUPERUSER NOBYPASSRLS}.
 *
 * <p>⚠️ <b>Do not "simplify" this with {@code @ServiceConnection}.</b> That binds the application to
 * the container's superuser, and a superuser bypasses every row-level policy — so every isolation
 * assertion would turn <em>false green</em>: passing while nothing constrains anybody. The same is
 * true of pointing {@code spring.datasource.*} back at {@code POSTGRES.getUsername()}. The split is
 * the evidence; without it there is none.
 *
 * <p>⚠️ <b>Connection budget.</b> {@link #APP_DB_USERNAME} is {@code NOSUPERUSER}, so it cannot draw
 * on the slots Postgres holds back via {@code superuser_reserved_connections} the way the previous
 * single superuser identity could. {@code maximum-pool-size} is deliberately left at the base 10
 * (application-test.yml keeps {@code minimum-idle: 0} so idle contexts hoard nothing) — but if the
 * suite ever does exhaust connections, the symptom surfaces as an unrelated failure in a
 * late-building context ("Unable to determine Dialect"), not as a clear pool error.
 *
 * <p>The role is created <em>here</em> rather than left to the migration, with a password that is
 * deliberately NOT the one {@code V11__app_role.sql} commits. Reason: {@code ProdApiDocsExposureIT}
 * boots the real {@code prod} profile, and {@code AppDbCredentialsValidator} refuses the committed
 * migration password under it — exactly as {@code JwtSecretValidator} refuses the committed signing
 * keys, which is why that IT supplies its own. The migration's role creation is idempotent, so it
 * skips creation here and applies only the grants; its {@code CREATE ROLE} branch is exercised by a
 * dev database and by the {@code migration-drift} CI gate, both of which start with no such role.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntegrationIT {

    protected static final String TENANT_HEADER = "X-Tenant";
    protected static final String SEED_ADMIN_USERNAME = "admin";
    protected static final String SEED_ADMIN_PASSWORD = "Admin123!";

    /** The runtime role every IT's application context connects as. See the class javadoc. */
    protected static final String APP_DB_USERNAME = "zero_app";

    /**
     * Test-scaffolding password. It only ever exists inside a Testcontainers instance that lives for
     * one JVM, so it is not a credential to anything — see {@code AppDbCredentialsValidator} for why
     * this one is not on the prod refusal list while the migration's is.
     */
    private static final String APP_DB_PASSWORD = "zero_app_testcontainer_only";

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
        createApplicationRole();
    }

    /**
     * Creates the application's login role as the container superuser, before Spring — and therefore
     * before Flyway — touches the database. Idempotent for the same reason the migration is: a
     * Postgres role is a cluster-level object, not a database-level one.
     */
    private static void createApplicationRole() {
        String sql = """
                DO $$
                BEGIN
                  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '%s') THEN
                    CREATE ROLE %s LOGIN PASSWORD '%s'
                      NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
                  END IF;
                END $$;""".formatted(APP_DB_USERNAME, APP_DB_USERNAME, APP_DB_PASSWORD);
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException ex) {
            // Fail here with the reason rather than let every context fail later with
            // "FATAL: role \"zero_app\" does not exist", which points at the wrong file.
            throw new IllegalStateException(
                    "Could not create the '" + APP_DB_USERNAME + "' role in the test container; the "
                            + "application identity cannot be separated from the migration identity.", ex);
        }
    }

    @DynamicPropertySource
    static void registerDynamicProperties(DynamicPropertyRegistry registry) {
        // Migrations: the container superuser. Also the owner of every table it creates.
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        // Application runtime: the NOSUPERUSER NOBYPASSRLS role. Same database, different identity.
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_DB_USERNAME);
        registry.add("spring.datasource.password", () -> APP_DB_PASSWORD);
        registry.add("spring.cache.type", () -> "simple");
    }

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate databaseContextJdbcTemplate;

    @Autowired
    private PlatformTransactionManager databaseContextTransactionManager;

    private TransactionTemplate databaseContextTransactions;

    // -------------------------------------------------------------------------------------------
    // Announcing a database context from the TEST thread
    //
    // A test thread is not a @Service, so HibernateTenantFilterAspect never runs for it: a plain
    // `userRepository.findByTenantIdAndUsernameIgnoreCase(...)` in an IT opens its own transaction
    // with NO app.current_tenant and NO app.is_host. Against a policed table that answers 0 rows —
    // correctly, that is the fail-closed guarantee — so from V12 on such a read has to say which
    // context it is making its claim in. These two helpers are the only sanctioned way to do it.
    //
    // ⚠️ They are NOT a way to opt out of a policy. Wrapping an assertion in asHostDatabase makes it
    // a claim about the HOST view (ADR-0018: host sees every row); a claim about a tenant's own view
    // must use inTenantDatabase, or the test proves something weaker than it says. Anything that is
    // about the policy ITSELF belongs in tenancy/RlsIdentityIsolationIT, which binds the settings
    // with the aspect's own constants rather than through here.
    // -------------------------------------------------------------------------------------------

    /**
     * Runs {@code body} in one transaction that names {@code tenantId} to the row-level policies, so
     * repository reads and writes issued from the test thread see exactly what that tenant sees.
     */
    protected <T> T inTenantDatabase(long tenantId, Supplier<T> body) {
        return withDatabaseContext(Long.toString(tenantId), "", body);
    }

    /** {@link #inTenantDatabase} for a body that returns nothing. */
    protected void inTenantDatabase(long tenantId, Runnable body) {
        inTenantDatabase(tenantId, () -> {
            body.run();
            return null;
        });
    }

    /**
     * Runs {@code body} in one transaction marked as host, which the policy's host branch answers
     * with every tenant's rows plus the host-global ones ({@code tenant_id IS NULL}).
     */
    protected <T> T asHostDatabase(Supplier<T> body) {
        return withDatabaseContext("", HibernateTenantFilterAspect.IS_HOST_ON, body);
    }

    /** {@link #asHostDatabase} for a body that returns nothing. */
    protected void asHostDatabase(Runnable body) {
        asHostDatabase(() -> {
            body.run();
            return null;
        });
    }

    /**
     * One transaction, both settings written {@code is_local = true} exactly as the aspect writes
     * them — so they revert when this transaction ends and the pooled connection carries nothing into
     * the next test. Both are always written, never one of them: a leftover {@code app.is_host} next
     * to a tenant id would make the policy true for every row.
     */
    private <T> T withDatabaseContext(String currentTenant, String isHost, Supplier<T> body) {
        if (databaseContextTransactions == null) {
            databaseContextTransactions = new TransactionTemplate(databaseContextTransactionManager);
        }
        return databaseContextTransactions.execute(status -> {
            databaseContextJdbcTemplate.query(
                    "select set_config('" + HibernateTenantFilterAspect.CURRENT_TENANT_SETTING
                            + "', ?, true), set_config('" + HibernateTenantFilterAspect.IS_HOST_SETTING
                            + "', ?, true)",
                    (ResultSetExtractor<Void>) resultSet -> null, currentTenant, isHost);
            return body.get();
        });
    }

    protected ResponseEntity<JsonNode> login(String tenant, String usernameOrEmail, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (tenant != null) {
            headers.set(TENANT_HEADER, tenant);
        }
        Map<String, String> body = Map.of(
                "usernameOrEmail", usernameOrEmail,
                "password", password);
        return restTemplate.exchange(
                "/api/auth/login", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    protected JsonNode loginOk(String tenant, String usernameOrEmail, String password) {
        ResponseEntity<JsonNode> response = login(tenant, usernameOrEmail, password);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        return body;
    }

    protected String accessToken(String tenant, String usernameOrEmail, String password) {
        return loginOk(tenant, usernameOrEmail, password).path("accessToken").asText();
    }

    protected HttpHeaders bearerHeaders(String accessToken, String tenant) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (tenant != null) {
            headers.set(TENANT_HEADER, tenant);
        }
        return headers;
    }

    /** Supports both direct Page serialization ({"content": [...]}) and plain array bodies. */
    protected JsonNode pageContent(JsonNode body) {
        assertThat(body).isNotNull();
        return body.isArray() ? body : body.path("content");
    }
}
