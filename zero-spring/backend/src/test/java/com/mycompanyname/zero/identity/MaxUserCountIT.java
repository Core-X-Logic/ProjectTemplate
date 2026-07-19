package com.mycompanyname.zero.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.AbstractIntegrationIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers enforcement of {@code app.maxUserCount}.
 *
 * <p>This is the numeric half of feature gating and the reason {@code identity} now depends on
 * {@code saas :: api}: a limit cannot be expressed declaratively with {@code @RequiresFeature}
 * because the decision needs the tenant's current usage, so {@code UserService} asks
 * {@code FeatureChecker.intValue} directly.
 *
 * <p>The two behaviours that matter are asserted separately. A limit that has been reached refuses
 * the next user; a limit of {@code 0} means <em>unlimited</em>, which is the source system's
 * semantics and the one an "obvious" implementation gets backwards.
 *
 * <p>The limit is always derived from the tenant's live user count rather than hard-coded, because
 * sibling ITs create users in the same {@code default} tenant.
 */
class MaxUserCountIT extends AbstractIntegrationIT {

    private static final String DEFAULT_TENANT = "default";
    private static final String MAX_USER_COUNT = "app.maxUserCount";
    private static final AtomicInteger SEQ = new AtomicInteger();

    @AfterEach
    void clearTheLimit() {
        setMaxUserCount(null);
    }

    @Test
    void aTenantThatReachedItsLimitCannotCreateAnotherUser() {
        long currentUsers = userCount();
        setMaxUserCount(String.valueOf(currentUsers));

        ResponseEntity<JsonNode> response = createUser(uniqueUsername("overlimit"));

        assertThat(response.getStatusCode())
                .as("at the limit the next user must be refused, got %s: %s",
                        response.getStatusCode(), response.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("VALIDATION");
        assertThat(userCount())
                .as("a refused creation must not have written anything")
                .isEqualTo(currentUsers);
    }

    @Test
    void aTenantBelowItsLimitCanStillCreateUsers() {
        long currentUsers = userCount();
        setMaxUserCount(String.valueOf(currentUsers + 1));

        ResponseEntity<JsonNode> allowed = createUser(uniqueUsername("underlimit"));
        assertThat(allowed.getStatusCode())
                .as("one seat left must still be usable, got %s: %s",
                        allowed.getStatusCode(), allowed.getBody())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> refused = createUser(uniqueUsername("overlimit"));
        assertThat(refused.getStatusCode())
                .as("and the seat after that must not be")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void zeroMeansUnlimitedRatherThanNoUsersAtAll() {
        setMaxUserCount("0");

        ResponseEntity<JsonNode> response = createUser(uniqueUsername("unlimited"));

        assertThat(response.getStatusCode())
                .as("0 is the source system's 'unlimited' sentinel; reading it as a hard limit of "
                        + "zero would lock every tenant out of user management")
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void theDefaultPackageDoesNotLimitUsers() {
        // No override at all: the definition default is 0, i.e. unlimited.
        ResponseEntity<JsonNode> response = createUser(uniqueUsername("nolimit"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void aRaisedLimitTakesEffectImmediately() {
        long currentUsers = userCount();
        setMaxUserCount(String.valueOf(currentUsers));
        assertThat(createUser(uniqueUsername("blocked")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        setMaxUserCount(String.valueOf(currentUsers + 5));

        assertThat(createUser(uniqueUsername("unblocked")).getStatusCode())
                .as("raising the limit must not be masked by a stale feature cache")
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void theHostScopeIsNotGovernedByATenantFeature() {
        setMaxUserCount("1");

        ResponseEntity<JsonNode> response = restTemplate.exchange("/api/users", HttpMethod.POST,
                new HttpEntity<>(userBody(uniqueUsername("hostuser")), host()), JsonNode.class);

        assertThat(response.getStatusCode())
                .as("host users belong to no tenant, so no tenant's package may cap them")
                .isEqualTo(HttpStatus.CREATED);
    }

    // --- helpers ---

    private HttpHeaders host() {
        return bearerHeaders(accessToken(null, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), null);
    }

    private HttpHeaders tenantAdmin() {
        return bearerHeaders(
                accessToken(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), DEFAULT_TENANT);
    }

    private String uniqueUsername(String prefix) {
        return prefix + "_" + System.nanoTime() + "_" + SEQ.incrementAndGet();
    }

    private ResponseEntity<JsonNode> createUser(String username) {
        return restTemplate.exchange("/api/users", HttpMethod.POST,
                new HttpEntity<>(userBody(username), tenantAdmin()), JsonNode.class);
    }

    private Map<String, Object> userBody(String username) {
        return Map.of(
                "username", username,
                "email", username + "@example.com",
                "password", "Password123!",
                "roleNames", Set.of("Admin"));
    }

    private long userCount() {
        ResponseEntity<JsonNode> list = restTemplate.exchange("/api/users?page=0&size=1",
                HttpMethod.GET, new HttpEntity<>(tenantAdmin()), JsonNode.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).isNotNull();
        return list.getBody().path("totalElements").asLong();
    }

    /** Host-side write of the tenant override; a {@code null} value clears it. */
    private void setMaxUserCount(String value) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", MAX_USER_COUNT);
        entry.put("value", value);
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/tenant-features/" + defaultTenantId(), HttpMethod.PUT,
                new HttpEntity<>(List.of(entry), host()), JsonNode.class);
        assertThat(response.getStatusCode())
                .as("setting %s to %s must succeed, got %s", MAX_USER_COUNT, value, response.getBody())
                .isEqualTo(HttpStatus.OK);
    }

    private long defaultTenantId() {
        ResponseEntity<JsonNode> list = restTemplate.exchange("/api/tenants",
                HttpMethod.GET, new HttpEntity<>(host()), JsonNode.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        for (JsonNode node : pageContent(list.getBody())) {
            if (DEFAULT_TENANT.equals(node.path("name").asText())) {
                return node.path("id").asLong();
            }
        }
        throw new AssertionError("tenant not found: " + DEFAULT_TENANT);
    }
}
