package com.mycompanyname.zero.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.AbstractIntegrationIT;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage of hierarchical settings.
 *
 * <p>Verifies: a tenant-scoped override is persisted and read back (with cache eviction on write),
 * the client endpoint exposes only {@code visibleToClient} settings, and a tenant user cannot reach
 * the host settings endpoint (403).
 *
 * <p>The batch update is sent as a list of {@code {name, value}} pairs.
 */
class SettingsIT extends AbstractIntegrationIT {

    private static final String DEFAULT_TENANT = "default";
    private static final String REQUIRED_LENGTH = "App.Password.RequiredLength";
    private static final String EMAIL_FROM = "App.Email.DefaultFromAddress";

    private HttpHeaders tenantAdmin() {
        return bearerHeaders(accessToken(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), DEFAULT_TENANT);
    }

    private ResponseEntity<JsonNode> putTenantSetting(HttpHeaders headers, String name, String value) {
        // Batch update: a JSON array of SettingDto {name, value} pairs.
        List<Map<String, String>> batch = List.of(Map.of("name", name, "value", value));
        return restTemplate.exchange(
                "/api/settings/tenant", HttpMethod.PUT, new HttpEntity<>(batch, headers), JsonNode.class);
    }

    private JsonNode getTenantSettings(HttpHeaders headers) {
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/settings/tenant", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    @Test
    void tenantOverrideIsPersistedAndCacheStaysConsistent() {
        HttpHeaders admin = tenantAdmin();

        ResponseEntity<JsonNode> firstSet = putTenantSetting(admin, REQUIRED_LENGTH, "8");
        assertThat(firstSet.getStatusCode().is2xxSuccessful())
                .as("tenant setting update must succeed, got %s", firstSet.getStatusCode())
                .isTrue();
        assertThat(settingValue(getTenantSettings(admin), REQUIRED_LENGTH))
                .as("the tenant override must be read back")
                .isEqualTo("8");

        // second write must evict the cache: the fresh value, not the stale one, is returned
        ResponseEntity<JsonNode> secondSet = putTenantSetting(admin, REQUIRED_LENGTH, "10");
        assertThat(secondSet.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(settingValue(getTenantSettings(admin), REQUIRED_LENGTH))
                .as("cache must be evicted on write; the new value must be visible")
                .isEqualTo("10");
    }

    @Test
    void clientEndpointExposesOnlyVisibleSettings() {
        HttpHeaders admin = tenantAdmin();
        putTenantSetting(admin, REQUIRED_LENGTH, "9");

        ResponseEntity<JsonNode> client = restTemplate.exchange(
                "/api/settings/client", HttpMethod.GET, new HttpEntity<>(admin), JsonNode.class);
        assertThat(client.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = client.getBody();

        assertThat(settingValue(body, REQUIRED_LENGTH))
                .as("a visibleToClient setting must be resolved for the client")
                .isEqualTo("9");
        assertThat(settingValue(body, EMAIL_FROM))
                .as("a non-visible setting must never reach the client bootstrap")
                .isNull();
    }

    @Test
    void tenantSettingsCarryDefinitionDefaultAsHint() {
        HttpHeaders admin = tenantAdmin();
        // Even with a tenant override in place, defaultValue must reflect the definition fallback.
        putTenantSetting(admin, REQUIRED_LENGTH, "12");

        JsonNode body = getTenantSettings(admin);
        assertThat(settingDefaultValue(body, REQUIRED_LENGTH))
                .as("defaultValue must equal the definition default (App.Password.RequiredLength = 6)")
                .isEqualTo("6");
    }

    @Test
    void tenantUserCannotReadHostSettings() {
        HttpHeaders admin = tenantAdmin();
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/settings/host", HttpMethod.GET, new HttpEntity<>(admin), JsonNode.class);
        assertThat(response.getStatusCode())
                .as("settings.host.manage is HOST-ONLY; a tenant admin must be forbidden")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * Reads a setting value tolerant of shape: an array/page of {@code {name, value}} objects,
     * or a flat {@code {name: value}} map (optionally wrapped in a "settings" node).
     */
    private String settingValue(JsonNode body, String name) {
        if (body == null) {
            return null;
        }
        JsonNode array = null;
        if (body.isArray()) {
            array = body;
        } else if (body.path("content").isArray()) {
            array = body.path("content");
        } else if (body.path("settings").isArray()) {
            array = body.path("settings");
        }
        if (array != null) {
            for (JsonNode node : array) {
                if (name.equals(node.path("name").asText())) {
                    JsonNode value = node.path("value");
                    return value.isNull() || value.isMissingNode() ? null : value.asText();
                }
            }
            return null;
        }
        // object-map form
        JsonNode container = body.path("settings").isObject() ? body.path("settings") : body;
        JsonNode value = container.path(name);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        return value.isValueNode() ? value.asText() : value.path("value").asText();
    }

    /**
     * Reads the {@code defaultValue} hint from an array/page of {@code {name, value, defaultValue}} objects.
     */
    private String settingDefaultValue(JsonNode body, String name) {
        if (body == null) {
            return null;
        }
        JsonNode array = null;
        if (body.isArray()) {
            array = body;
        } else if (body.path("content").isArray()) {
            array = body.path("content");
        } else if (body.path("settings").isArray()) {
            array = body.path("settings");
        }
        if (array == null) {
            return null;
        }
        for (JsonNode node : array) {
            if (name.equals(node.path("name").asText())) {
                JsonNode value = node.path("defaultValue");
                return value.isNull() || value.isMissingNode() ? null : value.asText();
            }
        }
        return null;
    }
}
