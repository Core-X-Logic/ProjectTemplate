package com.mycompanyname.zero.saas;

import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.saas.subscription.SubscriptionService;
import com.mycompanyname.zero.saas.subscription.SubscriptionStatus;
import com.mycompanyname.zero.shared.domain.DomainException;
import com.mycompanyname.zero.shared.domain.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the subscription state machine.
 *
 * <p>The decisive behaviour is that an illegal transition <em>throws</em> rather than silently doing
 * nothing, and that every legal transition leaves a {@code subscription_events} row. Lifecycle
 * transitions driven by elapsed time (GRACE/EXPIRED) have no HTTP route, so they are exercised
 * through the service — the same API the scheduled job calls.
 */
class SubscriptionStateMachineIT extends AbstractSaasIT {

    @Autowired
    private SubscriptionService subscriptionService;

    @Test
    void theHappyPathWalksPendingPaymentToActiveToGraceToExpired() {
        long tenantId = ensureTenant("saas-states");
        long editionId = createPaidEdition("states", "25.00", null, 0, 5);
        assignEditionOk(tenantId, editionId, "MONTHLY", false);

        assertThat(statusOf(tenantId)).isEqualTo("PENDING_PAYMENT");

        ResponseEntity<JsonNode> activated = post("/api/subscriptions/" + tenantId + "/activate");
        assertThat(activated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statusOf(tenantId)).isEqualTo("ACTIVE");

        // S7: the paid period elapsed and the edition grants a grace window
        subscriptionService.transition(tenantId, SubscriptionStatus.GRACE, "PERIOD_ENDED", "test");
        JsonNode inGrace = subscriptionOf(getSubscription(tenantId));
        assertThat(inGrace.path("status").asText()).isEqualTo("GRACE");
        assertThat(inGrace.path("graceEndAt").isNull())
                .as("entering GRACE must set the grace deadline from the edition's graceDayCount")
                .isFalse();

        // S9: the grace window elapsed
        subscriptionService.transition(tenantId, SubscriptionStatus.EXPIRED, "GRACE_ENDED", "test");
        assertThat(statusOf(tenantId)).isEqualTo("EXPIRED");

        assertThat(transitionsOf(tenantId))
                .as("every successful transition must be appended to the lifecycle trail")
                .containsSequence("PENDING_PAYMENT->ACTIVE", "ACTIVE->GRACE", "GRACE->EXPIRED");
    }

    @Test
    void anExpiredSubscriptionCanNeverReEnterATrial() {
        long tenantId = ensureTenant("saas-states-expired");
        long editionId = createPaidEdition("states-expired", "25.00", null, 0, 0);
        assignEditionOk(tenantId, editionId, "MONTHLY", false);
        subscriptionService.transition(tenantId, SubscriptionStatus.EXPIRED, "PERIOD_ENDED", "test");

        assertThatThrownBy(() ->
                subscriptionService.transition(tenantId, SubscriptionStatus.TRIALING, "ILLEGAL", "test"))
                .as("EXPIRED -> TRIALING is not in the state table and must be rejected, not ignored")
                .isInstanceOf(DomainException.class)
                .extracting(ex -> ((DomainException) ex).getCode())
                .isEqualTo(ErrorCode.VALIDATION);

        assertThat(statusOf(tenantId))
                .as("a rejected transition must leave the subscription untouched")
                .isEqualTo("EXPIRED");
        assertThat(transitionsOf(tenantId))
                .as("a rejected transition must not be recorded")
                .doesNotContain("EXPIRED->TRIALING");
    }

    @Test
    void cancellationIsTerminalAndActivatingAgainIsRejectedWith400() {
        long tenantId = ensureTenant("saas-states-cancel");
        long editionId = createPaidEdition("states-cancel", "25.00", null, 0, 0);
        assignEditionOk(tenantId, editionId, "MONTHLY", false);
        post("/api/subscriptions/" + tenantId + "/activate");

        ResponseEntity<JsonNode> cancelled = post("/api/subscriptions/" + tenantId + "/cancel");
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody()).isNotNull();
        JsonNode subscription = subscriptionOf(cancelled.getBody());
        assertThat(subscription.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(subscription.path("cancelledAt").isNull()).isFalse();
        assertThat(subscription.path("currentPeriodEndAt").isNull())
                .as("cancellation keeps access until the period ends, so the deadline is preserved")
                .isFalse();

        ResponseEntity<JsonNode> reactivated = post("/api/subscriptions/" + tenantId + "/activate");
        assertThat(reactivated.getStatusCode())
                .as("CANCELLED is terminal; coming back requires assigning a package again")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reactivated.getBody()).isNotNull();
        assertThat(reactivated.getBody().path("code").asText()).isEqualTo("VALIDATION");

        assertThat(transitionsOf(tenantId)).contains("ACTIVE->CANCELLED");
    }

    @Test
    void reAssigningAPackageRecoversACancelledSubscription() {
        long tenantId = ensureTenant("saas-states-recover");
        long editionId = createPaidEdition("states-recover", "25.00", null, 0, 0);
        assignEditionOk(tenantId, editionId, "MONTHLY", false);
        post("/api/subscriptions/" + tenantId + "/cancel");
        assertThat(statusOf(tenantId)).isEqualTo("CANCELLED");

        // Provisioning (state-table rows S1-S3) is not a transition, so the terminal state is no obstacle.
        long freeEditionId = createFreeEdition("states-recover-free");
        JsonNode subscription = subscriptionOf(assignEditionOk(tenantId, freeEditionId, null, false));

        assertThat(subscription.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(subscription.path("cancelledAt").isNull())
                .as("re-provisioning clears the cancellation marker")
                .isTrue();
    }

    // --- helpers ---

    private String statusOf(long tenantId) {
        return subscriptionOf(getSubscription(tenantId)).path("status").asText();
    }

    /** The lifecycle trail rendered as {@code FROM->TO} strings, in insertion order. */
    private List<String> transitionsOf(long tenantId) {
        List<String> transitions = new ArrayList<>();
        for (JsonNode event : getSubscription(tenantId).path("events")) {
            String from = event.path("fromStatus").isNull() ? "null" : event.path("fromStatus").asText();
            transitions.add(from + "->" + event.path("toStatus").asText());
        }
        return transitions;
    }
}
