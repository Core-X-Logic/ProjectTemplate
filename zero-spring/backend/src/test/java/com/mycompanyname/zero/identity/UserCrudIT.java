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

import static org.assertj.core.api.Assertions.assertThat;

class UserCrudIT extends AbstractIntegrationIT {

    private static final String DEFAULT_TENANT = "default";

    @Test
    void userCrudFlowWithDuplicateUsernameConflict() {
        String token = accessToken(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD);
        HttpHeaders headers = bearerHeaders(token, DEFAULT_TENANT);

        // create
        Map<String, Object> createBody = Map.of(
                "username", "johndoe",
                "email", "johndoe@example.com",
                "password", "Password123!",
                "roleNames", Set.of("Admin"));
        ResponseEntity<JsonNode> created = restTemplate.exchange(
                "/api/users", HttpMethod.POST, new HttpEntity<>(createBody, headers), JsonNode.class);

        assertThat(created.getStatusCode().is2xxSuccessful())
                .as("create user must succeed, got %s", created.getStatusCode())
                .isTrue();
        JsonNode createdUser = created.getBody();
        assertThat(createdUser).isNotNull();
        long userId = createdUser.path("id").asLong();
        assertThat(userId).isPositive();
        assertThat(createdUser.path("username").asText()).isEqualTo("johndoe");

        // duplicate username in same tenant -> 409
        ResponseEntity<JsonNode> duplicate = restTemplate.exchange(
                "/api/users", HttpMethod.POST, new HttpEntity<>(createBody, headers), JsonNode.class);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // list contains the created user
        ResponseEntity<JsonNode> list = restTemplate.exchange(
                "/api/users?page=0&size=100", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode content = pageContent(list.getBody());
        List<String> usernames = new ArrayList<>();
        content.forEach(node -> usernames.add(node.path("username").asText()));
        assertThat(usernames).contains("johndoe");

        // update
        Map<String, Object> updateBody = Map.of(
                "username", "johndoe",
                "email", "john.updated@example.com",
                "password", "Password123!",
                "active", true,
                "roleNames", Set.of("Admin"));
        ResponseEntity<JsonNode> updated = restTemplate.exchange(
                "/api/users/" + userId, HttpMethod.PUT, new HttpEntity<>(updateBody, headers), JsonNode.class);
        assertThat(updated.getStatusCode().is2xxSuccessful())
                .as("update user must succeed, got %s", updated.getStatusCode())
                .isTrue();

        // fetch and verify the update landed
        ResponseEntity<JsonNode> fetched = restTemplate.exchange(
                "/api/users/" + userId, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).isNotNull();
        assertThat(fetched.getBody().path("email").asText()).isEqualTo("john.updated@example.com");

        // delete
        ResponseEntity<Void> deleted = restTemplate.exchange(
                "/api/users/" + userId, HttpMethod.DELETE, new HttpEntity<>(headers), Void.class);
        assertThat(deleted.getStatusCode().is2xxSuccessful())
                .as("delete user must succeed, got %s", deleted.getStatusCode())
                .isTrue();

        // get after delete -> 404
        ResponseEntity<JsonNode> afterDelete = restTemplate.exchange(
                "/api/users/" + userId, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertThat(afterDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
