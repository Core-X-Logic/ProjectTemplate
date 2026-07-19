package com.mycompanyname.zero.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompanyname.zero.AbstractIntegrationIT;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage of impersonation.
 *
 * <p>A host user impersonates a tenant user: a one-time token is exchanged for a token pair whose
 * JWT carries the {@code act} (actor) claim. Verifies: the actor claim identifies the real user,
 * back-to-impersonator restores the host identity, cascade impersonation is forbidden (403), and
 * the impersonation login is recorded in the audit log.
 */
class ImpersonationIT extends AbstractIntegrationIT {

    private static final String DEFAULT_TENANT = "default";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void hostImpersonatesTenantUserAndReturnsBack() {
        // actor: the host admin
        HttpHeaders hostHeaders = bearerHeaders(accessToken(null, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), null);
        JsonNode hostMe = me(hostHeaders);
        long actorId = hostMe.path("id").asLong();

        // target: the default tenant admin
        HttpHeaders tenantHeaders = bearerHeaders(
                accessToken(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), DEFAULT_TENANT);
        JsonNode tenantMe = me(tenantHeaders);
        long targetUserId = tenantMe.path("id").asLong();
        long targetTenantId = tenantMe.path("tenantId").asLong();
        assertThat(targetTenantId).isPositive();

        // 1) host requests an impersonation token (users.impersonate)
        ResponseEntity<JsonNode> impersonate = restTemplate.exchange(
                "/api/auth/impersonate", HttpMethod.POST,
                new HttpEntity<>(Map.of("targetUserId", targetUserId, "targetTenantId", targetTenantId), hostHeaders),
                JsonNode.class);
        assertThat(impersonate.getStatusCode())
                .as("impersonate must succeed, got %s", impersonate.getStatusCode())
                .isEqualTo(HttpStatus.OK);
        String impersonationToken = impersonate.getBody().path("impersonationToken").asText();
        assertThat(impersonationToken).isNotBlank();

        // 2) exchange the one-time token for an access/refresh pair. The endpoint requires an
        // authenticated caller (it is not permitAll): the actor is still logged in while performing
        // the hand-off, so the actor's (host) bearer is presented here.
        ResponseEntity<JsonNode> authenticated = restTemplate.exchange(
                "/api/auth/impersonate/authenticate", HttpMethod.POST,
                new HttpEntity<>(Map.of("impersonationToken", impersonationToken), hostHeaders), JsonNode.class);
        assertThat(authenticated.getStatusCode())
                .as("impersonate/authenticate must succeed, got %s", authenticated.getStatusCode())
                .isEqualTo(HttpStatus.OK);
        String impersonatedAccess = authenticated.getBody().path("accessToken").asText();
        assertThat(impersonatedAccess).isNotBlank();

        // the JWT carries the actor claim = the real (host) user
        JsonNode claims = decodeJwtClaims(impersonatedAccess);
        assertThat(claims.path("act").asLong())
                .as("act (actor) claim must be the impersonating host user id")
                .isEqualTo(actorId);
        assertThat(claims.path("tenant").asLong())
                .as("impersonated session runs in the target tenant")
                .isEqualTo(targetTenantId);

        // identity resolves to the target user
        HttpHeaders impersonatedHeaders = bearerHeaders(impersonatedAccess, DEFAULT_TENANT);
        JsonNode impersonatedMe = me(impersonatedHeaders);
        assertThat(impersonatedMe.path("id").asLong()).isEqualTo(targetUserId);
        assertThat(impersonatedMe.path("tenantId").asLong()).isEqualTo(targetTenantId);

        // 3) cascade impersonation is forbidden: the act claim is already present -> 403
        ResponseEntity<JsonNode> cascade = restTemplate.exchange(
                "/api/auth/impersonate", HttpMethod.POST,
                new HttpEntity<>(Map.of("targetUserId", targetUserId, "targetTenantId", targetTenantId),
                        impersonatedHeaders),
                JsonNode.class);
        assertThat(cascade.getStatusCode())
                .as("nested impersonation must be rejected with 403")
                .isEqualTo(HttpStatus.FORBIDDEN);

        // 4) back to impersonator: restores the host identity
        ResponseEntity<JsonNode> back = restTemplate.exchange(
                "/api/auth/back-to-impersonator", HttpMethod.POST,
                new HttpEntity<>(impersonatedHeaders), JsonNode.class);
        assertThat(back.getStatusCode())
                .as("back-to-impersonator must succeed, got %s", back.getStatusCode())
                .isEqualTo(HttpStatus.OK);
        String restoredAccess = back.getBody().path("accessToken").asText();
        assertThat(restoredAccess).isNotBlank();
        JsonNode restoredClaims = decodeJwtClaims(restoredAccess);
        assertThat(restoredClaims.path("act").isMissingNode() || restoredClaims.path("act").isNull())
                .as("restored token must not carry an actor claim")
                .isTrue();

        JsonNode restoredMe = me(bearerHeaders(restoredAccess, null));
        assertThat(restoredMe.path("id").asLong()).isEqualTo(actorId);
        JsonNode restoredTenant = restoredMe.path("tenantId");
        assertThat(restoredTenant.isMissingNode() || restoredTenant.isNull())
                .as("restored identity is the host user (tenantId null)")
                .isTrue();

        // 5) the impersonation login is recorded in the audit log
        assertThat(impersonationAudited(hostHeaders, tenantHeaders))
                .as("an audit_logs entry for the impersonation login must exist")
                .isTrue();
    }

    // --- helpers ---------------------------------------------------------

    private JsonNode me(HttpHeaders headers) {
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/auth/me", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private boolean impersonationAudited(HttpHeaders hostHeaders, HttpHeaders tenantHeaders) {
        for (int attempt = 0; attempt < 15; attempt++) {
            if (containsImpersonationEntry(hostHeaders) || containsImpersonationEntry(tenantHeaders)) {
                return true;
            }
            sleep(400);
        }
        return false;
    }

    private boolean containsImpersonationEntry(HttpHeaders headers) {
        ResponseEntity<JsonNode> logs = restTemplate.exchange(
                "/api/audit-logs?page=0&size=200", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        if (!logs.getStatusCode().is2xxSuccessful() || logs.getBody() == null) {
            return false;
        }
        for (JsonNode entry : pageContent(logs.getBody())) {
            String url = entry.path("url").asText("");
            String service = entry.path("serviceName").asText("");
            if (url.contains("impersonate") || service.equalsIgnoreCase("Impersonation")) {
                return true;
            }
        }
        return false;
    }

    private JsonNode decodeJwtClaims(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            String payload = parts[1];
            int pad = (4 - payload.length() % 4) % 4;
            payload = payload + "=".repeat(pad);
            byte[] decoded = Base64.getUrlDecoder().decode(payload);
            return MAPPER.readTree(new String(decoded, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to decode JWT claims", e);
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
