package com.mycompanyname.zero.saas;

import com.fasterxml.jackson.databind.JsonNode;
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
 * F5 Slice A proof for feature resolution (CONTRACT-phase5 A.4).
 *
 * <p>Walks the whole chain in one test — definition default, then the edition value, then the tenant
 * override, then back down as each level is cleared — because the ordering between the levels is the
 * actual contract, not any single value. Unknown feature names and type-incompatible values are
 * rejected with 400, mirroring {@code SettingDefinitions.require}.
 */
class FeatureResolutionIT extends AbstractSaasIT {

    private static final String TENANT = "saas-features";
    private static final String MAX_USER_COUNT = "app.maxUserCount";
    private static final String AUDIT_LOG = "app.auditLog";

    @Test
    void definitionsExposeTypeAndDefaultForTheAdminEditor() {
        ResponseEntity<JsonNode> response = restTemplate.exchange("/api/features/definitions",
                HttpMethod.GET, new HttpEntity<>(host()), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode maxUsers = definition(response.getBody(), MAX_USER_COUNT);
        assertThat(maxUsers.path("type").asText()).isEqualTo("NUMBER");
        assertThat(maxUsers.path("defaultValue").asText())
                .as("0 means unlimited; the source semantics are preserved")
                .isEqualTo("0");

        JsonNode auditLog = definition(response.getBody(), AUDIT_LOG);
        assertThat(auditLog.path("type").asText()).isEqualTo("BOOLEAN");
        assertThat(auditLog.path("defaultValue").asText()).isEqualTo("true");
    }

    @Test
    void valueResolvesTenantOverrideThenEditionThenDefault() {
        long tenantId = ensureTenant(TENANT);
        long editionId = createFreeEdition("feat");
        assignEditionOk(tenantId, editionId, null, false);

        // 1. nothing set anywhere -> the definition default
        JsonNode feature = tenantFeature(tenantId, MAX_USER_COUNT);
        assertThat(feature.path("value").asText()).isEqualTo("0");
        assertThat(feature.path("overrideValue").isNull()).isTrue();
        assertThat(feature.path("editionValue").isNull()).isTrue();

        // 2. the edition supplies a value -> it wins over the default
        assertThat(setEditionFeatures(editionId, List.of(featureValue(MAX_USER_COUNT, "25"))).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        feature = tenantFeature(tenantId, MAX_USER_COUNT);
        assertThat(feature.path("value").asText())
                .as("the tenant's edition must override the definition default")
                .isEqualTo("25");
        assertThat(feature.path("editionValue").asText()).isEqualTo("25");
        assertThat(feature.path("overrideValue").isNull()).isTrue();

        // 3. a host-set tenant override -> it wins over the edition
        assertThat(putTenantFeatures(tenantId, List.of(featureValue(MAX_USER_COUNT, "50"))).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        feature = tenantFeature(tenantId, MAX_USER_COUNT);
        assertThat(feature.path("value").asText())
                .as("the tenant override sits at the top of the chain")
                .isEqualTo("50");
        assertThat(feature.path("overrideValue").asText()).isEqualTo("50");
        assertThat(feature.path("editionValue").asText())
                .as("the inherited edition value stays visible so the admin sees what it masks")
                .isEqualTo("25");

        // 4. clearing the override falls back to the edition, not to the default
        assertThat(putTenantFeatures(tenantId, List.of(featureValue(MAX_USER_COUNT, null))).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        feature = tenantFeature(tenantId, MAX_USER_COUNT);
        assertThat(feature.path("value").asText()).isEqualTo("25");
        assertThat(feature.path("overrideValue").isNull()).isTrue();

        // 5. clearing the edition value falls back to the definition default
        assertThat(setEditionFeatures(editionId, List.of(featureValue(MAX_USER_COUNT, null))).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(tenantFeature(tenantId, MAX_USER_COUNT).path("value").asText()).isEqualTo("0");
    }

    @Test
    void anUnknownFeatureIsRejected() {
        long tenantId = ensureTenant(TENANT);

        ResponseEntity<JsonNode> response =
                putTenantFeatures(tenantId, List.of(featureValue("app.thisFeatureDoesNotExist", "1")));

        assertThat(response.getStatusCode())
                .as("the registry is authoritative; unknown features must never be silently stored")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("VALIDATION");
    }

    @Test
    void aValueIncompatibleWithTheFeatureTypeIsRejected() {
        long editionId = createFreeEdition("feat-type");

        ResponseEntity<JsonNode> boolResponse =
                setEditionFeatures(editionId, List.of(featureValue(AUDIT_LOG, "definitely-not-a-boolean")));
        assertThat(boolResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(boolResponse.getBody()).isNotNull();
        assertThat(boolResponse.getBody().path("code").asText()).isEqualTo("VALIDATION");

        ResponseEntity<JsonNode> numberResponse =
                setEditionFeatures(editionId, List.of(featureValue(MAX_USER_COUNT, "many")));
        assertThat(numberResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- helpers ---

    private ResponseEntity<JsonNode> putTenantFeatures(long tenantId, List<Map<String, Object>> updates) {
        return restTemplate.exchange("/api/tenant-features/" + tenantId, HttpMethod.PUT,
                new HttpEntity<>(updates, host()), JsonNode.class);
    }

    private JsonNode tenantFeature(long tenantId, String name) {
        ResponseEntity<JsonNode> response = restTemplate.exchange("/api/tenant-features/" + tenantId,
                HttpMethod.GET, new HttpEntity<>(host()), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return definition(response.getBody(), name);
    }

    /** A batch entry; {@code null} values use a LinkedHashMap since {@code Map.of} forbids them. */
    private Map<String, Object> featureValue(String name, String value) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", name);
        entry.put("value", value);
        return entry;
    }

    private JsonNode definition(JsonNode body, String name) {
        assertThat(body).isNotNull();
        for (JsonNode node : pageContent(body)) {
            if (name.equals(node.path("name").asText())) {
                return node;
            }
        }
        throw new AssertionError("feature not present in the response: " + name);
    }
}
