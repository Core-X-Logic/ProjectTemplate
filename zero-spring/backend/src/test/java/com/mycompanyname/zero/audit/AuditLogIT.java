package com.mycompanyname.zero.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.AbstractIntegrationIT;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 parity proof for HTTP audit logging (CONTRACT-phase2 §5.1).
 *
 * <p>Tenant-scoped ({@code default}). A {@code /api/users} call must be recorded with its method,
 * status, non-negative duration and with sensitive request parameters masked. The interceptor
 * serialises the request parameter map (query/form parameters), masking sensitive keys such as
 * {@code password} to {@code ***}. A caller lacking {@code auditlogs.read} must be refused (403).
 */
class AuditLogIT extends AbstractIntegrationIT {

    private static final String DEFAULT_TENANT = "default";
    private static final AtomicInteger SEQ = new AtomicInteger();

    private String unique(String prefix) {
        return prefix + "_" + System.nanoTime() + "_" + SEQ.incrementAndGet();
    }

    private HttpHeaders tenantAdmin() {
        return bearerHeaders(accessToken(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), DEFAULT_TENANT);
    }

    @Test
    void usersCallIsAuditedWithSensitiveParametersMasked() {
        HttpHeaders admin = tenantAdmin();
        String marker = "auditmarker" + System.nanoTime();
        String secret = "Sup3rSecretPw";

        // A unique, non-sensitive query parameter correlates the audit row; a "password" query
        // parameter must be masked. The list endpoint ignores unknown parameters.
        String uri = "/api/users?password=" + secret + "&auditMarker=" + marker;
        ResponseEntity<JsonNode> call = restTemplate.exchange(
                uri, HttpMethod.GET, new HttpEntity<>(admin), JsonNode.class);
        assertThat(call.getStatusCode())
                .as("the audited call must succeed, got %s", call.getStatusCode())
                .isEqualTo(HttpStatus.OK);

        JsonNode entry = pollForAuditEntry(admin, marker);
        assertThat(entry).as("the /api/users call must be recorded in audit_logs").isNotNull();

        assertThat(entry.path("httpMethod").asText()).isEqualTo("GET");
        assertThat(entry.path("url").asText()).contains("/api/users");
        assertThat(entry.path("httpStatusCode").asInt())
                .as("recorded status must be the 200 result")
                .isEqualTo(200);
        assertThat(entry.path("executionDurationMs").asLong())
                .as("execution duration must be non-negative")
                .isGreaterThanOrEqualTo(0);

        String parameters = entry.path("parameters").asText("");
        assertThat(parameters)
                .as("the raw password must never be persisted in the audit log")
                .doesNotContain(secret);
        assertThat(parameters)
                .as("sensitive parameters must be masked")
                .contains("***");
    }

    @Test
    void callerWithoutAuditPermissionIsForbidden() {
        HttpHeaders admin = tenantAdmin();

        // a role and user without auditlogs.read
        String roleName = unique("noaudit");
        Map<String, Object> roleBody = Map.of(
                "name", roleName,
                "displayName", "No Audit",
                "permissions", Set.of("users.read"),
                "isDefault", false);
        ResponseEntity<JsonNode> role = restTemplate.exchange(
                "/api/roles", HttpMethod.POST, new HttpEntity<>(roleBody, admin), JsonNode.class);
        assertThat(role.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String username = unique("noauditor");
        Map<String, Object> userBody = new HashMap<>();
        userBody.put("username", username);
        userBody.put("email", username + "@example.com");
        userBody.put("password", "Password123!");
        userBody.put("roleNames", Set.of(roleName));
        ResponseEntity<JsonNode> user = restTemplate.exchange(
                "/api/users", HttpMethod.POST, new HttpEntity<>(userBody, admin), JsonNode.class);
        assertThat(user.getStatusCode().is2xxSuccessful()).isTrue();

        HttpHeaders limited = bearerHeaders(accessToken(DEFAULT_TENANT, username, "Password123!"), DEFAULT_TENANT);
        ResponseEntity<JsonNode> logs = restTemplate.exchange(
                "/api/audit-logs", HttpMethod.GET, new HttpEntity<>(limited), JsonNode.class);
        assertThat(logs.getStatusCode())
                .as("auditlogs.read is required to read the audit log")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private JsonNode pollForAuditEntry(HttpHeaders headers, String parameterNeedle) {
        for (int attempt = 0; attempt < 15; attempt++) {
            // size<=max-page-size(100); the service applies a default execution_time DESC sort, so the
            // just-made call is on the first page (deterministic, no reliance on insertion order).
            ResponseEntity<JsonNode> logs = restTemplate.exchange(
                    "/api/audit-logs?page=0&size=100", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
            if (logs.getStatusCode().is2xxSuccessful() && logs.getBody() != null) {
                for (JsonNode entry : pageContent(logs.getBody())) {
                    if (entry.path("url").asText().contains("/api/users")
                            && entry.path("parameters").asText("").contains(parameterNeedle)) {
                        return entry;
                    }
                }
            }
            sleep(400);
        }
        return null;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
