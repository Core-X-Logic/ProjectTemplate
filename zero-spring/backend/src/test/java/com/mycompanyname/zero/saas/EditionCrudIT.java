package com.mycompanyname.zero.saas;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the edition catalogue.
 *
 * <p>Covers the full CRUD round-trip plus the three catalogue invariants: an edition that is still
 * sold cannot be deleted (409), an edition another edition downgrades into cannot be deleted (409),
 * and a downgrade target must be free (400).
 */
class EditionCrudIT extends AbstractSaasIT {

    private static final String TENANT = "saas-crud";

    @Test
    void hostAdminCanCreateReadUpdateAndDeleteAnEdition() {
        long editionId = createPaidEdition("crud", "10.00", "100.00", 14, 3);

        JsonNode detail = getEdition(editionId);
        JsonNode edition = detail.path("edition");
        assertThat(edition.path("free").asBoolean())
                .as("an edition carrying a price is not free")
                .isFalse();
        assertThat(edition.path("trialDayCount").asInt()).isEqualTo(14);
        assertThat(edition.path("graceDayCount").asInt()).isEqualTo(3);
        assertThat(edition.path("currency").asText()).isEqualTo("USD");
        assertAmount(edition.path("monthlyPrice"), "10.00");
        assertThat(detail.path("features").isArray())
                .as("detail must expose the feature editor rows")
                .isTrue();

        // Prices stay editable (ADR-0012): existing subscribers are protected by their snapshot.
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("displayName", "Renamed edition");
        update.put("monthlyPrice", "12.50");
        update.put("annualPrice", "125.00");
        update.put("currency", "EUR");
        update.put("trialDayCount", 7);
        update.put("graceDayCount", 5);
        update.put("active", false);
        update.put("sortOrder", 42);
        ResponseEntity<JsonNode> updated = restTemplate.exchange("/api/editions/" + editionId,
                HttpMethod.PUT, new HttpEntity<>(update, host()), JsonNode.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody()).isNotNull();
        JsonNode updatedEdition = updated.getBody().path("edition");
        assertThat(updatedEdition.path("displayName").asText()).isEqualTo("Renamed edition");
        assertAmount(updatedEdition.path("monthlyPrice"), "12.50");
        assertThat(updatedEdition.path("currency").asText()).isEqualTo("EUR");
        assertThat(updatedEdition.path("active").asBoolean()).isFalse();

        ResponseEntity<JsonNode> page = restTemplate.exchange("/api/editions?size=100",
                HttpMethod.GET, new HttpEntity<>(host()), JsonNode.class);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(idsOf(page.getBody())).contains(editionId);

        ResponseEntity<Void> deleted = restTemplate.exchange("/api/editions/" + editionId,
                HttpMethod.DELETE, new HttpEntity<>(host()), Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<JsonNode> gone = restTemplate.exchange("/api/editions/" + editionId,
                HttpMethod.GET, new HttpEntity<>(host()), JsonNode.class);
        assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anEditionAssignedToATenantCannotBeDeleted() {
        long tenantId = ensureTenant(TENANT);
        long editionId = createFreeEdition("crud-inuse");
        assignEditionOk(tenantId, editionId, null, false);

        ResponseEntity<JsonNode> response = restTemplate.exchange("/api/editions/" + editionId,
                HttpMethod.DELETE, new HttpEntity<>(host()), JsonNode.class);

        assertThat(response.getStatusCode())
                .as("an edition still sold to a tenant must not be deletable")
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("CONFLICT");
        assertThat(response.getBody().path("detail").asText())
                .as("the message must explain why the delete was refused")
                .contains("cannot be deleted");
    }

    @Test
    void anEditionUsedAsAnotherEditionsDowngradeTargetCannotBeDeleted() {
        long freeTarget = createFreeEdition("crud-target");

        Map<String, Object> body = editionBody(uniqueEditionName("crud-expiring"), "20.00", null, "USD", 0, 0);
        body.put("expiringEditionId", freeTarget);
        long expiringEditionId = createEdition(body);

        ResponseEntity<JsonNode> response = restTemplate.exchange("/api/editions/" + freeTarget,
                HttpMethod.DELETE, new HttpEntity<>(host()), JsonNode.class);

        assertThat(response.getStatusCode())
                .as("deleting the downgrade target would leave expiring subscriptions nowhere to land")
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("CONFLICT");

        // the dependent edition itself is unencumbered and may still be removed
        ResponseEntity<Void> deleted = restTemplate.exchange("/api/editions/" + expiringEditionId,
                HttpMethod.DELETE, new HttpEntity<>(host()), Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void theExpiringEditionMustBeFree() {
        long paidTarget = createPaidEdition("crud-paidtarget", "30.00", null, 0, 0);

        Map<String, Object> body = editionBody(uniqueEditionName("crud-bad"), "40.00", null, "USD", 0, 0);
        body.put("expiringEditionId", paidTarget);
        ResponseEntity<JsonNode> response = postEdition(body);

        assertThat(response.getStatusCode())
                .as("a downgrade must never land the tenant on a billable edition")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("VALIDATION");
    }

    @Test
    void aFreeEditionCannotOfferATrial() {
        Map<String, Object> body = editionBody(uniqueEditionName("crud-freetrial"), null, null, null, 7, 0);

        ResponseEntity<JsonNode> response = postEdition(body);

        assertThat(response.getStatusCode())
                .as("there is nothing to convert into, so a trial on a free edition is meaningless")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("VALIDATION");
    }

    @Test
    void aPricedEditionRequiresACurrency() {
        Map<String, Object> body = editionBody(uniqueEditionName("crud-nocurrency"), "15.00", null, null, 0, 0);

        ResponseEntity<JsonNode> response = postEdition(body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("VALIDATION");
    }

    private java.util.List<Long> idsOf(JsonNode body) {
        java.util.List<Long> ids = new java.util.ArrayList<>();
        pageContent(body).forEach(node -> ids.add(node.path("id").asLong()));
        return ids;
    }
}
