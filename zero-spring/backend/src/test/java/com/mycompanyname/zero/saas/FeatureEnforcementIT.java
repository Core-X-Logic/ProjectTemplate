package com.mycompanyname.zero.saas;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F5 Slice B proof for {@code @RequiresFeature} and for the cache behind it
 * (CONTRACT-phase5 Slice B; F5-ARCHITECTURE §6, F5-R2).
 *
 * <p>The gate is exercised against a <em>real</em> production route — the organization-unit
 * controller carries {@code @RequiresFeature(app.organizationUnits)} — rather than a fixture
 * controller, so what is proven here is what ships.
 *
 * <p>Every assertion goes through an HTTP call to that gated route, never through the admin
 * feature-listing API. That distinction is deliberate: the admin listing reads each level of the
 * chain directly (it has to, so the editor can show what masks what), so it would pass even with a
 * completely stale cache. Only the gated route exercises the cached resolution path, which is what
 * F5-R2 is about.
 *
 * <p>The {@code default} tenant is shared with the rest of the suite, so teardown always restores
 * both the tenant override and the seeded {@code Standard} package.
 */
class FeatureEnforcementIT extends AbstractSaasIT {

    private static final String ORGANIZATION_UNITS = "app.organizationUnits";
    private static final String GATED_ROUTE = "/api/organization-units";
    private static final String STANDARD_EDITION = "Standard";

    @AfterEach
    void restoreTheDefaultTenant() {
        long tenantId = tenantId(DEFAULT_TENANT);
        setTenantFeature(tenantId, ORGANIZATION_UNITS, null);
        assignEditionOk(tenantId, editionIdByName(STANDARD_EDITION), null, false);
    }

    @Test
    void aFeatureThatIsOnLetsTheRequestThrough() {
        assertThat(callGatedRoute().getStatusCode())
                .as("the definition default is true, so nothing is gated until a value says otherwise")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void aFeatureTurnedOffForTheTenantAnswers403() {
        setTenantFeature(tenantId(DEFAULT_TENANT), ORGANIZATION_UNITS, "false");

        ResponseEntity<JsonNode> response = callGatedRoute();

        assertThat(response.getStatusCode())
                .as("a capability the package does not include must be refused, not merely empty")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(response.getBody().path("detail").asText())
                .as("the message must name the missing feature so support can act on it")
                .contains(ORGANIZATION_UNITS);
    }

    @Test
    void aTenantOverrideTakesEffectOnTheNextRequestInBothDirections() {
        long tenantId = tenantId(DEFAULT_TENANT);

        setTenantFeature(tenantId, ORGANIZATION_UNITS, "false");
        assertThat(callGatedRoute().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        setTenantFeature(tenantId, ORGANIZATION_UNITS, "true");
        assertThat(callGatedRoute().getStatusCode())
                .as("the override write must evict the feature cache (F5-R2), otherwise the tenant "
                        + "stays locked out of what it was just granted")
                .isEqualTo(HttpStatus.OK);

        setTenantFeature(tenantId, ORGANIZATION_UNITS, "false");
        assertThat(callGatedRoute().getStatusCode())
                .as("and the reverse direction must be just as immediate")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aFeatureValueChangedOnTheEditionIsNotServedFromAStaleCache() {
        long tenantId = tenantId(DEFAULT_TENANT);
        long editionId = createFreeEdition("enforce-edition");

        assignEditionOk(tenantId, editionId, null, false);
        // Prime the cache with the inherited "true" before changing the edition underneath it.
        assertThat(callGatedRoute().getStatusCode()).isEqualTo(HttpStatus.OK);

        setEditionFeature(editionId, ORGANIZATION_UNITS, "false");
        assertThat(callGatedRoute().getStatusCode())
                .as("an edition feature write must evict the cache for every subscriber of that edition")
                .isEqualTo(HttpStatus.FORBIDDEN);

        setEditionFeature(editionId, ORGANIZATION_UNITS, "true");
        assertThat(callGatedRoute().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void reassigningThePackageRefreshesTheInheritedFeatureValue() {
        long tenantId = tenantId(DEFAULT_TENANT);
        long restrictedEditionId = createFreeEdition("enforce-restricted");
        long fullEditionId = createFreeEdition("enforce-full");
        setEditionFeature(restrictedEditionId, ORGANIZATION_UNITS, "false");
        setEditionFeature(fullEditionId, ORGANIZATION_UNITS, "true");

        assignEditionOk(tenantId, restrictedEditionId, null, false);
        assertThat(callGatedRoute().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        assignEditionOk(tenantId, fullEditionId, null, false);
        assertThat(callGatedRoute().getStatusCode())
                .as("package assignment is the third eviction path F5-R2 requires")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void aTenantOverrideStillBeatsAPermissiveEdition() {
        long tenantId = tenantId(DEFAULT_TENANT);
        long editionId = createFreeEdition("enforce-chain");
        setEditionFeature(editionId, ORGANIZATION_UNITS, "true");
        assignEditionOk(tenantId, editionId, null, false);

        setTenantFeature(tenantId, ORGANIZATION_UNITS, "false");

        assertThat(callGatedRoute().getStatusCode())
                .as("the resolution order (override > edition) must survive caching")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void theHostIsNeverGatedByATenantsPackage() {
        setTenantFeature(tenantId(DEFAULT_TENANT), ORGANIZATION_UNITS, "false");

        ResponseEntity<JsonNode> response = restTemplate.exchange(GATED_ROUTE,
                HttpMethod.GET, new HttpEntity<>(host()), JsonNode.class);

        assertThat(response.getStatusCode())
                .as("a host request carries no tenant, so it resolves the definition default")
                .isEqualTo(HttpStatus.OK);
    }

    // --- helpers ---

    private ResponseEntity<JsonNode> callGatedRoute() {
        return restTemplate.exchange(GATED_ROUTE, HttpMethod.GET,
                new HttpEntity<>(tenantAdmin()), JsonNode.class);
    }

    private long editionIdByName(String name) {
        ResponseEntity<JsonNode> list = restTemplate.exchange("/api/editions?size=100",
                HttpMethod.GET, new HttpEntity<>(host()), JsonNode.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        for (JsonNode node : pageContent(list.getBody())) {
            if (name.equals(node.path("name").asText())) {
                return node.path("id").asLong();
            }
        }
        throw new AssertionError("edition not found: " + name);
    }

    private void setTenantFeature(long tenantId, String featureName, String value) {
        ResponseEntity<JsonNode> response = restTemplate.exchange("/api/tenant-features/" + tenantId,
                HttpMethod.PUT, new HttpEntity<>(List.of(entry(featureName, value)), host()), JsonNode.class);
        assertThat(response.getStatusCode())
                .as("setting '%s' to '%s' must succeed, got %s", featureName, value, response.getBody())
                .isEqualTo(HttpStatus.OK);
    }

    private void setEditionFeature(long editionId, String featureName, String value) {
        assertThat(setEditionFeatures(editionId, List.of(entry(featureName, value))).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    /** A batch entry; {@code null} values need a LinkedHashMap since {@code Map.of} forbids them. */
    private Map<String, Object> entry(String name, String value) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", name);
        entry.put("value", value);
        return entry;
    }
}
