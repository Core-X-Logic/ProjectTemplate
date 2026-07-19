package com.mycompanyname.zero.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.AbstractIntegrationIT;
import com.mycompanyname.zero.identity.domain.Role;
import com.mycompanyname.zero.identity.domain.User;
import com.mycompanyname.zero.identity.ou.OrganizationUnit;
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
 * End-to-end coverage of entity change history, through the real HTTP surface.
 *
 * <p>Each covered entity is created and then updated; both a CREATED and an UPDATED
 * {@code entity_change} must appear, and the UPDATED change must carry the property that actually
 * changed with its original and new value.
 *
 * <p>Entity types are referenced as {@code X.class.getName()} rather than as name literals on
 * purpose. Tracking is driven by the {@code @TrackChanges} annotation precisely because a template
 * clone that renames the base package would otherwise silently lose entity history — a test that
 * hard-codes those same names would be renamed out of correctness along with the production code and
 * would keep passing against nothing.
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

        assertCreatedAndUpdated(admin, Role.class, roleId, "displayName", "HistOrig", "HistUpdated");
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

        assertCreatedAndUpdated(admin, OrganizationUnit.class, ouId, "displayName", "OuOrig", "OuUpdated");
    }

    /**
     * {@code User} is the entity most likely to be asked about in an audit ("who changed this
     * account?"), and it was previously covered only by the removed fully-qualified-name list, so
     * nothing in the suite noticed whether it was tracked at all.
     */
    @Test
    void userCreateAndUpdateAreTrackedWithPropertyChanges() {
        HttpHeaders admin = tenantAdmin();

        String username = unique("histuser");
        String originalEmail = username + "@example.com";
        String updatedEmail = username + ".updated@example.com";

        ResponseEntity<JsonNode> created = restTemplate.exchange(
                "/api/users", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "username", username,
                        "email", originalEmail,
                        "password", "Password123!",
                        "roleNames", Set.of("Admin")), admin),
                JsonNode.class);
        assertThat(created.getStatusCode().is2xxSuccessful())
                .as("user creation must succeed, got %s: %s", created.getStatusCode(), created.getBody())
                .isTrue();
        long userId = created.getBody().path("id").asLong();

        ResponseEntity<JsonNode> updated = restTemplate.exchange(
                "/api/users/" + userId, HttpMethod.PUT,
                new HttpEntity<>(Map.of(
                        "username", username,
                        "email", updatedEmail,
                        "password", "Password123!",
                        "active", true,
                        "roleNames", Set.of("Admin")), admin),
                JsonNode.class);
        assertThat(updated.getStatusCode().is2xxSuccessful())
                .as("user update must succeed, got %s: %s", updated.getStatusCode(), updated.getBody())
                .isTrue();

        assertCreatedAndUpdated(admin, User.class, userId, "email", originalEmail, updatedEmail);
    }

    // --- helpers ---

    /**
     * Asserts the full contract for one entity: a CREATED change, an UPDATED change, and the named
     * property recorded on the UPDATED change with the expected before/after values.
     */
    private void assertCreatedAndUpdated(HttpHeaders headers, Class<?> entityType, long entityId,
                                         String propertyName, String originalValue, String newValue) {
        List<JsonNode> changes = pollForChanges(headers, entityType, entityId);
        assertThat(changes)
                .as("entity_changes must be recorded for %s#%s — an empty result here is exactly the "
                        + "silent failure @TrackChanges exists to prevent", entityType.getSimpleName(), entityId)
                .isNotEmpty();

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
        JsonNode propertyChange = findPropertyChange(updateChange, propertyName);
        assertThat(propertyChange)
                .as("the %s property change must be recorded", propertyName)
                .isNotNull();
        assertThat(propertyChange.path("originalValue").asText()).isEqualTo(originalValue);
        assertThat(propertyChange.path("newValue").asText()).isEqualTo(newValue);
    }

    /** History is written after commit, so give the writer a bounded window to catch up. */
    private List<JsonNode> pollForChanges(HttpHeaders headers, Class<?> entityType, long entityId) {
        for (int attempt = 0; attempt < 15; attempt++) {
            List<JsonNode> changes = fetchChanges(headers, entityType, entityId);
            boolean hasCreated = changes.stream().anyMatch(c -> "CREATED".equals(c.path("changeType").asText()));
            boolean hasUpdated = changes.stream().anyMatch(c -> "UPDATED".equals(c.path("changeType").asText()));
            if (hasCreated && hasUpdated) {
                return changes;
            }
            sleep(400);
        }
        return fetchChanges(headers, entityType, entityId);
    }

    /** Filters by both entityId and entityTypeName: each entity's ids come from its own sequence. */
    private List<JsonNode> fetchChanges(HttpHeaders headers, Class<?> entityType, long entityId) {
        String typeName = entityType.getName();
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/entity-changes?entityTypeName=" + typeName + "&entityId=" + entityId + "&page=0&size=100",
                HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        List<JsonNode> result = new ArrayList<>();
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            for (JsonNode entry : pageContent(response.getBody())) {
                if (String.valueOf(entityId).equals(entry.path("entityId").asText())
                        && typeName.equals(entry.path("entityTypeName").asText())) {
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
