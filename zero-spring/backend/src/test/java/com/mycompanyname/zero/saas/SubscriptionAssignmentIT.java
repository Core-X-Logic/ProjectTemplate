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
 * Covers package assignment.
 *
 * <p>Three easily-broken guarantees are asserted: a tenant is never left without a
 * subscription (the {@code TenantCreated} listener provisions one in the same transaction), the
 * price is <em>snapshotted</em> so later catalogue edits cannot change what an existing subscriber
 * pays (ADR-0012), and a paid package waits in {@code PENDING_PAYMENT} until the server activates it
 * (ADR-0014).
 */
class SubscriptionAssignmentIT extends AbstractSaasIT {

    private static final String TENANT = "saas-assign";

    @Test
    void aNewTenantIsAutomaticallyProvisionedWithTheDefaultSubscription() {
        long tenantId = ensureTenant(TENANT);

        JsonNode subscription = subscriptionOf(getSubscription(tenantId));

        assertThat(subscription.path("status").asText())
                .as("the seeded default edition is free, so the tenant lands on an ACTIVE subscription")
                .isEqualTo("ACTIVE");
        assertThat(subscription.path("tenantId").asLong()).isEqualTo(tenantId);
        assertThat(subscription.path("editionName").asText()).isEqualTo("Standard");
        assertThat(subscription.path("currentPeriodEndAt").isNull())
                .as("a free subscription never expires")
                .isTrue();
        assertThat(subscription.path("priceAmount").isNull()).isTrue();

        JsonNode events = getSubscription(tenantId).path("events");
        assertThat(events.isArray()).isTrue();
        assertThat(events).isNotEmpty();
        assertThat(events.get(0).path("toStatus").asText()).isEqualTo("ACTIVE");
        assertThat(events.get(0).path("fromStatus").isNull())
                .as("provisioning has no source state")
                .isTrue();
    }

    @Test
    void assigningAPaidEditionWithATrialSnapshotsThePriceAndStartsTheTrial() {
        long tenantId = ensureTenant("saas-assign-trial");
        long editionId = createPaidEdition("assign-trial", "19.99", "199.90", 14, 0);

        JsonNode subscription = subscriptionOf(assignEditionOk(tenantId, editionId, "MONTHLY", true));

        assertThat(subscription.path("status").asText()).isEqualTo("TRIALING");
        assertThat(subscription.path("billingPeriod").asText()).isEqualTo("MONTHLY");
        assertAmount(subscription.path("priceAmount"), "19.99");
        assertThat(subscription.path("priceCurrency").asText()).isEqualTo("USD");
        assertThat(subscription.path("trialEndAt").isNull())
                .as("a trial must carry its end date")
                .isFalse();
    }

    @Test
    void theAnnualPriceIsSnapshottedWhenTheAnnualPeriodIsChosen() {
        long tenantId = ensureTenant("saas-assign-annual");
        long editionId = createPaidEdition("assign-annual", "19.99", "199.90", 0, 0);

        JsonNode subscription = subscriptionOf(assignEditionOk(tenantId, editionId, "ANNUAL", false));

        assertThat(subscription.path("billingPeriod").asText()).isEqualTo("ANNUAL");
        assertAmount(subscription.path("priceAmount"), "199.90");
    }

    @Test
    void theSnapshotSurvivesALaterEditionPriceChange() {
        long tenantId = ensureTenant("saas-assign-snapshot");
        long editionId = createPaidEdition("assign-snapshot", "19.99", null, 0, 0);
        assignEditionOk(tenantId, editionId, "MONTHLY", false);

        Map<String, Object> repriced = new LinkedHashMap<>();
        repriced.put("displayName", "Repriced");
        repriced.put("monthlyPrice", "49.99");
        repriced.put("currency", "USD");
        repriced.put("trialDayCount", 0);
        repriced.put("graceDayCount", 0);
        repriced.put("active", true);
        repriced.put("sortOrder", 100);
        ResponseEntity<JsonNode> updated = restTemplate.exchange("/api/editions/" + editionId,
                HttpMethod.PUT, new HttpEntity<>(repriced, host()), JsonNode.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode subscription = subscriptionOf(getSubscription(tenantId));
        assertAmount(subscription.path("priceAmount"), "19.99");
    }

    @Test
    void aPaidPackageAwaitsPaymentAndOnlyTheServerActivatesIt() {
        long tenantId = ensureTenant("saas-assign-pending");
        long editionId = createPaidEdition("assign-pending", "29.00", null, 0, 0);

        JsonNode assigned = subscriptionOf(assignEditionOk(tenantId, editionId, "MONTHLY", false));
        assertThat(assigned.path("status").asText())
                .as("without a trial a paid package cannot start before payment")
                .isEqualTo("PENDING_PAYMENT");
        assertThat(assigned.path("currentPeriodEndAt").isNull()).isTrue();

        ResponseEntity<JsonNode> activated = post("/api/subscriptions/" + tenantId + "/activate");
        assertThat(activated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(activated.getBody()).isNotNull();
        JsonNode subscription = subscriptionOf(activated.getBody());
        assertThat(subscription.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(subscription.path("currentPeriodEndAt").isNull())
                .as("activation starts the billed period")
                .isFalse();
    }

    @Test
    void aPaidEditionRequiresABillingPeriod() {
        long tenantId = ensureTenant("saas-assign-noperiod");
        long editionId = createPaidEdition("assign-noperiod", "9.00", null, 0, 0);

        ResponseEntity<JsonNode> response = assignEdition(tenantId, editionId, null, false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("VALIDATION");
    }

    @Test
    void aTrialCannotBeStartedOnAFreeEdition() {
        long tenantId = ensureTenant("saas-assign-freetrial");
        long editionId = createFreeEdition("assign-freetrial");

        ResponseEntity<JsonNode> response = assignEdition(tenantId, editionId, null, true);

        assertThat(response.getStatusCode())
                .as("a free edition has nothing to convert into, so a trial is invalid")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("VALIDATION");
    }

    @Test
    void aTrialCannotBeStartedOnAnEditionThatOffersNone() {
        long tenantId = ensureTenant("saas-assign-notrial");
        long editionId = createPaidEdition("assign-notrial", "11.00", null, 0, 0);

        ResponseEntity<JsonNode> response = assignEdition(tenantId, editionId, "MONTHLY", true);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("VALIDATION");
    }
}
