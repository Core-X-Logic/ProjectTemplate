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

class TenantIsolationIT extends AbstractIntegrationIT {

    private static final String DEFAULT_TENANT = "default";

    @Test
    void tenantAdminCanLoginWithTenantHeader() {
        JsonNode pair = loginOk(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD);

        assertThat(pair.path("accessToken").asText()).isNotBlank();
        assertThat(pair.path("refreshToken").asText()).isNotBlank();
    }

    @Test
    void tenantTokenSeesOnlyDefaultTenantUsersHostAdminInvisible() {
        String token = accessToken(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD);
        HttpHeaders headers = bearerHeaders(token, DEFAULT_TENANT);

        ResponseEntity<JsonNode> me = restTemplate.exchange(
                "/api/auth/me", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody()).isNotNull();
        long defaultTenantId = me.getBody().path("tenantId").asLong();
        assertThat(defaultTenantId).isPositive();

        ResponseEntity<JsonNode> users = restTemplate.exchange(
                "/api/users?page=0&size=100", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertThat(users.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode content = pageContent(users.getBody());
        assertThat(content.isArray()).isTrue();
        assertThat(content.size()).isGreaterThanOrEqualTo(1);

        for (JsonNode user : content) {
            JsonNode tenantId = user.path("tenantId");
            assertThat(tenantId.isMissingNode() || tenantId.isNull())
                    .as("host users (tenantId=null) must not be visible to a tenant token")
                    .isFalse();
            assertThat(tenantId.asLong())
                    .as("every visible user must belong to the default tenant")
                    .isEqualTo(defaultTenantId);
        }
    }

    @Test
    void hostCreatesTenantAndFreshTenantHasNoUsersToLoginWith() {
        String hostToken = accessToken(null, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD);
        HttpHeaders headers = bearerHeaders(hostToken, null);

        Map<String, String> body = Map.of(
                "name", "acme",
                "displayName", "Acme Inc");
        ResponseEntity<JsonNode> created = restTemplate.exchange(
                "/api/tenants", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);

        // shared context across IT classes: TenantEscalationIT may already have created "acme"
        assertThat(created.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.CONFLICT);
        if (created.getStatusCode() == HttpStatus.CREATED) {
            assertThat(created.getBody()).isNotNull();
            assertThat(created.getBody().path("name").asText()).isEqualTo("acme");
        }

        ResponseEntity<JsonNode> acmeLogin = login("acme", SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD);
        assertThat(acmeLogin.getStatusCode())
                .as("new tenant has no seeded users, login must fail")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void tenantTokenCannotListTenants() {
        String token = accessToken(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD);
        HttpHeaders headers = bearerHeaders(token, DEFAULT_TENANT);

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/tenants", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);

        assertThat(response.getStatusCode())
                .as("tenant admin lacks tenants.manage")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
