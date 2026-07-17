package com.mycompanyname.zero.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.AbstractIntegrationIT;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the in-app notification inbox end to end: creating a user triggers a "welcome"
 * notification (published by the identity module), the new user reads it via polling endpoints,
 * and a user cannot mark another user's notification as read.
 */
class NotificationInboxIT extends AbstractIntegrationIT {

    private static final String DEFAULT_TENANT = "default";
    private static final String PASSWORD = "Password123!";

    @Test
    void welcomeNotificationIsDeliveredReadAndOwnershipEnforced() {
        String adminToken = accessToken(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD);
        HttpHeaders adminHeaders = bearerHeaders(adminToken, DEFAULT_TENANT);

        // Tenant admin provisions two users; each gets a welcome notification.
        createUser(adminHeaders, "notif_alice", "notif_alice@example.com");
        createUser(adminHeaders, "notif_bob", "notif_bob@example.com");

        HttpHeaders aliceHeaders = bearerHeaders(accessToken(DEFAULT_TENANT, "notif_alice", PASSWORD), DEFAULT_TENANT);
        HttpHeaders bobHeaders = bearerHeaders(accessToken(DEFAULT_TENANT, "notif_bob", PASSWORD), DEFAULT_TENANT);

        // Alice sees exactly the welcome notification.
        JsonNode inbox = pageContent(getJson("/api/notifications", aliceHeaders).getBody());
        assertThat(inbox).isNotNull();
        assertThat(inbox.size()).isEqualTo(1);
        JsonNode welcome = inbox.get(0);
        long welcomeId = welcome.path("id").asLong();
        assertThat(welcomeId).isPositive();
        assertThat(welcome.path("notificationName").asText()).isEqualTo("welcome");
        assertThat(welcome.path("title").asText()).isEqualTo("Welcome to Zero Platform");
        assertThat(welcome.path("level").asText()).isEqualTo("SUCCESS");
        assertThat(welcome.path("isRead").asBoolean()).isFalse();

        // Unread count starts at 1.
        assertThat(unreadCount(aliceHeaders)).isEqualTo(1L);

        // Mark it read -> count drops to 0.
        ResponseEntity<Void> marked = restTemplate.exchange(
                "/api/notifications/" + welcomeId + "/read", HttpMethod.PUT,
                new HttpEntity<>(aliceHeaders), Void.class);
        assertThat(marked.getStatusCode().is2xxSuccessful())
                .as("mark read must succeed, got %s", marked.getStatusCode())
                .isTrue();
        assertThat(unreadCount(aliceHeaders)).isEqualTo(0L);

        // Bob's own welcome notification id.
        JsonNode bobInbox = pageContent(getJson("/api/notifications", bobHeaders).getBody());
        long bobNotificationId = bobInbox.get(0).path("id").asLong();
        assertThat(bobNotificationId).isNotEqualTo(welcomeId);

        // Alice cannot mark Bob's notification read -> 403 (or 404); never leaks it.
        ResponseEntity<JsonNode> foreign = restTemplate.exchange(
                "/api/notifications/" + bobNotificationId + "/read", HttpMethod.PUT,
                new HttpEntity<>(aliceHeaders), JsonNode.class);
        assertThat(foreign.getStatusCode())
                .as("marking another user's notification read must be denied, got %s", foreign.getStatusCode())
                .isIn(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);

        // Bob's notification remains unread.
        assertThat(unreadCount(bobHeaders)).isEqualTo(1L);
    }

    private void createUser(HttpHeaders adminHeaders, String username, String email) {
        Map<String, Object> body = Map.of(
                "username", username,
                "email", email,
                "password", PASSWORD,
                "roleNames", Set.of("Admin"));
        ResponseEntity<JsonNode> created = restTemplate.exchange(
                "/api/users", HttpMethod.POST, new HttpEntity<>(body, adminHeaders), JsonNode.class);
        assertThat(created.getStatusCode().is2xxSuccessful())
                .as("create user %s must succeed, got %s", username, created.getStatusCode())
                .isTrue();
    }

    private ResponseEntity<JsonNode> getJson(String path, HttpHeaders headers) {
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response;
    }

    private long unreadCount(HttpHeaders headers) {
        return getJson("/api/notifications/unread-count", headers).getBody().path("count").asLong();
    }
}
