package com.mycompanyname.zero.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.AbstractIntegrationIT;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /api/users?tenantId=} — the host-side picker behind "view this tenant's users /
 * impersonate one" on the tenants screen.
 *
 * <p>Positive: a host caller with {@code users.read} lists ONE named tenant's users and every row
 * belongs to that tenant. Negative (the half that makes the slice "done"): a TENANT caller passing
 * a {@code tenantId} — any value, including their own — is refused with 403, because the JWT
 * {@code tenant} claim is authoritative and the parameter would otherwise be a cross-tenant probe.
 * The refusal must be a 403 and not an empty page: an empty page reads as "that tenant has no
 * users" and would hide the security decision.
 */
class HostTenantUserListingIT extends AbstractIntegrationIT {

    private static final String DEFAULT_TENANT = "default";

    @Test
    void hostListsANamedTenantsUsers() {
        HttpHeaders hostHeaders = bearerHeaders(
                accessToken(null, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), null);
        long tenantId = defaultTenantId();

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/users?tenantId=" + tenantId + "&page=0&size=50", HttpMethod.GET,
                new HttpEntity<>(hostHeaders), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode content = pageContent(response.getBody());
        assertThat(content.size())
                .as("the default tenant has at least its seeded admin")
                .isPositive();
        for (JsonNode row : content) {
            assertThat(row.path("tenantId").asLong())
                    .as("every returned row must belong to the requested tenant")
                    .isEqualTo(tenantId);
        }
        boolean containsSeededAdmin = false;
        for (JsonNode row : content) {
            if (SEED_ADMIN_USERNAME.equalsIgnoreCase(row.path("username").asText())) {
                containsSeededAdmin = true;
            }
        }
        assertThat(containsSeededAdmin)
                .as("the seeded tenant admin must be present in the listing")
                .isTrue();
    }

    @Test
    void hostWithoutTenantIdStillListsOnlyHostUsers() {
        // The parameterless listing must not change behaviour: host scope stays host rows only.
        HttpHeaders hostHeaders = bearerHeaders(
                accessToken(null, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), null);

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/users?page=0&size=50", HttpMethod.GET,
                new HttpEntity<>(hostHeaders), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        for (JsonNode row : pageContent(response.getBody())) {
            JsonNode rowTenant = row.path("tenantId");
            assertThat(rowTenant.isMissingNode() || rowTenant.isNull())
                    .as("without tenantId a host caller sees host users only, got tenantId=%s",
                            rowTenant)
                    .isTrue();
        }
    }

    @Test
    void tenantCallerPassingTenantIdIsRefused() {
        HttpHeaders tenantHeaders = bearerHeaders(
                accessToken(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD),
                DEFAULT_TENANT);
        long ownTenantId = defaultTenantId();

        // Their OWN tenant id: still 403 — the parameter is host-only, not "redundant but allowed".
        // Allowing the redundant form would make the 403 depend on whether the id happens to match,
        // and a guessed foreign id would then be distinguishable from one's own by the status code.
        ResponseEntity<JsonNode> own = restTemplate.exchange(
                "/api/users?tenantId=" + ownTenantId, HttpMethod.GET,
                new HttpEntity<>(tenantHeaders), JsonNode.class);
        assertThat(own.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // A foreign (non-existent is fine — the guard fires before any lookup) tenant id: 403.
        ResponseEntity<JsonNode> foreign = restTemplate.exchange(
                "/api/users?tenantId=" + (ownTenantId + 999_999), HttpMethod.GET,
                new HttpEntity<>(tenantHeaders), JsonNode.class);
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private long defaultTenantId() {
        HttpHeaders tenantHeaders = bearerHeaders(
                accessToken(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD),
                DEFAULT_TENANT);
        ResponseEntity<JsonNode> me = restTemplate.exchange(
                "/api/auth/me", HttpMethod.GET, new HttpEntity<>(tenantHeaders), JsonNode.class);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        long tenantId = me.getBody().path("tenantId").asLong();
        assertThat(tenantId).isPositive();
        return tenantId;
    }
}
