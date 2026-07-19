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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage of user management.
 *
 * <p>Tenant-scoped ({@code default}). Verifies: extended create fields, role assignment,
 * organization-unit assignment, unlock, activate/deactivate (deactivation revokes refresh
 * tokens), soft delete (deleted user disappears from listings), duplicate username (409) and
 * the Apache POI xlsx export.
 */
class UserManagementIT extends AbstractIntegrationIT {

    private static final String DEFAULT_TENANT = "default";
    private static final AtomicInteger SEQ = new AtomicInteger();

    private String unique(String prefix) {
        return prefix + "_" + System.nanoTime() + "_" + SEQ.incrementAndGet();
    }

    private HttpHeaders tenantAdmin() {
        return bearerHeaders(accessToken(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), DEFAULT_TENANT);
    }

    private HttpHeaders hostAdmin() {
        return bearerHeaders(accessToken(null, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), null);
    }

    private List<String> listUsernames(HttpHeaders headers, String url) {
        ResponseEntity<JsonNode> list = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> usernames = new ArrayList<>();
        pageContent(list.getBody()).forEach(n -> usernames.add(n.path("username").asText()));
        return usernames;
    }

    private JsonNode createUser(HttpHeaders headers, String username, String password) {
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("email", username + "@example.com");
        body.put("password", password);
        body.put("name", "Given");
        body.put("surname", "Family");
        body.put("phoneNumber", "+1-555-0100");
        body.put("roleNames", Set.of("Admin"));
        ResponseEntity<JsonNode> created = restTemplate.exchange(
                "/api/users", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(created.getStatusCode().is2xxSuccessful())
                .as("create user must succeed, got %s", created.getStatusCode())
                .isTrue();
        assertThat(created.getBody()).isNotNull();
        return created.getBody();
    }

    @Test
    void createExposesExtendedProfileFields() {
        HttpHeaders headers = tenantAdmin();
        JsonNode user = createUser(headers, unique("profileuser"), "Password123!");

        assertThat(user.path("name").asText()).isEqualTo("Given");
        assertThat(user.path("surname").asText()).isEqualTo("Family");
        assertThat(user.path("phoneNumber").asText()).isEqualTo("+1-555-0100");
        assertThat(user.path("emailConfirmed").asBoolean()).isFalse();
    }

    @Test
    void assignRolesAndOrganizationUnits() {
        HttpHeaders headers = tenantAdmin();
        long userId = createUser(headers, unique("assignee"), "Password123!").path("id").asLong();

        // create a role and assign it
        String roleName = unique("viewer");
        Map<String, Object> roleBody = Map.of(
                "name", roleName,
                "displayName", "Viewer",
                "permissions", Set.of("users.read"),
                "isDefault", false);
        ResponseEntity<JsonNode> role = restTemplate.exchange(
                "/api/roles", HttpMethod.POST, new HttpEntity<>(roleBody, headers), JsonNode.class);
        assertThat(role.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> assignedRoles = restTemplate.exchange(
                "/api/users/" + userId + "/roles", HttpMethod.PUT,
                new HttpEntity<>(Map.of("roleNames", Set.of(roleName)), headers), JsonNode.class);
        assertThat(assignedRoles.getStatusCode().is2xxSuccessful())
                .as("role assignment must succeed, got %s", assignedRoles.getStatusCode())
                .isTrue();

        ResponseEntity<JsonNode> afterRoles = restTemplate.exchange(
                "/api/users/" + userId, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        List<String> roles = new ArrayList<>();
        afterRoles.getBody().path("roles").forEach(n -> roles.add(n.asText()));
        assertThat(roles).contains(roleName);

        // create an organization unit and assign it
        ResponseEntity<JsonNode> ou = restTemplate.exchange(
                "/api/organization-units", HttpMethod.POST,
                new HttpEntity<>(Map.of("displayName", unique("HR")), headers), JsonNode.class);
        assertThat(ou.getStatusCode().is2xxSuccessful())
                .as("organization unit creation must succeed, got %s", ou.getStatusCode())
                .isTrue();
        long ouId = ou.getBody().path("id").asLong();

        ResponseEntity<JsonNode> assignedOu = restTemplate.exchange(
                "/api/users/" + userId + "/organization-units", HttpMethod.PUT,
                new HttpEntity<>(Map.of("ouIds", Set.of(ouId)), headers), JsonNode.class);
        assertThat(assignedOu.getStatusCode().is2xxSuccessful())
                .as("organization-unit assignment must succeed, got %s", assignedOu.getStatusCode())
                .isTrue();
    }

    @Test
    void lockedUserCanBeUnlocked() {
        HttpHeaders headers = tenantAdmin();
        String username = unique("lockme");
        long userId = createUser(headers, username, "Password123!").path("id").asLong();

        // trip the lockout threshold (default 5 failed attempts)
        for (int i = 0; i < 6; i++) {
            login(DEFAULT_TENANT, username, "wrong-password-" + i);
        }

        // even a correct password is refused while locked
        ResponseEntity<JsonNode> whileLocked = login(DEFAULT_TENANT, username, "Password123!");
        assertThat(whileLocked.getStatusCode())
                .as("locked account must reject even the correct password")
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // unlock (users.unlock)
        ResponseEntity<JsonNode> unlock = restTemplate.exchange(
                "/api/users/" + userId + "/unlock", HttpMethod.POST, new HttpEntity<>(headers), JsonNode.class);
        assertThat(unlock.getStatusCode().is2xxSuccessful())
                .as("unlock must succeed, got %s", unlock.getStatusCode())
                .isTrue();

        // login now works
        ResponseEntity<JsonNode> afterUnlock = login(DEFAULT_TENANT, username, "Password123!");
        assertThat(afterUnlock.getStatusCode())
                .as("after unlock the correct password must authenticate")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void deactivationRevokesRefreshTokens() {
        HttpHeaders headers = tenantAdmin();
        String username = unique("deactme");
        long userId = createUser(headers, username, "Password123!").path("id").asLong();

        JsonNode pair = loginOk(DEFAULT_TENANT, username, "Password123!");
        String refreshToken = pair.path("refreshToken").asText();
        assertThat(refreshToken).isNotBlank();

        ResponseEntity<JsonNode> deactivated = restTemplate.exchange(
                "/api/users/" + userId + "/deactivate", HttpMethod.POST, new HttpEntity<>(headers), JsonNode.class);
        assertThat(deactivated.getStatusCode().is2xxSuccessful())
                .as("deactivate must succeed, got %s", deactivated.getStatusCode())
                .isTrue();

        // the previously issued refresh token must be revoked -> 401
        HttpHeaders json = new HttpHeaders();
        json.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> refresh = restTemplate.exchange(
                "/api/auth/refresh", HttpMethod.POST,
                new HttpEntity<>(Map.of("refreshToken", refreshToken), json), JsonNode.class);
        assertThat(refresh.getStatusCode())
                .as("a deactivated user's refresh token must be revoked")
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // reactivating restores login
        ResponseEntity<JsonNode> activated = restTemplate.exchange(
                "/api/users/" + userId + "/activate", HttpMethod.POST, new HttpEntity<>(headers), JsonNode.class);
        assertThat(activated.getStatusCode().is2xxSuccessful())
                .as("activate must succeed, got %s", activated.getStatusCode())
                .isTrue();
        assertThat(login(DEFAULT_TENANT, username, "Password123!").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void softDeletedUserDisappearsFromListings() {
        HttpHeaders headers = tenantAdmin();
        String username = unique("softdel");
        long userId = createUser(headers, username, "Password123!").path("id").asLong();

        ResponseEntity<Void> deleted = restTemplate.exchange(
                "/api/users/" + userId, HttpMethod.DELETE, new HttpEntity<>(headers), Void.class);
        assertThat(deleted.getStatusCode().is2xxSuccessful())
                .as("soft delete must succeed, got %s", deleted.getStatusCode())
                .isTrue();

        // not listed anymore
        ResponseEntity<JsonNode> list = restTemplate.exchange(
                "/api/users?page=0&size=200", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        List<String> usernames = new ArrayList<>();
        pageContent(list.getBody()).forEach(n -> usernames.add(n.path("username").asText()));
        assertThat(usernames).doesNotContain(username);

        // fetch by id -> 404
        ResponseEntity<JsonNode> byId = restTemplate.exchange(
                "/api/users/" + userId, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertThat(byId.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listSupportsCaseInsensitiveSearchScopedToTenant() {
        HttpHeaders headers = tenantAdmin();

        // Three tenant-scoped users: two match "ali" (via username/email), one does not.
        String aliceUsername = unique("aliceSearch");
        String alistairUsername = unique("alistairSearch");
        String bobUsername = unique("bobSearch");
        createUser(headers, aliceUsername, "Password123!");
        createUser(headers, alistairUsername, "Password123!");
        createUser(headers, bobUsername, "Password123!");

        // A user in another scope (host, tenant_id is null) whose username also matches "ali".
        String hostUsername = unique("alienHost");
        createUser(hostAdmin(), hostUsername, "Password123!");

        // search='ALI' proves case-insensitivity and returns only the matching tenant users.
        List<String> matched = listUsernames(headers, "/api/users?page=0&size=500&search=ALI");
        assertThat(matched)
                .as("case-insensitive search must return every username containing 'ali'")
                .contains(aliceUsername, alistairUsername);
        assertThat(matched)
                .as("search must exclude users that do not match the term")
                .doesNotContain(bobUsername);
        assertThat(matched)
                .as("tenant isolation: a host-scoped user must never leak into a tenant search")
                .doesNotContain(hostUsername);

        // Blank/absent search returns the full tenant-scoped listing (all three), still isolated.
        List<String> all = listUsernames(headers, "/api/users?page=0&size=500");
        assertThat(all).contains(aliceUsername, alistairUsername, bobUsername);
        assertThat(all)
                .as("tenant isolation must hold for the unfiltered listing too")
                .doesNotContain(hostUsername);
    }

    @Test
    void duplicateUsernameInSameTenantConflicts() {
        HttpHeaders headers = tenantAdmin();
        String username = unique("dupe");
        createUser(headers, username, "Password123!");

        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("email", username + "-2@example.com");
        body.put("password", "Password123!");
        body.put("roleNames", Set.of("Admin"));
        ResponseEntity<JsonNode> duplicate = restTemplate.exchange(
                "/api/users", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void excelExportReturnsXlsxWorkbook() {
        HttpHeaders headers = tenantAdmin();
        createUser(headers, unique("exportme"), "Password123!");

        ResponseEntity<byte[]> export = restTemplate.exchange(
                "/api/users/export", HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
        assertThat(export.getStatusCode()).isEqualTo(HttpStatus.OK);

        byte[] body = export.getBody();
        assertThat(body).isNotNull();
        assertThat(body.length).isGreaterThan(4);
        // xlsx is a ZIP container: the first two bytes are the local file header magic "PK"
        assertThat(body[0]).isEqualTo((byte) 0x50);
        assertThat(body[1]).isEqualTo((byte) 0x4B);

        String contentType = export.getHeaders().getContentType() == null
                ? "" : export.getHeaders().getContentType().toString();
        assertThat(contentType).contains("spreadsheetml.sheet");
    }
}
