package com.mycompanyname.zero.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.AbstractIntegrationIT;
import com.mycompanyname.zero.identity.domain.User;
import com.mycompanyname.zero.identity.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage of the {@code shouldChangePassword} flag surfaced on {@code /api/auth/me}.
 * A user flagged to change their password must see {@code shouldChangePassword=true}
 * in the {@code me} payload so the frontend can force a password change.
 */
class MeShouldChangePasswordIT extends AbstractIntegrationIT {

    private static final String DEFAULT_TENANT = "default";
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private UserRepository userRepository;

    private String unique(String prefix) {
        return prefix + "_" + System.nanoTime() + "_" + SEQ.incrementAndGet();
    }

    private HttpHeaders tenantAdmin() {
        return bearerHeaders(accessToken(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), DEFAULT_TENANT);
    }

    @Test
    void meReportsShouldChangePasswordWhenFlagged() {
        HttpHeaders admin = tenantAdmin();
        String username = unique("mustchange");
        Map<String, Object> body = Map.of(
                "username", username,
                "email", username + "@example.com",
                "password", "Password123!",
                "roleNames", Set.of("Admin"));
        ResponseEntity<JsonNode> created = restTemplate.exchange(
                "/api/users", HttpMethod.POST, new HttpEntity<>(body, admin), JsonNode.class);
        assertThat(created.getStatusCode().is2xxSuccessful())
                .as("create user must succeed, got %s", created.getStatusCode())
                .isTrue();
        long userId = created.getBody().path("id").asLong();

        // Fresh user: the flag is false by default.
        HttpHeaders userHeaders = bearerHeaders(accessToken(DEFAULT_TENANT, username, "Password123!"), DEFAULT_TENANT);
        assertThat(me(userHeaders).path("shouldChangePassword").asBoolean())
                .as("a freshly created user must not be forced to change the password")
                .isFalse();

        // Flag the user (there is no API for this yet; set it directly like an operator would).
        // asHostDatabase: `users` is policed since V12 and a test thread announces no context —
        // and "like an operator would" is exactly the host context this stands in for.
        asHostDatabase(() -> {
            User user = userRepository.findById(userId).orElseThrow();
            user.setShouldChangePassword(true);
            userRepository.save(user);
        });

        // A new session must now report the flag as true.
        HttpHeaders flagged = bearerHeaders(accessToken(DEFAULT_TENANT, username, "Password123!"), DEFAULT_TENANT);
        assertThat(me(flagged).path("shouldChangePassword").asBoolean())
                .as("me() must report shouldChangePassword=true once the user is flagged")
                .isTrue();
    }

    private JsonNode me(HttpHeaders headers) {
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/auth/me", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }
}
