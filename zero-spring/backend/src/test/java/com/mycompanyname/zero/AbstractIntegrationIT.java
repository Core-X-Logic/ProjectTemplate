package com.mycompanyname.zero;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base class for integration tests. Uses the singleton-container pattern: one
 * PostgreSQL container is started once per JVM and shared by every subclass,
 * so the Spring context (and the idempotent seed) is reused across IT classes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntegrationIT {

    protected static final String TENANT_HEADER = "X-Tenant";
    protected static final String SEED_ADMIN_USERNAME = "admin";
    protected static final String SEED_ADMIN_PASSWORD = "Admin123!";

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void registerDynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.cache.type", () -> "simple");
    }

    @Autowired
    protected TestRestTemplate restTemplate;

    protected ResponseEntity<JsonNode> login(String tenant, String usernameOrEmail, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (tenant != null) {
            headers.set(TENANT_HEADER, tenant);
        }
        Map<String, String> body = Map.of(
                "usernameOrEmail", usernameOrEmail,
                "password", password);
        return restTemplate.exchange(
                "/api/auth/login", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    protected JsonNode loginOk(String tenant, String usernameOrEmail, String password) {
        ResponseEntity<JsonNode> response = login(tenant, usernameOrEmail, password);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        return body;
    }

    protected String accessToken(String tenant, String usernameOrEmail, String password) {
        return loginOk(tenant, usernameOrEmail, password).path("accessToken").asText();
    }

    protected HttpHeaders bearerHeaders(String accessToken, String tenant) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (tenant != null) {
            headers.set(TENANT_HEADER, tenant);
        }
        return headers;
    }

    /** Supports both direct Page serialization ({"content": [...]}) and plain array bodies. */
    protected JsonNode pageContent(JsonNode body) {
        assertThat(body).isNotNull();
        return body.isArray() ? body : body.path("content");
    }
}
