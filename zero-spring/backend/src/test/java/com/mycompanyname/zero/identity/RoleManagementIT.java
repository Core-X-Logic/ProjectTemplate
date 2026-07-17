package com.mycompanyname.zero.identity;

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
 * Phase 2 parity proof for role management (CONTRACT-phase2 §4.1).
 *
 * <p>Runs entirely in the {@code default} tenant scope because roles are tenant-scoped
 * ({@code tenantId = CurrentUser.tenantId()}). Verifies: full CRUD, clone, static-role
 * cannot be deleted (400 VALIDATION), a HOST-ONLY permission cannot be granted to a tenant
 * role (400 VALIDATION), and a role still assigned to a user cannot be deleted (409 CONFLICT).
 */
class RoleManagementIT extends AbstractIntegrationIT {

    private static final String DEFAULT_TENANT = "default";
    private static final AtomicInteger SEQ = new AtomicInteger();

    private String uniqueName(String prefix) {
        return prefix + "_" + System.nanoTime() + "_" + SEQ.incrementAndGet();
    }

    private HttpHeaders tenantAdmin() {
        return bearerHeaders(accessToken(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), DEFAULT_TENANT);
    }

    @Test
    void fullCrudAndCloneLifecycle() {
        HttpHeaders headers = tenantAdmin();
        String roleName = uniqueName("editor");

        // CREATE (roles.create) -> 201 RoleDetailDto with permissions
        Map<String, Object> createBody = Map.of(
                "name", roleName,
                "displayName", "Editors",
                "permissions", Set.of("users.read", "users.update"),
                "isDefault", false);
        ResponseEntity<JsonNode> created = restTemplate.exchange(
                "/api/roles", HttpMethod.POST, new HttpEntity<>(createBody, headers), JsonNode.class);
        assertThat(created.getStatusCode())
                .as("create role must be 201, got %s", created.getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        JsonNode createdRole = created.getBody();
        assertThat(createdRole).isNotNull();
        long roleId = createdRole.path("id").asLong();
        assertThat(roleId).isPositive();
        assertThat(createdRole.path("name").asText()).isEqualTo(roleName);
        assertThat(permissionsOf(createdRole)).contains("users.read", "users.update");
        assertThat(createdRole.path("isStatic").asBoolean()).isFalse();

        // READ by id (roles.read) -> permissions included
        ResponseEntity<JsonNode> fetched = restTemplate.exchange(
                "/api/roles/" + roleId, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(permissionsOf(fetched.getBody())).contains("users.read", "users.update");

        // LIST (roles.read) contains the new role
        ResponseEntity<JsonNode> list = restTemplate.exchange(
                "/api/roles?page=0&size=200", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(roleNames(pageContent(list.getBody()))).contains(roleName);

        // UPDATE (roles.update): displayName + permissions change
        Map<String, Object> updateBody = Map.of(
                "displayName", "Senior Editors",
                "permissions", Set.of("users.read"),
                "isDefault", false);
        ResponseEntity<JsonNode> updated = restTemplate.exchange(
                "/api/roles/" + roleId, HttpMethod.PUT, new HttpEntity<>(updateBody, headers), JsonNode.class);
        assertThat(updated.getStatusCode().is2xxSuccessful())
                .as("update role must succeed, got %s", updated.getStatusCode())
                .isTrue();
        ResponseEntity<JsonNode> afterUpdate = restTemplate.exchange(
                "/api/roles/" + roleId, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertThat(afterUpdate.getBody()).isNotNull();
        assertThat(afterUpdate.getBody().path("displayName").asText()).isEqualTo("Senior Editors");
        assertThat(permissionsOf(afterUpdate.getBody())).containsExactly("users.read");

        // CLONE (roles.create) -> new role named {name}_copy with the same permissions
        ResponseEntity<JsonNode> cloned = restTemplate.exchange(
                "/api/roles/" + roleId + "/clone", HttpMethod.POST, new HttpEntity<>(headers), JsonNode.class);
        assertThat(cloned.getStatusCode().is2xxSuccessful())
                .as("clone role must succeed, got %s", cloned.getStatusCode())
                .isTrue();
        JsonNode clone = cloned.getBody();
        assertThat(clone).isNotNull();
        assertThat(clone.path("name").asText()).isEqualTo(roleName + "_copy");
        assertThat(permissionsOf(clone)).containsExactly("users.read");
        assertThat(clone.path("id").asLong()).isNotEqualTo(roleId);
    }

    @Test
    void staticRoleCannotBeDeleted() {
        HttpHeaders headers = tenantAdmin();
        long adminRoleId = findRoleIdByName(headers, "Admin");
        assertThat(adminRoleId).as("seeded static Admin role must exist in default tenant").isPositive();

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/roles/" + adminRoleId, HttpMethod.DELETE, new HttpEntity<>(headers), JsonNode.class);

        assertThat(response.getStatusCode())
                .as("static role cannot be deleted -> 400 VALIDATION")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("VALIDATION");
    }

    @Test
    void hostOnlyPermissionCannotBeGrantedToTenantRole() {
        HttpHeaders headers = tenantAdmin();
        Map<String, Object> body = Map.of(
                "name", uniqueName("badrole"),
                "displayName", "Bad Role",
                "permissions", Set.of("settings.host.manage"),
                "isDefault", false);

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/roles", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);

        assertThat(response.getStatusCode())
                .as("HOST-ONLY permission on a tenant role -> 400 VALIDATION")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("VALIDATION");
    }

    @Test
    void roleAssignedToUserCannotBeDeleted() {
        HttpHeaders headers = tenantAdmin();
        String roleName = uniqueName("inuse");

        // create a deletable (non-static) role
        Map<String, Object> createRole = Map.of(
                "name", roleName,
                "displayName", "In Use",
                "permissions", Set.of("users.read"),
                "isDefault", false);
        ResponseEntity<JsonNode> role = restTemplate.exchange(
                "/api/roles", HttpMethod.POST, new HttpEntity<>(createRole, headers), JsonNode.class);
        assertThat(role.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long roleId = role.getBody().path("id").asLong();

        // create a user that carries the role
        Map<String, Object> createUser = Map.of(
                "username", uniqueName("holder"),
                "email", uniqueName("holder") + "@example.com",
                "password", "Password123!",
                "roleNames", Set.of(roleName));
        ResponseEntity<JsonNode> user = restTemplate.exchange(
                "/api/users", HttpMethod.POST, new HttpEntity<>(createUser, headers), JsonNode.class);
        assertThat(user.getStatusCode().is2xxSuccessful())
                .as("user creation must succeed, got %s", user.getStatusCode())
                .isTrue();

        // deleting a role that is assigned to a user -> 409 CONFLICT
        ResponseEntity<JsonNode> deleted = restTemplate.exchange(
                "/api/roles/" + roleId, HttpMethod.DELETE, new HttpEntity<>(headers), JsonNode.class);
        assertThat(deleted.getStatusCode())
                .as("role assigned to a user cannot be deleted -> 409")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    // --- helpers ---------------------------------------------------------

    private long findRoleIdByName(HttpHeaders headers, String name) {
        ResponseEntity<JsonNode> list = restTemplate.exchange(
                "/api/roles?page=0&size=200", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        for (JsonNode role : pageContent(list.getBody())) {
            if (name.equals(role.path("name").asText())) {
                return role.path("id").asLong();
            }
        }
        return -1L;
    }

    private List<String> permissionsOf(JsonNode role) {
        List<String> permissions = new ArrayList<>();
        role.path("permissions").forEach(node -> permissions.add(node.asText()));
        return permissions;
    }

    private List<String> roleNames(JsonNode content) {
        List<String> names = new ArrayList<>();
        content.forEach(node -> names.add(node.path("name").asText()));
        return names;
    }
}
