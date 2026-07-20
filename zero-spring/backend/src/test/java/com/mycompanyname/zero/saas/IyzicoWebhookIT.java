package com.mycompanyname.zero.saas;

import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.saas.billing.IyzicoBillingProviderTestHook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The iyzico intake contract (P2'-B), asserted on DATABASE state. Signature verification is the
 * REAL {@code IyzicoBillingProvider} implementation against a dummy secret
 * ({@code IyzicoTestProviderConfig}); signatures here are computed with plain {@code javax.crypto}
 * against the documented v3 formula, so the test cannot share a code path with the verification it
 * is testing (the {@code AbstractBillingIT} signing strategy). The retrieve is faked at the SPI
 * seam — the exact seam the reconciliation job reuses — with answers canned through the REAL
 * activation predicate ({@code IyzicoBillingProviderTestHook.mapRetrieve}).
 *
 * <p><b>What is being proven, uniquely for this provider: NOTHING DELIVERED TO US ACTIVATES.</b>
 * A hash-valid PayTR notification is itself the proof of collection; an iyzico webhook — even
 * signature-valid, even claiming SUCCESS — is only a TRIGGER, and so is the browser callback
 * (which carries no proof at all). The authoritative step is the retrieve query, and these tests
 * pin both directions: retrieve confirms → activation with every side effect; retrieve refuses →
 * 200, stored, and NOT ONE row of payment/subscription state moves.
 *
 * <p><b>Mutation evidence carried by this class</b> (each run against deliberately broken code,
 * red output recorded in the slice report):
 * <ul>
 *   <li>v3 verification skipped → {@link #invalidSignatureAnswers400AndStoresNothing} red;</li>
 *   <li>dedup insert clause dropped → {@link #duplicateReferenceCodeAnswers200AndProcessesOnce}
 *       red;</li>
 *   <li>webhook activates from the payload, retrieve skipped →
 *       {@link #webhookSuccessWithRefusingRetrieveActivatesNothing} red (the retrieve-authoritative
 *       proof).</li>
 * </ul>
 */
@Import(IyzicoTestProviderConfig.class)
@TestPropertySource(properties = {
        "zero.billing.iyzico.enabled=true",
        "zero.billing.iyzico.api-key=" + IyzicoWebhookIT.TEST_API_KEY,
        "zero.billing.iyzico.secret-key=" + IyzicoWebhookIT.TEST_SECRET_KEY,
        "zero.billing.iyzico.base-url=https://sandbox-api.iyzipay.com"
})
class IyzicoWebhookIT extends AbstractSaasIT {

    static final String TEST_API_KEY = "sandbox-it-dummy-api-key-never-real";
    static final String TEST_SECRET_KEY = "sandbox-it-dummy-secret-key-never-real";

    private static final String WEBHOOK_PATH = "/api/billing/webhook/iyzico";
    private static final String CALLBACK_PATH = "/api/billing/callback/iyzico";
    private static final String SIGNATURE_HEADER = "X-IYZ-SIGNATURE-V3";
    private static final String CF_EVENT_TYPE = "CHECKOUT_FORM_AUTH";

    @Autowired
    private JdbcTemplate jdbc;

    // ------------------------------------------------------------------ the authoritative path

    @Test
    @DisplayName("signed SUCCESS webhook + confirming retrieve -> 200, PAID, edition applied server-side")
    void validSuccessWebhookActivatesThroughRetrieve() {
        long editionId = createTryEdition("iyzico-hook-a", "9.99");
        long tenantId = ensureTenant("iyzico-hook-tenant-a");
        String token = startIyzicoCheckout(tenantId, editionId);
        assertThat(paymentRow(token).get("status")).isEqualTo("NOT_PAID");
        // The webhook payload CLAIMS one payment id, the retrieve answers ANOTHER: the row must
        // carry the retrieve's — the id the provider's server vouched for, not the delivered claim.
        canRetrieve(token, "success", "SUCCESS", 1, "ipay-authoritative-a");
        String referenceCode = uniqueReferenceCode();

        ResponseEntity<String> response = postWebhook(
                successPayload(token, "webhook-claimed-a", referenceCode));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("iyzico settles delivery on the status code alone — the ack is bodyless")
                .isNull();

        Map<String, Object> payment = paymentRow(token);
        assertThat(payment.get("status")).isEqualTo("PAID");
        assertThat(payment.get("paid_at")).as("paid_at must be stamped via the Clock").isNotNull();
        assertThat(payment.get("external_payment_id"))
                .as("the retrieve's payment id outranks the webhook payload's claim")
                .isEqualTo("ipay-authoritative-a");
        assertThat(payment.get("provider")).isEqualTo("iyzico");

        JsonNode subscription = subscriptionOf(getSubscription(tenantId));
        assertThat(subscription.path("editionId").asLong())
                .as("the confirmation itself must apply the target edition — no browser involved")
                .isEqualTo(editionId);
        assertThat(subscription.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(lastSubscriptionEventActor(tenantId))
                .as("the event trail must name THIS provider's webhook as the actor")
                .isEqualTo("iyzico-webhook");

        Map<String, Object> event = webhookEventRow(referenceCode);
        assertThat(event.get("status")).isEqualTo("PROCESSED");
        assertThat(event.get("event_type")).isEqualTo("CHECKOUT_COMPLETED");
        assertThat(event.get("processed_at")).isNotNull();
    }

    @Test
    @DisplayName("the SAME iyziReferenceCode delivered twice -> 200 twice, ONE processing, ONE retrieve")
    void duplicateReferenceCodeAnswers200AndProcessesOnce() {
        long editionId = createTryEdition("iyzico-hook-b", "12.50");
        long tenantId = ensureTenant("iyzico-hook-tenant-b");
        String token = startIyzicoCheckout(tenantId, editionId);
        canRetrieve(token, "success", "SUCCESS", 1, "ipay-b");
        String referenceCode = uniqueReferenceCode();
        String payload = successPayload(token, "ipay-b", referenceCode);

        assertThat(postWebhook(payload).getStatusCode()).isEqualTo(HttpStatus.OK);
        int eventsAfterFirst = subscriptionEventCount(tenantId);
        int retrievesAfterFirst = IyzicoTestProviderConfig.RETRIEVE_CALLS.get();
        Object paidAtAfterFirst = paymentRow(token).get("paid_at");

        ResponseEntity<String> second = postWebhook(payload);
        assertThat(second.getStatusCode())
                .as("a duplicate must NEVER answer 4xx/5xx — iyzico redelivers until it sees 200, "
                        + "and it only has three retries to spend (got %s: %s)",
                        second.getStatusCode(), second.getBody())
                .isEqualTo(HttpStatus.OK);

        assertThat(webhookEventCountFor(referenceCode))
                .as("the dedup key (provider, iyziReferenceCode) admits exactly one row")
                .isEqualTo(1);
        assertThat(IyzicoTestProviderConfig.RETRIEVE_CALLS.get())
                .as("the duplicate must short-circuit at dedup, BEFORE any provider query")
                .isEqualTo(retrievesAfterFirst);
        assertThat(subscriptionEventCount(tenantId))
                .as("no second activation: the subscription event trail must not have grown")
                .isEqualTo(eventsAfterFirst);
        assertThat(paymentRow(token).get("paid_at"))
                .as("the payment must not have been re-stamped")
                .isEqualTo(paidAtAfterFirst);
    }

    @Test
    @DisplayName("an invalid X-IYZ-SIGNATURE-V3 answers 400 ProblemDetail and stores nothing")
    void invalidSignatureAnswers400AndStoresNothing() {
        long editionId = createTryEdition("iyzico-hook-c", "15.00");
        long tenantId = ensureTenant("iyzico-hook-tenant-c");
        String token = startIyzicoCheckout(tenantId, editionId);
        canRetrieve(token, "success", "SUCCESS", 1, "ipay-c");
        int rowsBefore = webhookEventCount();
        int retrievesBefore = IyzicoTestProviderConfig.RETRIEVE_CALLS.get();

        String payload = successPayload(token, "ipay-c", uniqueReferenceCode());
        ResponseEntity<String> response = postWebhook(payload,
                "0000000000000000000000000000000000000000000000000000000000000000");

        assertThat(response.getStatusCode())
                .as("acknowledging an unverified delivery would confirm money nobody proved AND "
                        + "spend one of iyzico's three retries on it")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).contains("VALIDATION");
        assertThat(response.getBody())
                .as("the rejection must not echo the submitted payload back to the caller")
                .doesNotContain(token);

        assertThat(webhookEventCount())
                .as("an unauthenticated payload must not occupy a dedup slot")
                .isEqualTo(rowsBefore);
        assertThat(IyzicoTestProviderConfig.RETRIEVE_CALLS.get())
                .as("an unverified delivery must trigger no provider query either")
                .isEqualTo(retrievesBefore);
        assertThat(paymentRow(token).get("status")).isEqualTo("NOT_PAID");
    }

    /**
     * THE retrieve-authoritative proof (contract §4 row 3): a signature-VALID webhook claiming
     * SUCCESS, while the provider's own query says the payment did not collect. PayTR semantics
     * would activate here; iyzico semantics must not — the delivery is a trigger, the query is the
     * truth. Mutation-proved: activating from the payload (skipping the funnel) turns exactly this
     * test red while every signature and dedup test stays green.
     */
    @Test
    @DisplayName("webhook says SUCCESS but the retrieve refuses -> 200, stored, NOT activated")
    void webhookSuccessWithRefusingRetrieveActivatesNothing() {
        long editionId = createTryEdition("iyzico-hook-d", "20.00");
        long tenantId = ensureTenant("iyzico-hook-tenant-d");
        String token = startIyzicoCheckout(tenantId, editionId);
        canRetrieve(token, "success", "FAILURE", 1, null);
        int eventsBefore = subscriptionEventCount(tenantId);
        int retrievesBefore = IyzicoTestProviderConfig.RETRIEVE_CALLS.get();
        String referenceCode = uniqueReferenceCode();

        ResponseEntity<String> response = postWebhook(
                successPayload(token, "ipay-d-claimed", referenceCode));

        assertThat(response.getStatusCode())
                .as("the delivery itself was fine — 200; what it CLAIMED simply did not check out")
                .isEqualTo(HttpStatus.OK);
        assertThat(IyzicoTestProviderConfig.RETRIEVE_CALLS.get())
                .as("the funnel must actually have asked the provider")
                .isEqualTo(retrievesBefore + 1);

        Map<String, Object> payment = paymentRow(token);
        assertThat(payment.get("status"))
                .as("a webhook whose claim the provider's own query refuses must move NOTHING")
                .isEqualTo("NOT_PAID");
        assertThat(payment.get("paid_at")).isNull();
        assertThat(subscriptionEventCount(tenantId)).isEqualTo(eventsBefore);
        assertThat(webhookEventRow(referenceCode).get("status"))
                .as("the delivery is stored as evidence — PROCESSED, acted on as nothing")
                .isEqualTo("PROCESSED");
    }

    /**
     * fraudStatus 0 = payment under fraud review: collected money that may yet be clawed back, so
     * NOT activated — and the second half documents the documented-UNCERTAINTY handling: iyzico's
     * docs leave open whether a retry carries a NEW iyziReferenceCode, so one is simulated here;
     * it passes dedup BY DESIGN and runs the same idempotent retrieve path, which by then confirms.
     */
    @Test
    @DisplayName("fraudStatus=0 does not activate; a later redelivery (new reference code) confirms")
    void fraudReviewBlocksActivationUntilAConfirmingRecheck() {
        long editionId = createTryEdition("iyzico-hook-e", "25.00");
        long tenantId = ensureTenant("iyzico-hook-tenant-e");
        String token = startIyzicoCheckout(tenantId, editionId);
        canRetrieve(token, "success", "SUCCESS", 0, "ipay-e");

        assertThat(postWebhook(successPayload(token, "ipay-e", uniqueReferenceCode()))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(paymentRow(token).get("status"))
                .as("under-review money must not buy an activation the review may claw back")
                .isEqualTo("NOT_PAID");

        // The review approves; iyzico redelivers — possibly under a NEW reference code (documented
        // uncertainty). The state machine, not the dedup, is what must hold — and does.
        canRetrieve(token, "success", "SUCCESS", 1, "ipay-e");
        assertThat(postWebhook(successPayload(token, "ipay-e", uniqueReferenceCode()))
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> payment = paymentRow(token);
        assertThat(payment.get("status")).isEqualTo("PAID");
        assertThat(subscriptionOf(getSubscription(tenantId)).path("status").asText())
                .isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("signed FAILURE webhook marks the payment FAILED without any activation or retrieve")
    void failureWebhookMarksFailedWithoutActivation() {
        long editionId = createTryEdition("iyzico-hook-f", "30.00");
        long tenantId = ensureTenant("iyzico-hook-tenant-f");
        String token = startIyzicoCheckout(tenantId, editionId);
        int eventsBefore = subscriptionEventCount(tenantId);
        String referenceCode = uniqueReferenceCode();

        ResponseEntity<String> response = postWebhook(
                payload(CF_EVENT_TYPE, "FAILURE", token, "ipay-f", referenceCode));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> payment = paymentRow(token);
        assertThat(payment.get("status")).isEqualTo("FAILED");
        assertThat(payment.get("paid_at")).isNull();
        assertThat(subscriptionEventCount(tenantId)).isEqualTo(eventsBefore);
        assertThat(webhookEventRow(referenceCode).get("event_type")).isEqualTo("PAYMENT_FAILED");
    }

    // ------------------------------------------------------------------ the browser callback

    /**
     * The callback carries NO proof at all — so the only acceptable design is the one pinned here:
     * it may TRIGGER the authoritative query, and nothing it says is ever believed. First half:
     * retrieve refuses → the callback achieves nothing. Second half: retrieve confirms → the same
     * trigger activates, through the same service, with its own actor in the trail — and GET is
     * tolerated because iyzico's docs do not pin the callback's HTTP method.
     */
    @Test
    @DisplayName("callback (token only, no signature) triggers the retrieve and never trusts input")
    void callbackTriggersRetrieveButNeverTrustsInput() {
        long editionId = createTryEdition("iyzico-hook-g", "35.00");
        long tenantId = ensureTenant("iyzico-hook-tenant-g");
        String token = startIyzicoCheckout(tenantId, editionId);
        canRetrieve(token, "success", "FAILURE", 1, null);

        ResponseEntity<String> refused = postCallbackForm(token);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refused.getBody())
                .as("the anonymous caller learns nothing — no payment-status oracle")
                .isNull();
        assertThat(paymentRow(token).get("status"))
                .as("a callback whose retrieve refuses must activate NOTHING — this is the "
                        + "measured source-system bug (activation off a browser redirect) staying dead")
                .isEqualTo("NOT_PAID");

        canRetrieve(token, "success", "SUCCESS", 1, "ipay-g");
        ResponseEntity<String> confirmed = restTemplate.exchange(
                CALLBACK_PATH + "?token=" + token, HttpMethod.GET, HttpEntity.EMPTY, String.class);
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(paymentRow(token).get("status")).isEqualTo("PAID");
        assertThat(subscriptionOf(getSubscription(tenantId)).path("status").asText())
                .isEqualTo("ACTIVE");
        assertThat(lastSubscriptionEventActor(tenantId))
                .as("the trail must show the TRIGGER was the callback, the authority the retrieve")
                .isEqualTo("iyzico-callback");
    }

    /**
     * Stack-review Finding 4: a provider transport failure during the callback's confirmation must
     * not become an anonymous-drivable 500 with a stack trace at ERROR. The webhook's 500 is a
     * deliberate rollback-and-retry contract; the callback has no retry semantics and its contract
     * is "discloses nothing" — the failed trigger costs latency only, because the webhook and the
     * reconciliation job run the SAME confirmation.
     */
    @Test
    @DisplayName("a provider transport failure during the callback still answers 200, not 500")
    void callbackSurvivesAProviderTransportFailure() {
        long editionId = createTryEdition("iyzico-hook-i", "45.00");
        long tenantId = ensureTenant("iyzico-hook-tenant-i");
        String token = startIyzicoCheckout(tenantId, editionId);
        IyzicoTestProviderConfig.RETRIEVE_INTERCEPTOR.set(() -> {
            throw new IllegalStateException("simulated SDK transport failure");
        });
        ResponseEntity<String> response;
        try {
            response = postCallbackForm(token);
        } finally {
            IyzicoTestProviderConfig.RETRIEVE_INTERCEPTOR.set(null);
        }

        assertThat(response.getStatusCode())
                .as("the buyer's browser must not see an error page for a payment that may be "
                        + "fine, and an anonymous caller must not be able to mint ERROR stack "
                        + "traces by replaying a known token during a provider outage")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNull();
        assertThat(paymentRow(token).get("status"))
                .as("a failed trigger settles nothing — the webhook and the job remain the nets")
                .isEqualTo("NOT_PAID");
    }

    @Test
    @DisplayName("a callback with an unknown token answers 200 and triggers NO provider query")
    void callbackWithUnknownTokenTouchesNothing() {
        int retrievesBefore = IyzicoTestProviderConfig.RETRIEVE_CALLS.get();

        ResponseEntity<String> response = postCallbackForm("IYZTUNKNOWN" + UUID.randomUUID());

        assertThat(response.getStatusCode())
                .as("nothing to disclose, nothing to error about: a trigger about nothing")
                .isEqualTo(HttpStatus.OK);
        assertThat(IyzicoTestProviderConfig.RETRIEVE_CALLS.get())
                .as("an unknown token must not become a lever that drives outbound provider calls")
                .isEqualTo(retrievesBefore);
    }

    // ------------------------------------------------------------------ plumbing

    /** TRY on purpose: the iyzico adapter's currency contract (ADR-0017). */
    private long createTryEdition(String prefix, String monthlyPrice) {
        return createEdition(editionBody(uniqueEditionName(prefix), monthlyPrice, null, "TRY", 0, 7));
    }

    /** Starts an iyzico checkout as the host admin and returns the CF token (the session id). */
    private String startIyzicoCheckout(long tenantId, long editionId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", tenantId);
        body.put("editionId", editionId);
        body.put("billingPeriod", "MONTHLY");
        body.put("provider", "iyzico");
        body.put("successUrl", "https://app.example.com/billing/success");
        body.put("cancelUrl", "https://app.example.com/billing/cancel");
        ResponseEntity<JsonNode> response = restTemplate.exchange("/api/billing/checkout",
                HttpMethod.POST, new HttpEntity<>(body, host()), JsonNode.class);
        assertThat(response.getStatusCode())
                .as("checkout must start, got %s: %s", response.getStatusCode(), response.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody().path("sessionId").asText();
    }

    /** Cans the retrieve answer through the REAL activation predicate — not a hand-built record. */
    private static void canRetrieve(String token, String status, String paymentStatus,
                                    Integer fraudStatus, String paymentId) {
        IyzicoTestProviderConfig.RETRIEVE_RESULTS.put(token, IyzicoBillingProviderTestHook
                .mapRetrieve(status, paymentStatus, fraudStatus, paymentId, null));
    }

    private ResponseEntity<String> postWebhook(String payload) {
        return postWebhook(payload, signatureFor(payload));
    }

    private ResponseEntity<String> postWebhook(String payload, String signature) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(SIGNATURE_HEADER, signature);
        return restTemplate.exchange(WEBHOOK_PATH, HttpMethod.POST,
                new HttpEntity<>(payload, headers), String.class);
    }

    /** Form-encoded POST, the documented-adjacent callback shape (method itself is undocumented). */
    private ResponseEntity<String> postCallbackForm(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        return restTemplate.exchange(CALLBACK_PATH, HttpMethod.POST,
                new HttpEntity<>("token=" + token, headers), String.class);
    }

    private String successPayload(String token, String iyziPaymentId, String referenceCode) {
        return payload(CF_EVENT_TYPE, "SUCCESS", token, iyziPaymentId, referenceCode);
    }

    private String payload(String eventType, String status, String token, String iyziPaymentId,
                           String referenceCode) {
        return ("{\"paymentConversationId\":\"42\",\"merchantId\":123456,"
                + "\"paymentId\":\"%s\",\"status\":\"%s\",\"iyziReferenceCode\":\"%s\","
                + "\"iyziEventType\":\"%s\",\"iyziEventTime\":1700000000000,"
                + "\"token\":\"%s\",\"iyziPaymentId\":\"%s\"}")
                .formatted(iyziPaymentId, status, referenceCode, eventType, token, iyziPaymentId);
    }

    private static String uniqueReferenceCode() {
        return "iyzref-" + UUID.randomUUID();
    }

    /**
     * The documented v3 formula, computed with plain {@code javax.crypto} over the SAME fields the
     * production code reads out of the payload — independent of the implementation on purpose:
     * lowercase hex {@code HMAC-SHA256(key = secretKey, message = secretKey + iyziEventType +
     * iyziPaymentId + token + paymentConversationId + status)}.
     */
    private String signatureFor(String payloadJson) {
        try {
            JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payloadJson);
            String message = TEST_SECRET_KEY
                    + root.path("iyziEventType").asText("")
                    + root.path("iyziPaymentId").asText("")
                    + root.path("token").asText("")
                    + root.path("paymentConversationId").asText("")
                    + root.path("status").asText("");
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(TEST_SECRET_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (java.io.IOException | GeneralSecurityException ex) {
            throw new IllegalStateException("Cannot compute the test webhook signature", ex);
        }
    }

    // --- database state (asserted directly: DB truth, not response truth) ---

    private Map<String, Object> paymentRow(String token) {
        return jdbc.queryForMap("select * from payments where external_session_id = ?", token);
    }

    private int webhookEventCount() {
        Integer count = jdbc.queryForObject("select count(*) from webhook_events", Integer.class);
        return count == null ? 0 : count;
    }

    private int webhookEventCountFor(String eventId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from webhook_events where provider = 'iyzico' and event_id = ?",
                Integer.class, eventId);
        return count == null ? 0 : count;
    }

    private Map<String, Object> webhookEventRow(String eventId) {
        return jdbc.queryForMap(
                "select * from webhook_events where provider = 'iyzico' and event_id = ?", eventId);
    }

    private int subscriptionEventCount(long tenantId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from subscription_events e "
                        + "join subscriptions s on s.id = e.subscription_id where s.tenant_id = ?",
                Integer.class, tenantId);
        return count == null ? 0 : count;
    }

    private String lastSubscriptionEventActor(long tenantId) {
        return jdbc.queryForObject(
                "select e.actor from subscription_events e "
                        + "join subscriptions s on s.id = e.subscription_id "
                        + "where s.tenant_id = ? order by e.id desc limit 1",
                String.class, tenantId);
    }
}
