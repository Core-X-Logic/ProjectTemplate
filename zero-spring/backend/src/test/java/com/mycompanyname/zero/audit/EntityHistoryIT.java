package com.mycompanyname.zero.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.AbstractIntegrationIT;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage of entity change history.
 *
 * <p>{@code Role} is a tracked entity. Creating then renaming a role must produce a CREATED and an
 * UPDATED entity_change, and the UPDATED change must record the {@code displayName} property with
 * its original and new value.
 */
class EntityHistoryIT extends AbstractIntegrationIT {

    private static final String DEFAULT_TENANT = "default";
    private static final AtomicInteger SEQ = new AtomicInteger();

    private String unique(String prefix) {
        return prefix + "_" + System.nanoTime() + "_" + SEQ.incrementAndGet();
    }

    private HttpHeaders tenantAdmin() {
        return bearerHeaders(accessToken(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), DEFAULT_TENANT);
    }

    @Test
    void roleCreateAndUpdateAreTrackedWithPropertyChanges() {
        HttpHeaders admin = tenantAdmin();

        // CREATE -> CREATED history
        Map<String, Object> createBody = Map.of(
                "name", unique("tracked"),
                "displayName", "HistOrig",
                "permissions", Set.of("users.read"),
                "isDefault", false);
        ResponseEntity<JsonNode> created = restTemplate.exchange(
                "/api/roles", HttpMethod.POST, new HttpEntity<>(createBody, admin), JsonNode.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long roleId = created.getBody().path("id").asLong();

        // UPDATE displayName -> UPDATED history with a property change
        Map<String, Object> updateBody = Map.of(
                "displayName", "HistUpdated",
                "permissions", Set.of("users.read"),
                "isDefault", false);
        ResponseEntity<JsonNode> updated = restTemplate.exchange(
                "/api/roles/" + roleId, HttpMethod.PUT, new HttpEntity<>(updateBody, admin), JsonNode.class);
        assertThat(updated.getStatusCode().is2xxSuccessful())
                .as("role update must succeed, got %s", updated.getStatusCode())
                .isTrue();

        List<JsonNode> changes = pollForChanges(admin, roleId);
        assertThat(changes).as("entity_changes must be recorded for the role").isNotEmpty();

        List<String> changeTypes = new ArrayList<>();
        JsonNode updateChange = null;
        for (JsonNode change : changes) {
            String type = change.path("changeType").asText();
            changeTypes.add(type);
            if ("UPDATED".equals(type)) {
                updateChange = change;
            }
        }
        assertThat(changeTypes).contains("CREATED", "UPDATED");

        assertThat(updateChange).as("an UPDATED change must exist").isNotNull();
        JsonNode displayNameChange = findPropertyChange(updateChange, "displayName");
        assertThat(displayNameChange).as("the displayName property change must be recorded").isNotNull();
        assertThat(displayNameChange.path("originalValue").asText()).isEqualTo("HistOrig");
        assertThat(displayNameChange.path("newValue").asText()).isEqualTo("HistUpdated");
    }

    @Test
    void organizationUnitCreateAndUpdateAreTrackedWithPropertyChanges() {
        HttpHeaders admin = tenantAdmin();

        // CREATE an organization unit -> CREATED history
        ResponseEntity<JsonNode> created = restTemplate.exchange(
                "/api/organization-units", HttpMethod.POST,
                new HttpEntity<>(Map.of("displayName", "OuOrig"), admin), JsonNode.class);
        assertThat(created.getStatusCode().is2xxSuccessful())
                .as("organization unit creation must succeed, got %s", created.getStatusCode())
                .isTrue();
        long ouId = created.getBody().path("id").asLong();

        // UPDATE displayName -> UPDATED history with a property change
        ResponseEntity<JsonNode> updated = restTemplate.exchange(
                "/api/organization-units/" + ouId, HttpMethod.PUT,
                new HttpEntity<>(Map.of("displayName", "OuUpdated"), admin), JsonNode.class);
        assertThat(updated.getStatusCode().is2xxSuccessful())
                .as("organization unit update must succeed, got %s", updated.getStatusCode())
                .isTrue();

        List<JsonNode> changes = pollForOuChanges(admin, ouId);
        assertThat(changes).as("entity_changes must be recorded for the organization unit").isNotEmpty();

        List<String> changeTypes = new ArrayList<>();
        JsonNode updateChange = null;
        for (JsonNode change : changes) {
            String type = change.path("changeType").asText();
            changeTypes.add(type);
            if ("UPDATED".equals(type)) {
                updateChange = change;
            }
        }
        assertThat(changeTypes).contains("CREATED", "UPDATED");

        assertThat(updateChange).as("an UPDATED change must exist").isNotNull();
        JsonNode displayNameChange = findPropertyChange(updateChange, "displayName");
        assertThat(displayNameChange).as("the displayName property change must be recorded").isNotNull();
        assertThat(displayNameChange.path("originalValue").asText()).isEqualTo("OuOrig");
        assertThat(displayNameChange.path("newValue").asText()).isEqualTo("OuUpdated");
    }

    private static final String OU_TYPE = "com.mycompanyname.zero.identity.ou.OrganizationUnit";

    private List<JsonNode> pollForOuChanges(HttpHeaders headers, long ouId) {
        for (int attempt = 0; attempt < 15; attempt++) {
            List<JsonNode> changes = fetchOuChanges(headers, ouId);
            boolean hasCreated = changes.stream().anyMatch(c -> "CREATED".equals(c.path("changeType").asText()));
            boolean hasUpdated = changes.stream().anyMatch(c -> "UPDATED".equals(c.path("changeType").asText()));
            if (hasCreated && hasUpdated) {
                return changes;
            }
            sleep(400);
        }
        return fetchOuChanges(headers, ouId);
    }

    /** Filters by both entityId and entityTypeName: OU and Role ids come from independent sequences. */
    private List<JsonNode> fetchOuChanges(HttpHeaders headers, long ouId) {
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/entity-changes?entityTypeName=" + OU_TYPE + "&entityId=" + ouId + "&page=0&size=100",
                HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        List<JsonNode> result = new ArrayList<>();
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            for (JsonNode entry : pageContent(response.getBody())) {
                if (String.valueOf(ouId).equals(entry.path("entityId").asText())
                        && OU_TYPE.equals(entry.path("entityTypeName").asText())) {
                    result.add(entry);
                }
            }
        }
        return result;
    }

    private List<JsonNode> pollForChanges(HttpHeaders headers, long roleId) {
        for (int attempt = 0; attempt < 15; attempt++) {
            List<JsonNode> changes = fetchChanges(headers, roleId);
            boolean hasCreated = changes.stream().anyMatch(c -> "CREATED".equals(c.path("changeType").asText()));
            boolean hasUpdated = changes.stream().anyMatch(c -> "UPDATED".equals(c.path("changeType").asText()));
            if (hasCreated && hasUpdated) {
                return changes;
            }
            sleep(400);
        }
        return fetchChanges(headers, roleId);
    }

    private List<JsonNode> fetchChanges(HttpHeaders headers, long roleId) {
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/entity-changes?entityId=" + roleId + "&page=0&size=200",
                HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        List<JsonNode> result = new ArrayList<>();
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            for (JsonNode entry : pageContent(response.getBody())) {
                if (String.valueOf(roleId).equals(entry.path("entityId").asText())) {
                    result.add(entry);
                }
            }
        }
        return result;
    }

    /** The property-change collection may be exposed as "propertyChanges" or "properties". */
    private JsonNode findPropertyChange(JsonNode change, String propertyName) {
        JsonNode list = change.path("propertyChanges");
        if (list.isMissingNode() || !list.isArray() || list.isEmpty()) {
            list = change.path("properties");
        }
        for (JsonNode property : list) {
            if (propertyName.equals(property.path("propertyName").asText())) {
                return property;
            }
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
