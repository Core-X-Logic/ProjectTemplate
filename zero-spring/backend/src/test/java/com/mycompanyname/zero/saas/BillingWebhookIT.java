package com.mycompanyname.zero.saas;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The webhook contract of the billing slice (G14), asserted on DATABASE state, not on response
 * bodies. Signature verification is the REAL {@code StripeBillingProvider} implementation against a
 * dummy {@code whsec} secret — see {@code BillingTestProviderConfig}.
 *
 * <p>The dedup assertions carry the negative-evidence obligation: with the {@code on conflict
 * (provider, event_id) do nothing} clause removed from {@code WebhookEventRepository.insertIfAbsent}
 * (the mutation named in the slice contract), {@link #duplicateDeliveryIsAcknowledgedWithoutReprocessing}
 * must go red on its 200 assertion — the duplicate then dies on the unique index and answers 409,
 * which is the measured source-system bug (duplicate → 4xx → infinite provider retry).
 */
@Import(BillingTestProviderConfig.class)
class BillingWebhookIT extends AbstractBillingIT {

    @Test
    @DisplayName("valid signed checkout.session.completed marks the payment PAID and activates server-side")
    void validSignedCheckoutCompletedActivatesTheSubscription() {
        long editionId = createPaidEdition("billing-hook-a", "10.00", null, 0, 7);
        long tenantId = ensureTenant("billing-hook-tenant-a");
        String sessionId = startCheckoutOk(tenantId, editionId).path("sessionId").asText();
        assertThat(paymentRowBySession(sessionId).get("status")).isEqualTo("NOT_PAID");

        String eventId = uniqueEventId();
        String payload = checkoutCompletedPayload(eventId, sessionId, "pi_hook_a_1");
        ResponseEntity<JsonNode> response = postWebhook(payload, signatureFor(payload));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> payment = paymentRowBySession(sessionId);
        assertThat(payment.get("status")).isEqualTo("PAID");
        assertThat(payment.get("paid_at")).as("paid_at must be stamped via the Clock").isNotNull();
        assertThat(payment.get("external_payment_id")).isEqualTo("pi_hook_a_1");

        JsonNode subscription = subscriptionOf(getSubscription(tenantId));
        assertThat(subscription.path("editionId").asLong())
                .as("the webhook itself must apply the target edition — no browser involved")
                .isEqualTo(editionId);
        assertThat(subscription.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(lastSubscriptionEventActor(tenantId))
                .as("the event trail must show the webhook as the actor of the activation")
                .isEqualTo("stripe-webhook");

        Map<String, Object> event = webhookEventRow(eventId);
        assertThat(event.get("status")).isEqualTo("PROCESSED");
        assertThat(event.get("processed_at")).isNotNull();
    }

    @Test
    @DisplayName("the SAME event delivered twice answers 200 twice and processes exactly once")
    void duplicateDeliveryIsAcknowledgedWithoutReprocessing() {
        long editionId = createPaidEdition("billing-hook-b", "12.00", null, 0, 7);
        long tenantId = ensureTenant("billing-hook-tenant-b");
        String sessionId = startCheckoutOk(tenantId, editionId).path("sessionId").asText();

        String eventId = uniqueEventId();
        String payload = checkoutCompletedPayload(eventId, sessionId, "pi_hook_b_1");
        String signature = signatureFor(payload);

        assertThat(postWebhook(payload, signature).getStatusCode()).isEqualTo(HttpStatus.OK);
        int eventsAfterFirst = subscriptionEventCount(tenantId);
        Object paidAtAfterFirst = paymentRowBySession(sessionId).get("paid_at");

        ResponseEntity<JsonNode> second = postWebhook(payload, signature);
        assertThat(second.getStatusCode())
                .as("a duplicate delivery must NEVER answer 4xx/5xx (source bug: 400 → infinite "
                        + "Stripe retry); got %s: %s", second.getStatusCode(), second.getBody())
                .isEqualTo(HttpStatus.OK);

        assertThat(webhookEventCountFor(eventId))
                .as("the dedup key admits exactly one row per (provider, event_id)")
                .isEqualTo(1);
        assertThat(subscriptionEventCount(tenantId))
                .as("no second activation: the subscription event trail must not have grown")
                .isEqualTo(eventsAfterFirst);
        assertThat(paymentRowBySession(sessionId).get("paid_at"))
                .as("the payment must not have been re-stamped")
                .isEqualTo(paidAtAfterFirst);
    }

    @Test
    @DisplayName("an invalid signature answers 400 and stores nothing")
    void invalidSignatureStoresNothing() {
        int rowsBefore = webhookEventCount();
        String payload = checkoutCompletedPayload(uniqueEventId(), "cs_test_never_created", "pi_x");

        ResponseEntity<JsonNode> forged = postWebhook(payload,
                "t=" + (System.currentTimeMillis() / 1000L) + ",v1=" + "0".repeat(64));
        assertThat(forged.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(forged.getBody()).isNotNull();
        assertThat(forged.getBody().path("code").asText()).isEqualTo("VALIDATION");
        assertThat(forged.getBody().toString())
                .as("the rejection must not echo the submitted payload back to the caller")
                .doesNotContain("cs_test_never_created");

        ResponseEntity<JsonNode> unsigned = postWebhook(payload, null);
        assertThat(unsigned.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(webhookEventCount())
                .as("an unauthenticated payload must not occupy a dedup slot")
                .isEqualTo(rowsBefore);
    }

    @Test
    @DisplayName("an unknown event type, validly signed, answers 200 and is stored IGNORED")
    void unknownEventTypeIsStoredIgnored() {
        String eventId = uniqueEventId();
        String payload = unknownEventPayload(eventId);

        assertThat(postWebhook(payload, signatureFor(payload)).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        Map<String, Object> event = webhookEventRow(eventId);
        assertThat(event.get("status")).isEqualTo("IGNORED");
        assertThat(event.get("event_type")).isEqualTo("UNKNOWN");
        assertThat(event.get("processed_at")).isNotNull();
    }

    @Test
    @DisplayName("invoice.paid with billing_reason=subscription_cycle maps to RECURRING_PAYMENT_SUCCEEDED and is stored")
    void recurringPaymentEventIsMappedAndStored() {
        String eventId = uniqueEventId();
        String payload = invoicePaidSubscriptionCyclePayload(eventId);

        assertThat(postWebhook(payload, signatureFor(payload)).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        Map<String, Object> event = webhookEventRow(eventId);
        assertThat(event.get("event_type"))
                .as("the provider mapping must recognise a renewal charge, even though acting on it "
                        + "is a later slice")
                .isEqualTo("RECURRING_PAYMENT_SUCCEEDED");
        assertThat(event.get("status")).isEqualTo("IGNORED");
    }

    /**
     * The single-transaction rollback claim, measured instead of asserted in a javadoc: a failure
     * AFTER the dedup insert must roll the dedup row back too, otherwise the provider's retry hits
     * dedup, answers 200, and the payment is swallowed with every gate green. Mutation proof for
     * THIS test: catch the processing exception in {@code BillingWebhookService.handle} and answer
     * 200 (the future-bug) — the 500 assertion and the zero-row assertion both go red.
     */
    @Test
    @DisplayName("a failed attempt rolls back its dedup row, so redelivery of the SAME event id succeeds")
    void failedProcessingRollsBackTheDedupRowSoTheRetrySucceeds() {
        long editionId = createPaidEdition("billing-hook-d", "16.00", null, 0, 7);
        long tenantId = ensureTenant("billing-hook-tenant-d");

        // A validly signed completion event for a session NO payment row matches (yet): the
        // transient-ordering shape. Processing must fail loudly, not swallow.
        String orphanSessionId = "cs_test_orphan_" + uniqueEventId();
        String eventId = uniqueEventId();
        String payload = checkoutCompletedPayload(eventId, orphanSessionId, "pi_hook_d_1");
        String signature = signatureFor(payload);

        ResponseEntity<JsonNode> first = postWebhook(payload, signature);
        assertThat(first.getStatusCode())
                .as("a completed checkout with no payment row is a failure Stripe must retry, "
                        + "never a silent 200 (got %s: %s)", first.getStatusCode(), first.getBody())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(webhookEventCountFor(eventId))
                .as("the failed attempt must roll its dedup row back — a committed row here means "
                        + "every retry answers 200 while nothing was processed (a lost payment)")
                .isZero();

        // The payment row arrives late: a real checkout, re-keyed to the session the event named.
        String sessionId = startCheckoutOk(tenantId, editionId).path("sessionId").asText();
        jdbc.update("update payments set external_session_id = ? where external_session_id = ?",
                orphanSessionId, sessionId);

        // Stripe redelivers the SAME event id. Because nothing committed, this is not a duplicate.
        ResponseEntity<JsonNode> retry = postWebhook(payload, signature);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(paymentRowBySession(orphanSessionId).get("status")).isEqualTo("PAID");
        assertThat(webhookEventRow(eventId).get("status")).isEqualTo("PROCESSED");
        assertThat(subscriptionOf(getSubscription(tenantId)).path("status").asText())
                .isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("a DIFFERENT event id replaying an already-paid session answers 200 without a second activation")
    void replayAgainstAPaidSessionDoesNotActivateTwice() {
        long editionId = createPaidEdition("billing-hook-c", "14.00", null, 0, 7);
        long tenantId = ensureTenant("billing-hook-tenant-c");
        String sessionId = startCheckoutOk(tenantId, editionId).path("sessionId").asText();

        String firstPayload = checkoutCompletedPayload(uniqueEventId(), sessionId, "pi_hook_c_1");
        assertThat(postWebhook(firstPayload, signatureFor(firstPayload)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        int eventsAfterFirst = subscriptionEventCount(tenantId);

        // A fresh event id defeats the dedup on purpose: this exercises the SECOND idempotency
        // layer, the payment-status guard.
        String replayEventId = uniqueEventId();
        String replayPayload = checkoutCompletedPayload(replayEventId, sessionId, "pi_hook_c_2");
        ResponseEntity<JsonNode> replay = postWebhook(replayPayload, signatureFor(replayPayload));
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(webhookEventRow(replayEventId).get("status")).isEqualTo("PROCESSED");
        assertThat(subscriptionEventCount(tenantId))
                .as("the already-PAID payment must not activate the subscription a second time")
                .isEqualTo(eventsAfterFirst);
        Map<String, Object> payment = paymentRowBySession(sessionId);
        assertThat(payment.get("status")).isEqualTo("PAID");
        assertThat(payment.get("external_payment_id"))
                .as("the first confirmation wins; the replay must not overwrite it")
                .isEqualTo("pi_hook_c_1");
    }
}
