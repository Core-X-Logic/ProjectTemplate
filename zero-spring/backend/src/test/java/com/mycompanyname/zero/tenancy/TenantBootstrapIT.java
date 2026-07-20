package com.mycompanyname.zero.tenancy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.AbstractIntegrationIT;
import com.mycompanyname.zero.identity.bootstrap.TenantAdminBootstrapper;
import com.mycompanyname.zero.identity.domain.PermissionDefinitions;
import com.mycompanyname.zero.identity.domain.Role;
import com.mycompanyname.zero.identity.domain.User;
import com.mycompanyname.zero.identity.repo.RoleRepository;
import com.mycompanyname.zero.identity.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #1 — a freshly created tenant must come with a usable admin.
 *
 * <p>Before the fix, {@code POST /api/tenants} created the tenant row and nothing else: no role, no
 * user, nobody who could ever log in. The core test here was run against that code first and failed
 * exactly there (login answered 401) — the recorded negative evidence the contract requires.
 */
class TenantBootstrapIT extends AbstractIntegrationIT {

    private static final String ADMIN_USERNAME = "admin";
    /** Satisfies the default password policy: length >= 6, digit, upper, lower. */
    private static final String PROVIDED_PASSWORD = "BootAdmin123!";

    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private TenantAdminBootstrapper bootstrapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Unique per JVM so IT classes sharing the context (and reruns) can never collide on names. */
    private static String unique(String prefix) {
        return prefix + Long.toString(System.nanoTime(), 36);
    }

    private ResponseEntity<JsonNode> postTenant(Map<String, ?> body) {
        HttpHeaders host = bearerHeaders(accessToken(null, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), null);
        return restTemplate.exchange(
                "/api/tenants", HttpMethod.POST, new HttpEntity<>(body, host), JsonNode.class);
    }

    private Map<String, Object> tenantBody(String name, String adminEmail, String adminPassword) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("displayName", "Tenant " + name);
        if (adminEmail != null) {
            body.put("adminEmail", adminEmail);
        }
        if (adminPassword != null) {
            body.put("adminPassword", adminPassword);
        }
        return body;
    }

    /**
     * THE core proof: create a tenant through the API, then log in as its admin with a real token
     * flow (X-Tenant header) and hit a permission-gated tenant endpoint.
     *
     * <p>Recorded red on the unfixed code: the create answered 201 but the login answered 401 —
     * the tenant existed and was forever unusable.
     */
    @Test
    void createdTenantAdminCanLoginAndCallAPermissionGatedTenantEndpoint() {
        String name = unique("boot-a-");
        ResponseEntity<JsonNode> created = postTenant(tenantBody(name, "admin@" + name + ".test", PROVIDED_PASSWORD));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String token = accessToken(name, ADMIN_USERNAME, PROVIDED_PASSWORD);

        ResponseEntity<JsonNode> users = restTemplate.exchange(
                "/api/users?page=0&size=10", HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(token, name)), JsonNode.class);
        assertThat(users.getStatusCode())
                .as("the bootstrapped admin must hold a permission-gated tenant endpoint")
                .isEqualTo(HttpStatus.OK);

        // The bootstrapped rows must carry the NEW tenant's id (tenancy trap: a filter or context
        // mistake writes them into host scope or another tenant, silently).
        Long tenantId = tenantRepository.findByNameIgnoreCase(name).orElseThrow().getId();
        User admin = userRepository.findByTenantIdAndUsernameIgnoreCase(tenantId, ADMIN_USERNAME).orElseThrow();
        assertThat(admin.getTenantId()).isEqualTo(tenantId);
        assertThat(admin.getEmail()).isEqualTo("admin@" + name + ".test");
        Role adminRole = roleRepository.findByTenantIdAndNameIgnoreCase(tenantId, "Admin").orElseThrow();
        assertThat(adminRole.getTenantId()).isEqualTo(tenantId);
        // Via SQL, not adminRole.getPermissions(): the element collection is lazy and the entity is
        // detached out here.
        List<String> permissions = jdbcTemplate.queryForList(
                "select permission from role_permissions where role_id = ?", String.class, adminRole.getId());
        assertThat(permissions).isNotEmpty();
        assertThat(permissions)
                .as("the tenant Admin role must exclude every HOST-only permission")
                .doesNotContainAnyElementsOf(PermissionDefinitions.hostOnlyPermissionNames());
    }

    /**
     * Two tenants get two distinct admins; neither's credential or token opens the other.
     * Isolation gaps answer 200 and only a negative test sees them.
     */
    @Test
    void secondTenantAdminIsDistinctAndCrossTenantAccessFails() {
        String nameA = unique("boot-b1-");
        String nameB = unique("boot-b2-");
        String passwordA = "TenantA-Secret1";
        String passwordB = "TenantB-Secret2";
        assertThat(postTenant(tenantBody(nameA, "admin@" + nameA + ".test", passwordA)).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(postTenant(tenantBody(nameB, "admin@" + nameB + ".test", passwordB)).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        // Tenant A's credential must not open tenant B.
        assertThat(login(nameB, ADMIN_USERNAME, passwordA).getStatusCode())
                .as("tenant A's admin password must not log into tenant B")
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // Tenant A's token must not be usable against tenant B (JWT tenant claim is authoritative).
        String tokenA = accessToken(nameA, ADMIN_USERNAME, passwordA);
        ResponseEntity<JsonNode> crossTenant = restTemplate.exchange(
                "/api/users", HttpMethod.GET, new HttpEntity<>(bearerHeaders(tokenA, nameB)), JsonNode.class);
        assertThat(crossTenant.getStatusCode())
                .as("a tenant A token presented with tenant B's header is a tenant mismatch")
                .isEqualTo(HttpStatus.FORBIDDEN);

        // Distinct rows, each in its own tenant; and tenant A's user listing must not leak B's admin.
        Long tenantAId = tenantRepository.findByNameIgnoreCase(nameA).orElseThrow().getId();
        Long tenantBId = tenantRepository.findByNameIgnoreCase(nameB).orElseThrow().getId();
        User adminA = userRepository.findByTenantIdAndUsernameIgnoreCase(tenantAId, ADMIN_USERNAME).orElseThrow();
        User adminB = userRepository.findByTenantIdAndUsernameIgnoreCase(tenantBId, ADMIN_USERNAME).orElseThrow();
        assertThat(adminA.getId()).isNotEqualTo(adminB.getId());

        ResponseEntity<JsonNode> usersOfA = restTemplate.exchange(
                "/api/users?page=0&size=100", HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(tokenA, nameA)), JsonNode.class);
        assertThat(usersOfA.getStatusCode()).isEqualTo(HttpStatus.OK);
        for (JsonNode user : pageContent(usersOfA.getBody())) {
            assertThat(user.path("tenantId").asLong())
                    .as("tenant A's listing must contain tenant A rows only")
                    .isEqualTo(tenantAId);
        }
    }

    /**
     * No {@code adminPassword} in the request: a strong random password is generated, returned once
     * in the create response, hashes correctly — and never appears in any log line.
     */
    @Test
    void generatedPasswordIsReturnedOnceValidatesAndIsNeverLogged() {
        String name = unique("boot-c-");
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        root.addAppender(captured);
        String generated;
        try {
            ResponseEntity<JsonNode> created = postTenant(tenantBody(name, "admin@" + name + ".test", null));
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            generated = created.getBody().path("generatedAdminPassword").asText();
            assertThat(generated)
                    .as("without adminPassword in the request, the response must carry the "
                            + "generated one exactly once")
                    .isNotBlank();

            assertThat(loginOk(name, ADMIN_USERNAME, generated).path("accessToken").asText()).isNotBlank();
        } finally {
            root.detachAppender(captured);
            captured.stop();
        }

        // Not stored in plaintext: only the hash is persisted, and it validates the returned value.
        Long tenantId = tenantRepository.findByNameIgnoreCase(name).orElseThrow().getId();
        User admin = userRepository.findByTenantIdAndUsernameIgnoreCase(tenantId, ADMIN_USERNAME).orElseThrow();
        assertThat(admin.getPasswordHash()).isNotEqualTo(generated);
        assertThat(passwordEncoder.matches(generated, admin.getPasswordHash())).isTrue();
        assertThat(admin.isShouldChangePassword())
                .as("an operator-known initial credential must be rotated on first login")
                .isTrue();

        for (ILoggingEvent event : captured.list) {
            assertThat(event.getFormattedMessage())
                    .as("the generated password must not appear in any log line")
                    .doesNotContain(generated);
        }
    }

    /** {@code adminEmail} is required and must be an email; the old contract accepted its absence. */
    @Test
    void adminEmailIsRequiredAndValidated() {
        String name = unique("boot-d-");
        assertThat(postTenant(tenantBody(name, null, null)).getStatusCode())
                .as("adminEmail missing must be a 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postTenant(tenantBody(name, "not-an-email", null)).getStatusCode())
                .as("adminEmail must be a syntactically valid email")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tenantRepository.findByNameIgnoreCase(name))
                .as("a refused request must not leave a half-created tenant behind")
                .isEmpty();
    }

    /**
     * A second bootstrap of an already-bootstrapped tenant — a replayed event, or an operator's
     * future repair path — must create nothing and, critically, must never reset the existing
     * admin's password or email. Lookup-first, backed by the unique constraints.
     */
    @Test
    void doubleBootstrapCreatesNoDuplicateAdminOrRoleAndNeverResetsThePassword() {
        String name = unique("boot-f-");
        String email = "admin@" + name + ".test";
        assertThat(postTenant(tenantBody(name, email, PROVIDED_PASSWORD)).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        Long tenantId = tenantRepository.findByNameIgnoreCase(name).orElseThrow().getId();
        User before = userRepository.findByTenantIdAndUsernameIgnoreCase(tenantId, ADMIN_USERNAME).orElseThrow();

        bootstrapper.bootstrapAdmin(tenantId, "other@" + name + ".test", "Different1Pw", false);

        assertThat(userRepository.countByTenantId(tenantId))
                .as("the second bootstrap must not create a second user")
                .isEqualTo(1);
        assertThat(roleRepository.findAllByTenantId(tenantId))
                .as("the second bootstrap must not create a second Admin role")
                .filteredOn(role -> "Admin".equalsIgnoreCase(role.getName()))
                .hasSize(1);
        User after = userRepository.findByTenantIdAndUsernameIgnoreCase(tenantId, ADMIN_USERNAME).orElseThrow();
        assertThat(after.getId()).isEqualTo(before.getId());
        assertThat(after.getEmail())
                .as("an existing admin's email must not be rewritten by a re-bootstrap")
                .isEqualTo(email);
        assertThat(passwordEncoder.matches(PROVIDED_PASSWORD, after.getPasswordHash()))
                .as("an existing admin's password must survive a re-bootstrap untouched")
                .isTrue();
        assertThat(loginOk(name, ADMIN_USERNAME, PROVIDED_PASSWORD).path("accessToken").asText()).isNotBlank();
    }

    /**
     * A provided password that fails the password policy refuses the WHOLE creation: tenant and
     * admin are one transaction, so no "tenant exists but admin creation failed" state survives.
     */
    @Test
    void weakProvidedPasswordIsRejectedAndNoTenantIsLeftBehind() {
        String name = unique("boot-e-");
        ResponseEntity<JsonNode> created = postTenant(tenantBody(name, "admin@" + name + ".test", "weak"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tenantRepository.findByNameIgnoreCase(name))
                .as("the rejected creation must roll back atomically")
                .isEmpty();
    }
}
