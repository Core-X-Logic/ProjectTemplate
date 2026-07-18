package com.mycompanyname.zero.saas;

import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.saas.subscription.SubscriptionLifecycleProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F5 Slice B proof for the scheduled lifecycle (CONTRACT-phase5 Slice B, F5-ARCHITECTURE §3 S6-S10).
 *
 * <p>Time is moved with the injected {@link MutableClock} rather than by back-dating rows: that way
 * the production selection queries, the guard and the event trail are all exercised exactly as they
 * run in production, and nothing in the test knows how a deadline is stored.
 *
 * <p>The advance amounts are chosen to survive every month length. One billing month is at most 31
 * days, so +32 days always lands past the period end while the 5-day grace window (which starts at
 * the period end, i.e. at day 28-31) is still open — that is what makes GRACE observable rather than
 * skipped straight through to EXPIRED.
 */
@Import(MutableClockConfig.class)
class SubscriptionLifecycleIT extends AbstractSaasIT {

    @Autowired
    private MutableClock clock;

    @Autowired
    private SubscriptionLifecycleProcessor lifecycleProcessor;

    @AfterEach
    void restoreRealTime() {
        clock.reset();
    }

    @Test
    void anElapsedPaidPeriodEntersGraceAndThenExpires() {
        long tenantId = ensureTenant("saas-lifecycle-grace");
        long editionId = createPaidEdition("lifecycle-grace", "25.00", null, 0, 5);
        assignEditionOk(tenantId, editionId, "MONTHLY", false);
        post("/api/subscriptions/" + tenantId + "/activate");
        assertThat(statusOf(tenantId)).isEqualTo("ACTIVE");

        // S7: the billed period has elapsed and the edition grants a grace window.
        clock.advance(Duration.ofDays(32));
        lifecycleProcessor.processDueSubscriptions();

        JsonNode inGrace = subscriptionOf(getSubscription(tenantId));
        assertThat(inGrace.path("status").asText())
                .as("an edition with graceDayCount > 0 must not expire straight away")
                .isEqualTo("GRACE");
        assertThat(inGrace.path("graceEndAt").isNull())
                .as("entering GRACE must record when the window closes")
                .isFalse();

        // S9: the grace window has elapsed too.
        clock.advance(Duration.ofDays(10));
        lifecycleProcessor.processDueSubscriptions();

        assertThat(statusOf(tenantId)).isEqualTo("EXPIRED");
        assertThat(transitionsOf(tenantId))
                .as("the job must drive the state machine, so every step leaves a lifecycle event")
                .containsSequence("ACTIVE->GRACE", "GRACE->EXPIRED");
        assertThat(reasonsOf(tenantId)).contains("PERIOD_ENDED", "GRACE_ENDED");
        assertThat(actorsOf(tenantId)).contains(SubscriptionLifecycleProcessor.ACTOR);
    }

    @Test
    void anElapsedPaidPeriodWithoutGraceExpiresImmediately() {
        long tenantId = ensureTenant("saas-lifecycle-nograce");
        long editionId = createPaidEdition("lifecycle-nograce", "25.00", null, 0, 0);
        assignEditionOk(tenantId, editionId, "MONTHLY", false);
        post("/api/subscriptions/" + tenantId + "/activate");

        clock.advance(Duration.ofDays(32));
        lifecycleProcessor.processDueSubscriptions();

        assertThat(statusOf(tenantId)).isEqualTo("EXPIRED");
        assertThat(transitionsOf(tenantId)).contains("ACTIVE->EXPIRED");
    }

    /**
     * The asymmetry the source system had (F5-SAAS-INVENTORY §3, K9): its domain gave trials no
     * grace, but its worker's pre-filter granted one anyway. Here the edition offers a generous
     * grace window and the trial must still expire outright.
     */
    @Test
    void anElapsedTrialExpiresWithoutEverEnteringGrace() {
        long tenantId = ensureTenant("saas-lifecycle-trial");
        long editionId = createPaidEdition("lifecycle-trial", "25.00", null, 7, 30);
        assignEditionOk(tenantId, editionId, "MONTHLY", true);
        assertThat(statusOf(tenantId)).isEqualTo("TRIALING");

        clock.advance(Duration.ofDays(8));
        lifecycleProcessor.processDueSubscriptions();

        assertThat(statusOf(tenantId))
                .as("a trial has no grace window even when its edition defines one")
                .isEqualTo("EXPIRED");
        assertThat(transitionsOf(tenantId))
                .contains("TRIALING->EXPIRED")
                .doesNotContain("TRIALING->GRACE");
        assertThat(reasonsOf(tenantId)).contains("TRIAL_ENDED");
    }

    @Test
    void anExpiredSubscriptionIsDowngradedOntoItsFreeExpiringEdition() {
        long tenantId = ensureTenant("saas-lifecycle-downgrade");
        long freeEditionId = createFreeEdition("lifecycle-downgrade-free");
        long paidEditionId =
                createPaidEditionExpiringInto("lifecycle-downgrade", "25.00", 0, freeEditionId);
        assignEditionOk(tenantId, paidEditionId, "MONTHLY", false);
        post("/api/subscriptions/" + tenantId + "/activate");

        // A single run must both expire the period and land the tenant on the free edition.
        clock.advance(Duration.ofDays(32));
        lifecycleProcessor.processDueSubscriptions();

        JsonNode subscription = subscriptionOf(getSubscription(tenantId));
        assertThat(subscription.path("status").asText())
                .as("S10: an expiring edition means the tenant keeps a usable (free) package")
                .isEqualTo("ACTIVE");
        assertThat(subscription.path("editionId").asLong()).isEqualTo(freeEditionId);
        assertThat(subscription.path("currentPeriodEndAt").isNull())
                .as("the free package never expires")
                .isTrue();
        assertThat(subscription.path("priceAmount").isNull())
                .as("the paid snapshot must be cleared, otherwise the tenant looks billable")
                .isTrue();
        assertThat(subscription.path("billingPeriod").isNull()).isTrue();

        assertThat(transitionsOf(tenantId))
                .containsSequence("ACTIVE->EXPIRED", "EXPIRED->ACTIVE");
        assertThat(reasonsOf(tenantId)).contains("DOWNGRADED");
    }

    @Test
    void anExpiredSubscriptionWithoutAnExpiringEditionStaysExpired() {
        long tenantId = ensureTenant("saas-lifecycle-nodowngrade");
        long editionId = createPaidEdition("lifecycle-nodowngrade", "25.00", null, 0, 0);
        assignEditionOk(tenantId, editionId, "MONTHLY", false);
        post("/api/subscriptions/" + tenantId + "/activate");

        clock.advance(Duration.ofDays(32));
        lifecycleProcessor.processDueSubscriptions();
        // A second run must be a no-op rather than an error loop.
        lifecycleProcessor.processDueSubscriptions();

        assertThat(statusOf(tenantId))
                .as("with no downgrade target the tenant is simply locked out, as in the source system")
                .isEqualTo("EXPIRED");
        assertThat(transitionsOf(tenantId).stream().filter("ACTIVE->EXPIRED"::equals).count())
                .as("a repeated run must not append duplicate lifecycle events")
                .isEqualTo(1);
    }

    @Test
    void aSubscriptionThatIsNotDueIsLeftAlone() {
        long tenantId = ensureTenant("saas-lifecycle-notdue");
        long editionId = createPaidEdition("lifecycle-notdue", "25.00", null, 0, 5);
        assignEditionOk(tenantId, editionId, "MONTHLY", false);
        post("/api/subscriptions/" + tenantId + "/activate");
        int eventCount = eventsOf(tenantId).size();

        clock.advance(Duration.ofDays(5));
        lifecycleProcessor.processDueSubscriptions();

        assertThat(statusOf(tenantId)).isEqualTo("ACTIVE");
        assertThat(eventsOf(tenantId)).hasSize(eventCount);
    }

    // --- helpers ---

    private String statusOf(long tenantId) {
        return subscriptionOf(getSubscription(tenantId)).path("status").asText();
    }

    private List<JsonNode> eventsOf(long tenantId) {
        List<JsonNode> events = new ArrayList<>();
        getSubscription(tenantId).path("events").forEach(events::add);
        return events;
    }

    /** The lifecycle trail rendered as {@code FROM->TO} strings, in insertion order. */
    private List<String> transitionsOf(long tenantId) {
        return eventsOf(tenantId).stream()
                .map(event -> (event.path("fromStatus").isNull() ? "null" : event.path("fromStatus").asText())
                        + "->" + event.path("toStatus").asText())
                .toList();
    }

    private List<String> reasonsOf(long tenantId) {
        return eventsOf(tenantId).stream().map(event -> event.path("reason").asText()).toList();
    }

    private List<String> actorsOf(long tenantId) {
        return eventsOf(tenantId).stream().map(event -> event.path("actor").asText()).toList();
    }
}
