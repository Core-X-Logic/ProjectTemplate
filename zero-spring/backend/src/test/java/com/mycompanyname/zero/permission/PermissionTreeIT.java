package com.mycompanyname.zero.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.AbstractIntegrationIT;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 parity proof for the permission tree (CONTRACT-phase2 §3).
 *
 * <p>The tree is filtered by side: a host user sees HOST-ONLY permissions
 * ({@code settings.host.manage}, {@code languages.manage}, {@code tenants.manage}); a tenant user
 * does not. Both see the shared tenant permissions.
 */
class PermissionTreeIT extends AbstractIntegrationIT {

    private static final String DEFAULT_TENANT = "default";

    private Set<String> permissionNames(HttpHeaders headers) {
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/permissions/tree", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertThat(response.getStatusCode())
                .as("permission tree must be readable by any authenticated user")
                .isEqualTo(HttpStatus.OK);
        Set<String> names = new HashSet<>();
        collectNames(response.getBody(), names);
        return names;
    }

    @Test
    void hostTreeIncludesHostOnlyPermissions() {
        HttpHeaders host = bearerHeaders(accessToken(null, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), null);
        Set<String> names = permissionNames(host);

        assertThat(names)
                .as("host permission tree must expose HOST-ONLY permissions")
                .contains("settings.host.manage", "languages.manage", "tenants.manage");
        assertThat(names)
                .as("host tree still exposes the shared tenant permissions")
                .contains("users.read");
    }

    @Test
    void tenantTreeExcludesHostOnlyPermissions() {
        HttpHeaders tenant = bearerHeaders(
                accessToken(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), DEFAULT_TENANT);
        Set<String> names = permissionNames(tenant);

        assertThat(names)
                .as("tenant permission tree must expose the shared tenant permissions")
                .contains("users.read");
        assertThat(names)
                .as("tenant permission tree must hide HOST-ONLY permissions")
                .doesNotContain("settings.host.manage", "languages.manage", "tenants.manage");
    }

    @Test
    void everyTreeNodeIsLocalizedAndNoneFallsBackToTheRawName() {
        HttpHeaders host = bearerHeaders(accessToken(null, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), null);
        host.set("Accept-Language", "en"); // deterministic locale for the label assertion
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/permissions/tree", HttpMethod.GET, new HttpEntity<>(host), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, String> displayNames = new LinkedHashMap<>();
        collectDisplayNames(response.getBody(), displayNames);
        assertThat(displayNames).as("the permission tree must not be empty").isNotEmpty();

        // A leaf permission (users.read) must resolve to its localized label, never the raw perm name.
        assertThat(displayNames).containsKey("users.read");
        assertThat(displayNames.get("users.read"))
                .as("leaf displayName must be localized, not the raw permission name")
                .isNotBlank()
                .isNotEqualTo("users.read")
                .isEqualTo("View users");

        // No node (group or leaf) may fall back to its own raw name.
        displayNames.forEach((name, displayName) ->
                assertThat(displayName)
                        .as("node '%s' must have a localized displayName, not the raw name", name)
                        .isNotBlank()
                        .isNotEqualTo(name));
    }

    private void collectDisplayNames(JsonNode node, Map<String, String> out) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            JsonNode name = node.get("name");
            JsonNode displayName = node.get("displayName");
            if (name != null && name.isTextual() && displayName != null && displayName.isTextual()) {
                out.put(name.asText(), displayName.asText());
            }
            node.forEach(child -> collectDisplayNames(child, out));
        } else if (node.isArray()) {
            node.forEach(child -> collectDisplayNames(child, out));
        }
    }

    private void collectNames(JsonNode node, Set<String> out) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            JsonNode name = node.get("name");
            if (name != null && name.isTextual()) {
                out.add(name.asText());
            }
            node.forEach(child -> collectNames(child, out));
        } else if (node.isArray()) {
            node.forEach(child -> collectNames(child, out));
        }
    }
}
