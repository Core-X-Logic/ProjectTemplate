package com.mycompanyname.zero.tenancy;

import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.AbstractIntegrationIT;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that a tenant-scoped JWT can never widen its access via X-Tenant header manipulation:
 * the 'tenant' claim in the token is authoritative, any divergence between header and claim
 * (including omitting the header, which would imply host scope) is rejected with 403.
 */
class TenantEscalationIT extends AbstractIntegrationIT {

    private static final String DEFAULT_TENANT = "default";
    private static final String OTHER_TENANT = "acme";

    @Test
    void tenantTokenWithoutHeaderCannotReadHostUsers() {
        String token = accessToken(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD);
        // X-Tenant header deliberately omitted: header scope (host) != token tenant -> 403
        HttpHeaders headers = bearerHeaders(token, null);

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/users", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);

        assertThat(response.getStatusCode())
                .as("a tenant token without X-Tenant must not reach host data")
                .isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path("code").asText()).isEqualTo("FORBIDDEN");
    }

    @Test
    void tenantTokenWithForeignTenantHeaderIsRejected() {
        ensureTenantExists(OTHER_TENANT);
        String token = accessToken(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD);
        // default-tenant token + X-Tenant: acme -> mismatch -> 403
        HttpHeaders headers = bearerHeaders(token, OTHER_TENANT);

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/users", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);

        assertThat(response.getStatusCode())
                .as("a default-tenant token must not read another tenant's users")
                .isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path("code").asText()).isEqualTo("FORBIDDEN");
    }

    @Test
    void tenantTokenWithoutHeaderCannotCreateHostUser() {
        String token = accessToken(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD);
        HttpHeaders headers = bearerHeaders(token, null);

        Map<String, Object> body = Map.of(
                "username", "escalated-host-admin",
                "email", "escalated@host.local",
                "password", "Sup3rSecret!x");
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/users", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);

        assertThat(response.getStatusCode())
                .as("a tenant token must not create host-scoped users")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** Idempotent: another IT class may already have created the tenant in the shared context. */
    private void ensureTenantExists(String name) {
        String hostToken = accessToken(null, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD);
        HttpHeaders headers = bearerHeaders(hostToken, null);
        Map<String, String> body = Map.of(
                "name", name,
                "displayName", "Acme Inc");
        ResponseEntity<JsonNode> created = restTemplate.exchange(
                "/api/tenants", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(created.getStatusCode())
                .as("tenant must exist (freshly created or already present)")
                .isIn(HttpStatus.CREATED, HttpStatus.CONFLICT);
    }
}
