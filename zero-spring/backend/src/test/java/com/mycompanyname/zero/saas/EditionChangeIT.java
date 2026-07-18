package com.mycompanyname.zero.saas;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F5 Slice B proof for the edition change endpoint (state-table row S13).
 *
 * <p>The arithmetic itself is pinned down by {@code ProrationCalculatorTest}; what is asserted here
 * is the domain behaviour around it — the two rules carried over from the source system that are
 * easy to get wrong:
 *
 * <ul>
 *   <li>the billing period end is <b>not</b> shifted, because the pro-rated amount already prices
 *       the unused remainder and moving the date would charge for it twice;</li>
 *   <li>the price snapshot follows the new edition, so a later catalogue edit still cannot reach an
 *       existing subscriber (ADR-0012).</li>
 * </ul>
 *
 * <p>Slice B collects nothing: the amount is reported and {@code paymentRequired} tells Slice C
 * whether a checkout is needed at all.
 */
class EditionChangeIT extends AbstractSaasIT {

    @Test
    void anUpgradeChargesTheDifferenceAndLeavesThePeriodEndWhereItWas() {
        long tenantId = ensureTenant("saas-change-upgrade");
        long cheapEditionId = createPaidEdition("change-cheap", "10.00", null, 0, 0);
        long expensiveEditionId = createPaidEdition("change-expensive", "40.00", null, 0, 0);
        assignEditionOk(tenantId, cheapEditionId, "MONTHLY", false);
        post("/api/subscriptions/" + tenantId + "/activate");
        String periodEndBefore = subscriptionOf(getSubscription(tenantId)).path("currentPeriodEndAt").asText();

        ResponseEntity<JsonNode> response = changeEdition(tenantId, expensiveEditionId, null);

        assertThat(response.getStatusCode())
                .as("edition change must succeed, got %s: %s", response.getStatusCode(), response.getBody())
                .isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();

        // Practically the whole period is unused, so practically the whole 30.00 difference is due.
        // The band absorbs the seconds that elapse between activation and this call.
        assertThat(body.path("prorationAmount").decimalValue())
                .as("an unused period must be charged at (target - current), not at the full target price")
                .isBetween(new BigDecimal("29.99"), new BigDecimal("30.00"));
        assertThat(body.path("currency").asText()).isEqualTo("USD");
        assertThat(body.path("paymentRequired").asBoolean()).isTrue();

        JsonNode subscription = body.path("subscription");
        assertThat(subscription.path("editionId").asLong()).isEqualTo(expensiveEditionId);
        assertThat(subscription.path("status").asText()).isEqualTo("ACTIVE");
        assertAmount(subscription.path("priceAmount"), "40.00");
        assertThat(subscription.path("currentPeriodEndAt").asText())
                .as("S13: proration already prices the remaining time, so the period end must not move")
                .isEqualTo(periodEndBefore);
    }

    @Test
    void aChangeBelowTheMinimumAppliesWithoutRequestingPayment() {
        long tenantId = ensureTenant("saas-change-minimum");
        long baseEditionId = createPaidEdition("change-base", "10.00", null, 0, 0);
        long slightlyDearerId = createPaidEdition("change-slightly-dearer", "10.50", null, 0, 0);
        assignEditionOk(tenantId, baseEditionId, "MONTHLY", false);
        post("/api/subscriptions/" + tenantId + "/activate");

        JsonNode body = changeEditionOk(tenantId, slightlyDearerId, null);

        assertThat(body.path("prorationAmount").decimalValue())
                .isBetween(new BigDecimal("0.49"), new BigDecimal("0.50"));
        assertThat(body.path("paymentRequired").asBoolean())
                .as("below zero.saas.proration.minimum-amount the edition changes for free, "
                        + "exactly as the source system's MinimumUpgradePaymentAmount did")
                .isFalse();
        assertThat(body.path("subscription").path("editionId").asLong())
                .as("and the change is applied immediately regardless")
                .isEqualTo(slightlyDearerId);
    }

    @Test
    void aDowngradeOntoAFreeEditionClearsThePriceAndTheDeadline() {
        long tenantId = ensureTenant("saas-change-downgrade");
        long paidEditionId = createPaidEdition("change-downgrade-paid", "40.00", null, 0, 0);
        long freeEditionId = createFreeEdition("change-downgrade-free");
        assignEditionOk(tenantId, paidEditionId, "MONTHLY", false);
        post("/api/subscriptions/" + tenantId + "/activate");

        JsonNode body = changeEditionOk(tenantId, freeEditionId, null);

        assertThat(body.path("prorationAmount").decimalValue())
                .as("a downgrade produces a negative amount; nothing is collected in this slice")
                .isBetween(new BigDecimal("-40.00"), new BigDecimal("-39.99"));
        assertThat(body.path("paymentRequired").asBoolean()).isFalse();
        assertThat(body.path("currency").isNull()).isTrue();

        JsonNode subscription = body.path("subscription");
        assertThat(subscription.path("priceAmount").isNull()).isTrue();
        assertThat(subscription.path("billingPeriod").isNull()).isTrue();
        assertThat(subscription.path("currentPeriodEndAt").isNull())
                .as("a free package never expires, so the deadline must be cleared")
                .isTrue();
    }

    @Test
    void aSubscriptionThatIsNotActiveMustBeReassignedInsteadOfChanged() {
        long tenantId = ensureTenant("saas-change-pending");
        long editionId = createPaidEdition("change-pending", "10.00", null, 0, 0);
        long otherEditionId = createPaidEdition("change-pending-other", "20.00", null, 0, 0);
        // No activation: the subscription sits in PENDING_PAYMENT.
        assignEditionOk(tenantId, editionId, "MONTHLY", false);

        ResponseEntity<JsonNode> response = changeEdition(tenantId, otherEditionId, null);

        assertThat(response.getStatusCode())
                .as("S13 starts from ACTIVE; anything else is a sale, not an upgrade")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("VALIDATION");
    }

    @Test
    void changingToTheEditionAlreadyHeldIsRejected() {
        long tenantId = ensureTenant("saas-change-same");
        long editionId = createPaidEdition("change-same", "10.00", null, 0, 0);
        assignEditionOk(tenantId, editionId, "MONTHLY", false);
        post("/api/subscriptions/" + tenantId + "/activate");

        ResponseEntity<JsonNode> response = changeEdition(tenantId, editionId, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("VALIDATION");
    }

    @Test
    void aTenantAdminCannotUpgradeItself() {
        long tenantId = tenantId(DEFAULT_TENANT);
        long editionId = createPaidEdition("change-escalation", "10.00", null, 0, 0);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("editionId", editionId);
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/subscriptions/" + tenantId + "/change-edition", HttpMethod.POST,
                new HttpEntity<>(body, tenantAdmin()), JsonNode.class);

        assertThat(response.getStatusCode())
                .as("every SaaS write stays Side.HOST (F5-R3)")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- helpers ---

    private ResponseEntity<JsonNode> changeEdition(long tenantId, long editionId, String billingPeriod) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("editionId", editionId);
        body.put("billingPeriod", billingPeriod);
        return restTemplate.exchange("/api/subscriptions/" + tenantId + "/change-edition",
                HttpMethod.POST, new HttpEntity<>(body, host()), JsonNode.class);
    }

    private JsonNode changeEditionOk(long tenantId, long editionId, String billingPeriod) {
        ResponseEntity<JsonNode> response = changeEdition(tenantId, editionId, billingPeriod);
        assertThat(response.getStatusCode())
                .as("edition change must succeed, got %s: %s", response.getStatusCode(), response.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }
}
