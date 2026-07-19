package com.mycompanyname.zero.saas;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Negative-authorization coverage for every SaaS route, following the {@code TenantEscalationIT}
 * pattern. SaaS entities carry no tenant {@code @Filter}, so the permission check is the only thing
 * preventing cross-tenant access — every SaaS endpoint needs a negative test here (see
 * ARCHITECTURE-RULES.md — "Tenant kendi limitini yükseltemez").
 *
 * <p>Every SaaS route is {@code Side.HOST}, so the seeder withholds those permissions from the
 * tenant Admin role and a tenant admin — the most privileged tenant-side principal there is — is
 * forbidden everywhere, including on its <em>own</em> tenant id. The single tenant-facing route is
 * {@code /me}, which reads the tenant from the JWT and therefore cannot be pointed at anyone else.
 *
 * <p>Every write below carries a <em>valid</em> body on purpose: {@code @RequestBody} binding and
 * {@code @Valid} run during argument resolution, i.e. before {@code @PreAuthorize}. A malformed body
 * would produce a 400 and the assertion would pass without ever proving anything about authorization.
 */
class SaasAuthorizationIT extends AbstractSaasIT {

    @Test
    void tenantAdminIsForbiddenOnTheEditionCatalogue() {
        HttpHeaders headers = tenantAdmin();

        assertForbidden(HttpMethod.GET, "/api/editions", null, headers);
        assertForbidden(HttpMethod.GET, "/api/editions/1", null, headers);
        assertForbidden(HttpMethod.GET, "/api/features/definitions", null, headers);
        assertForbidden(HttpMethod.POST, "/api/editions", validCreateEdition(), headers);
        assertForbidden(HttpMethod.PUT, "/api/editions/1", validUpdateEdition(), headers);
        assertForbidden(HttpMethod.DELETE, "/api/editions/1", null, headers);
        assertForbidden(HttpMethod.PUT, "/api/editions/1/features", List.of(), headers);
    }

    @Test
    void tenantAdminCannotReachTheSubscriptionAdminRoutesEvenForItsOwnTenant() {
        long ownTenantId = tenantId(DEFAULT_TENANT);
        HttpHeaders headers = tenantAdmin();

        assertForbidden(HttpMethod.GET, "/api/subscriptions", null, headers);
        assertForbidden(HttpMethod.GET, "/api/subscriptions/" + ownTenantId, null, headers);
        assertForbidden(HttpMethod.PUT, "/api/subscriptions/" + ownTenantId + "/edition",
                validAssignEdition(), headers);
        assertForbidden(HttpMethod.POST, "/api/subscriptions/" + ownTenantId + "/activate", null, headers);
        assertForbidden(HttpMethod.POST, "/api/subscriptions/" + ownTenantId + "/cancel", null, headers);
        assertForbidden(HttpMethod.GET, "/api/tenant-features/" + ownTenantId, null, headers);
        assertForbidden(HttpMethod.PUT, "/api/tenant-features/" + ownTenantId, List.of(), headers);
    }

    @Test
    void tenantAdminCannotReachAnotherTenantsSaasData() {
        long foreignTenantId = ensureTenant("saas-authz-foreign");
        HttpHeaders headers = tenantAdmin();

        assertForbidden(HttpMethod.GET, "/api/subscriptions/" + foreignTenantId, null, headers);
        assertForbidden(HttpMethod.GET, "/api/tenant-features/" + foreignTenantId, null, headers);
        assertForbidden(HttpMethod.PUT, "/api/subscriptions/" + foreignTenantId + "/edition",
                validAssignEdition(), headers);
        assertForbidden(HttpMethod.PUT, "/api/tenant-features/" + foreignTenantId, List.of(), headers);
    }

    @Test
    void meReturnsOnlyTheCallersOwnSubscription() {
        long ownTenantId = tenantId(DEFAULT_TENANT);

        ResponseEntity<JsonNode> response = restTemplate.exchange("/api/subscriptions/me",
                HttpMethod.GET, new HttpEntity<>(tenantAdmin()), JsonNode.class);

        assertThat(response.getStatusCode())
                .as("reading your own subscription needs authentication only, no permission")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("tenantId").asLong())
                .as("the tenant comes from the JWT, so /me can only ever be the caller's own tenant")
                .isEqualTo(ownTenantId);
        assertThat(response.getBody().path("status").asText()).isNotBlank();
    }

    @Test
    void meIsRejectedForAHostUserBecauseThereIsNoTenantContext() {
        ResponseEntity<JsonNode> response = restTemplate.exchange("/api/subscriptions/me",
                HttpMethod.GET, new HttpEntity<>(host()), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("VALIDATION");
    }

    @Test
    void hostAdminRetainsFullAccess() {
        ResponseEntity<JsonNode> editions = restTemplate.exchange("/api/editions",
                HttpMethod.GET, new HttpEntity<>(host()), JsonNode.class);
        assertThat(editions.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> subscriptions = restTemplate.exchange("/api/subscriptions",
                HttpMethod.GET, new HttpEntity<>(host()), JsonNode.class);
        assertThat(subscriptions.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> definitions = restTemplate.exchange("/api/features/definitions",
                HttpMethod.GET, new HttpEntity<>(host()), JsonNode.class);
        assertThat(definitions.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void theTenantPermissionTreeHidesTheSaasPermissions() {
        ResponseEntity<JsonNode> response = restTemplate.exchange("/api/permissions/tree",
                HttpMethod.GET, new HttpEntity<>(tenantAdmin()), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        String tree = String.valueOf(response.getBody());
        for (String permission : SaasPermissions.all()) {
            assertThat(tree)
                    .as("a tenant must not even be offered '%s' in the role editor", permission)
                    .doesNotContain(permission);
        }
    }

    @Test
    void theHostPermissionTreeOffersTheSaasPermissions() {
        ResponseEntity<JsonNode> response = restTemplate.exchange("/api/permissions/tree",
                HttpMethod.GET, new HttpEntity<>(host()), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        String tree = String.valueOf(response.getBody());
        for (String permission : SaasPermissions.all()) {
            assertThat(tree)
                    .as("the host role editor must be able to grant '%s'", permission)
                    .contains(permission);
        }
    }

    // --- helpers ---

    private void assertForbidden(HttpMethod method, String path, Object body, HttpHeaders headers) {
        HttpEntity<Object> entity = body == null
                ? new HttpEntity<>(headers)
                : new HttpEntity<>(body, headers);
        ResponseEntity<JsonNode> response = restTemplate.exchange(path, method, entity, JsonNode.class);
        assertThat(response.getStatusCode())
                .as("%s %s must be host-only, got %s: %s",
                        method, path, response.getStatusCode(), response.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private Map<String, Object> validCreateEdition() {
        return editionBody(uniqueEditionName("authz"), null, null, null, 0, 0);
    }

    private Map<String, Object> validUpdateEdition() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("displayName", "Escalation attempt");
        body.put("trialDayCount", 0);
        body.put("graceDayCount", 0);
        body.put("active", true);
        body.put("sortOrder", 0);
        return body;
    }

    private Map<String, Object> validAssignEdition() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("editionId", 1);
        body.put("trial", false);
        return body;
    }
}
