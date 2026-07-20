package com.mycompanyname.zero.saas;

import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.AbstractIntegrationIT;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared fixtures for the SaaS integration tests.
 *
 * <p>All IT classes share one Spring context and one PostgreSQL container, so every fixture here is
 * either <em>uniquely named</em> (editions) or <em>idempotent</em> (tenants): a test must never
 * depend on, or disturb, state another test class created.
 */
abstract class AbstractSaasIT extends AbstractIntegrationIT {

    protected static final String DEFAULT_TENANT = "default";

    protected HttpHeaders host() {
        return bearerHeaders(accessToken(null, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), null);
    }

    protected HttpHeaders tenantAdmin() {
        return bearerHeaders(
                accessToken(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), DEFAULT_TENANT);
    }

    // --- tenants ---

    /** Creates the tenant if needed and returns its id; safe to call from several IT classes. */
    protected long ensureTenant(String name) {
        HttpHeaders headers = host();
        ResponseEntity<JsonNode> created = restTemplate.exchange("/api/tenants", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", name, "displayName", name,
                        "adminEmail", "admin@" + name + ".local"), headers), JsonNode.class);
        if (created.getStatusCode() == HttpStatus.CREATED) {
            assertThat(created.getBody()).isNotNull();
            return created.getBody().path("id").asLong();
        }
        assertThat(created.getStatusCode())
                .as("tenant must be created or already exist")
                .isEqualTo(HttpStatus.CONFLICT);
        return tenantId(name);
    }

    protected long tenantId(String name) {
        ResponseEntity<JsonNode> list = restTemplate.exchange(
                "/api/tenants", HttpMethod.GET, new HttpEntity<>(host()), JsonNode.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        for (JsonNode node : pageContent(list.getBody())) {
            if (name.equals(node.path("name").asText())) {
                return node.path("id").asLong();
            }
        }
        throw new AssertionError("tenant not found: " + name);
    }

    // --- editions ---

    /** A never-colliding edition name, so parallel/repeated IT classes cannot clash on uq_editions_name. */
    protected String uniqueEditionName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected ResponseEntity<JsonNode> postEdition(Map<String, Object> body) {
        return restTemplate.exchange("/api/editions", HttpMethod.POST,
                new HttpEntity<>(body, host()), JsonNode.class);
    }

    protected long createEdition(Map<String, Object> body) {
        ResponseEntity<JsonNode> response = postEdition(body);
        assertThat(response.getStatusCode())
                .as("edition creation must succeed, got %s: %s", response.getStatusCode(), response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody().path("edition").path("id").asLong();
    }

    /** A free edition: no price at all, therefore no trial and eligible as a downgrade target. */
    protected long createFreeEdition(String prefix) {
        return createEdition(editionBody(uniqueEditionName(prefix), null, null, null, 0, 0));
    }

    protected long createPaidEdition(String prefix, String monthly, String annual, int trialDays, int graceDays) {
        return createEdition(editionBody(uniqueEditionName(prefix), monthly, annual, "USD", trialDays, graceDays));
    }

    /** A paid edition that falls back to {@code expiringEditionId} (which must be free) when it expires. */
    protected long createPaidEditionExpiringInto(String prefix, String monthly, int graceDays,
                                                 long expiringEditionId) {
        Map<String, Object> body =
                editionBody(uniqueEditionName(prefix), monthly, null, "USD", 0, graceDays);
        body.put("expiringEditionId", expiringEditionId);
        return createEdition(body);
    }

    /** Uses a LinkedHashMap because null values (an unpriced edition) are illegal in {@code Map.of}. */
    protected Map<String, Object> editionBody(String name, String monthly, String annual, String currency,
                                              int trialDays, int graceDays) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("displayName", name + " display");
        body.put("description", "created by an integration test");
        body.put("monthlyPrice", monthly);
        body.put("annualPrice", annual);
        body.put("currency", currency);
        body.put("trialDayCount", trialDays);
        body.put("graceDayCount", graceDays);
        body.put("active", true);
        body.put("sortOrder", 100);
        return body;
    }

    protected JsonNode getEdition(long editionId) {
        ResponseEntity<JsonNode> response = restTemplate.exchange("/api/editions/" + editionId,
                HttpMethod.GET, new HttpEntity<>(host()), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    protected ResponseEntity<JsonNode> setEditionFeatures(long editionId, List<Map<String, Object>> values) {
        return restTemplate.exchange("/api/editions/" + editionId + "/features", HttpMethod.PUT,
                new HttpEntity<>(values, host()), JsonNode.class);
    }

    // --- subscriptions ---

    protected ResponseEntity<JsonNode> assignEdition(long tenantId, long editionId, String period, boolean trial) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("editionId", editionId);
        body.put("billingPeriod", period);
        body.put("trial", trial);
        return restTemplate.exchange("/api/subscriptions/" + tenantId + "/edition", HttpMethod.PUT,
                new HttpEntity<>(body, host()), JsonNode.class);
    }

    protected JsonNode assignEditionOk(long tenantId, long editionId, String period, boolean trial) {
        ResponseEntity<JsonNode> response = assignEdition(tenantId, editionId, period, trial);
        assertThat(response.getStatusCode())
                .as("assignment must succeed, got %s: %s", response.getStatusCode(), response.getBody())
                .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    protected JsonNode getSubscription(long tenantId) {
        ResponseEntity<JsonNode> response = restTemplate.exchange("/api/subscriptions/" + tenantId,
                HttpMethod.GET, new HttpEntity<>(host()), JsonNode.class);
        assertThat(response.getStatusCode())
                .as("subscription must be readable, got %s: %s", response.getStatusCode(), response.getBody())
                .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    protected ResponseEntity<JsonNode> post(String path) {
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(host()), JsonNode.class);
    }

    // --- assertions ---

    /** Compares numerically so a {@code numeric(19,4)} round-trip (19.99 -> 19.9900) still matches. */
    protected void assertAmount(JsonNode node, String expected) {
        assertThat(node.isNull() || node.isMissingNode())
                .as("expected an amount of %s but the field was absent", expected)
                .isFalse();
        assertThat(new BigDecimal(node.asText()))
                .usingComparator(BigDecimal::compareTo)
                .isEqualTo(new BigDecimal(expected));
    }

    protected JsonNode subscriptionOf(JsonNode detail) {
        return detail.path("subscription");
    }
}
