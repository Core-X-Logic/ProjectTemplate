package com.mycompanyname.zero.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.AbstractIntegrationIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Evidence for PROD-R10 and PROD-R14 (migration V6).
 *
 * <p>V1 declared {@code unique nulls not distinct (tenant_id, username)} over every row. V2 later
 * added soft delete, and {@code User} carries {@code @SQLRestriction("deleted = false")} — so the
 * application stopped being able to see rows the constraint still counted. Deleting a user made the
 * username permanently unusable, with a 409 whose cause was invisible through the API. The same
 * shape took the application down entirely when the deleted user was the seeded {@code admin}: the
 * seeder saw no admin, tried to insert one, and died on a constraint pointing at a row it could not
 * query.
 */
class SoftDeletedUsernameReuseIT extends AbstractIntegrationIT {

    private static final String DEFAULT_TENANT = "default";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void aUsernameBecomesAvailableAgainAfterTheUserIsSoftDeleted() {
        HttpHeaders headers = bearerHeaders(
                accessToken(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), DEFAULT_TENANT);
        String username = "recycled-" + System.nanoTime();

        long firstId = createUser(headers, username, username + "@example.com");

        ResponseEntity<Void> deleted = restTemplate.exchange(
                "/api/users/" + firstId, HttpMethod.DELETE, new HttpEntity<>(headers), Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rowIsSoftDeleted(firstId))
                .as("the arrange step must leave a soft-deleted row behind, otherwise nothing is proven")
                .isTrue();

        // Before V6 this answered 409 (or 500) because the unique constraint still counted the
        // soft-deleted row that the application can no longer see.
        long secondId = createUser(headers, username, username + "-again@example.com");

        assertThat(secondId)
                .as("recreating a soft-deleted username must produce a genuinely new row")
                .isNotEqualTo(firstId);
    }

    @Test
    void twoLiveUsersStillCannotShareAUsername() {
        // The partial index narrows the rule to live rows; it must not weaken it for them.
        HttpHeaders headers = bearerHeaders(
                accessToken(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), DEFAULT_TENANT);
        String username = "duplicate-" + System.nanoTime();
        createUser(headers, username, username + "@example.com");

        ResponseEntity<JsonNode> duplicate = restTemplate.exchange(
                "/api/users", HttpMethod.POST,
                new HttpEntity<>(userPayload(username, username + "-other@example.com"), headers),
                JsonNode.class);

        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void theHardeningIndexesExist() {
        // Names the migration's actual output, so a future edit that drops either one is caught here
        // rather than in production under load (PROD-R14) or on a restart (PROD-R10).
        assertThat(indexExists("uq_users_tenant_username_live"))
                .as("PROD-R10: partial unique index over live rows")
                .isTrue();
        assertThat(indexExists("ix_users_tenant_lower_username"))
                .as("PROD-R14: functional index that turns the case-insensitive login lookup into a seek")
                .isTrue();
        assertThat(constraintExists("uq_users_tenant_username"))
                .as("the constraint that counted soft-deleted rows must be gone")
                .isFalse();
    }

    private long createUser(HttpHeaders headers, String username, String email) {
        ResponseEntity<JsonNode> created = restTemplate.exchange(
                "/api/users", HttpMethod.POST,
                new HttpEntity<>(userPayload(username, email), headers), JsonNode.class);
        assertThat(created.getStatusCode().is2xxSuccessful())
                .as("creating '%s' must succeed, got %s / %s",
                        username, created.getStatusCode(), created.getBody())
                .isTrue();
        assertThat(created.getBody()).isNotNull();
        return created.getBody().path("id").asLong();
    }

    private static Map<String, Object> userPayload(String username, String email) {
        return Map.of(
                "username", username,
                "email", email,
                "password", "Password123!",
                "roleNames", Set.of("Admin"));
    }

    /**
     * Raw SQL against a policed table (V12), from a thread that crosses no {@code @Service} boundary:
     * with no context this reads 0 rows and {@code queryForObject} raises. Host is the right one here
     * — the claim is about the row's stored state, not about any tenant's view of it.
     */
    private boolean rowIsSoftDeleted(long userId) {
        Boolean deleted = asHostDatabase(() -> jdbcTemplate.queryForObject(
                "select deleted from users where id = ?", Boolean.class, userId));
        return Boolean.TRUE.equals(deleted);
    }

    private boolean indexExists(String indexName) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from pg_indexes where tablename = 'users' and indexname = ?",
                Integer.class, indexName);
        return count != null && count > 0;
    }

    private boolean constraintExists(String constraintName) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from pg_constraint where conname = ?", Integer.class, constraintName);
        return count != null && count > 0;
    }
}
