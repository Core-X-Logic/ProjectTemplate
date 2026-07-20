package com.mycompanyname.zero.saas;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The PayTR notification contract (P2'-A), asserted on DATABASE state AND on the exact response
 * bytes. Hash verification is the REAL {@code PayTRBillingProvider} implementation against dummy
 * merchant credentials — see {@code PayTRTestProviderConfig}; hashes here are computed with plain
 * {@code javax.crypto} against the documented formula, so the test cannot share a code path with
 * the verification it is testing (the {@code AbstractBillingIT} signing strategy).
 *
 * <p><b>Why the response BODY is load-bearing, uniquely for this provider.</b> PayTR settles the
 * money only when the notification response is the literal plain-text body {@code OK}. A JSON
 * wrapper, a ProblemDetail, different casing, a trailing newline — each is read by PayTR as a
 * FAILED delivery: the buyer has been charged and the merchant never receives the money. The
 * {@code isEqualTo("OK")} assertions in this class are therefore not cosmetics; they are the
 * settlement contract, and they double as the guard that no error-handler/advice wrapping can ever
 * reach a successful ack.
 *
 * <p><b>Mutation evidence carried by this class</b> (each run against the deliberately broken code,
 * red output recorded in the slice report):
 * <ul>
 *   <li>dedup insert clause dropped → {@link #duplicateNotificationAnswersOkAndProcessesOnce} red
 *       (duplicate dies on the unique index instead of resolving to 0 rows);</li>
 *   <li>hash verification skipped → {@link #invalidHashAnswers400AndStoresNothing} red (a forged
 *       notification is processed);</li>
 *   <li>ack wrapped/reworded ({@code "ok\n"}) → {@link #validSuccessNotificationSettlesWithLiteralOk}
 *       red on the exact-body assertion — documenting that settlement depends on the byte-exact
 *       body;</li>
 *   <li>PAID guard removed from the failure path → {@link #lateFailedAfterSuccessKeepsThePaymentPaid}
 *       red (a late "failed" un-pays a settled payment).</li>
 * </ul>
 */
@Import(PayTRTestProviderConfig.class)
@TestPropertySource(properties = {
        "zero.billing.paytr.enabled=true",
        "zero.billing.paytr.merchant-id=" + PayTRWebhookIT.TEST_MERCHANT_ID,
        "zero.billing.paytr.merchant-key=" + PayTRWebhookIT.TEST_MERCHANT_KEY,
        "zero.billing.paytr.merchant-salt=" + PayTRWebhookIT.TEST_MERCHANT_SALT,
        "zero.billing.paytr.test-mode=true"
})
class PayTRWebhookIT extends AbstractSaasIT {

    static final String TEST_MERCHANT_ID = "999001";
    static final String TEST_MERCHANT_KEY = "it_dummy_merchant_key_never_real";
    static final String TEST_MERCHANT_SALT = "it_dummy_merchant_salt_never_real";

    private static final String WEBHOOK_PATH = "/api/billing/webhook/paytr";

    @Autowired
    private JdbcTemplate jdbc;

    // ------------------------------------------------------------------ the settlement contract

    @Test
    @DisplayName("valid hash + status=success answers the LITERAL body \"OK\" and activates server-side")
    void validSuccessNotificationSettlesWithLiteralOk() {
        long editionId = createTryEdition("paytr-hook-a", "9.99");
        long tenantId = ensureTenant("paytr-hook-tenant-a");
        String merchantOid = startPayTRCheckout(tenantId, editionId);
        assertThat(paymentRow(merchantOid).get("status")).isEqualTo("NOT_PAID");

        ResponseEntity<String> response = postNotification(successBody(merchantOid, "999"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("PayTR settles the money ONLY on this exact body; any wrapper, casing or "
                        + "whitespace difference is an unsettled payment")
                .isEqualTo("OK");
        assertThat(response.getHeaders().getContentType())
                .as("the ack is plain text, not a negotiated JSON representation")
                .isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_PLAIN))
                .isTrue();

        Map<String, Object> payment = paymentRow(merchantOid);
        assertThat(payment.get("status")).isEqualTo("PAID");
        assertThat(payment.get("paid_at")).as("paid_at must be stamped via the Clock").isNotNull();

        JsonNode subscription = subscriptionOf(getSubscription(tenantId));
        assertThat(subscription.path("editionId").asLong())
                .as("the webhook itself must apply the target edition — no browser involved")
                .isEqualTo(editionId);
        assertThat(subscription.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(lastSubscriptionEventActor(tenantId))
                .as("the event trail must name THIS provider's webhook as the actor")
                .isEqualTo("paytr-webhook");

        Map<String, Object> event = webhookEventRow(merchantOid + ":success");
        assertThat(event.get("status")).isEqualTo("PROCESSED");
        assertThat(event.get("event_type")).isEqualTo("CHECKOUT_COMPLETED");
        assertThat(event.get("processed_at")).isNotNull();
    }

    @Test
    @DisplayName("the SAME notification delivered twice answers \"OK\" twice and processes exactly once")
    void duplicateNotificationAnswersOkAndProcessesOnce() {
        long editionId = createTryEdition("paytr-hook-b", "12.50");
        long tenantId = ensureTenant("paytr-hook-tenant-b");
        String merchantOid = startPayTRCheckout(tenantId, editionId);
        String body = successBody(merchantOid, "1250");

        assertThat(postNotification(body).getStatusCode()).isEqualTo(HttpStatus.OK);
        int eventsAfterFirst = subscriptionEventCount(tenantId);
        Object paidAtAfterFirst = paymentRow(merchantOid).get("paid_at");

        ResponseEntity<String> second = postNotification(body);
        assertThat(second.getStatusCode())
                .as("a duplicate must NEVER answer 4xx/5xx — PayTR reads only the FIRST "
                        + "notification as binding, and a non-OK answer on a redelivery is a "
                        + "failed delivery on its side (got %s: %s)",
                        second.getStatusCode(), second.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(second.getBody())
                .as("dedup hit must STILL answer the literal OK — the settlement contract does "
                        + "not distinguish first from repeated delivery")
                .isEqualTo("OK");

        assertThat(webhookEventCountFor(merchantOid + ":success"))
                .as("the dedup key (provider, merchant_oid:status) admits exactly one row")
                .isEqualTo(1);
        assertThat(subscriptionEventCount(tenantId))
                .as("no second activation: the subscription event trail must not have grown")
                .isEqualTo(eventsAfterFirst);
        assertThat(paymentRow(merchantOid).get("paid_at"))
                .as("the payment must not have been re-stamped")
                .isEqualTo(paidAtAfterFirst);
    }

    @Test
    @DisplayName("an invalid hash answers 400 ProblemDetail — deliberately NOT \"OK\" — and stores nothing")
    void invalidHashAnswers400AndStoresNothing() {
        long editionId = createTryEdition("paytr-hook-c", "15.00");
        long tenantId = ensureTenant("paytr-hook-tenant-c");
        String merchantOid = startPayTRCheckout(tenantId, editionId);
        int rowsBefore = webhookEventCount();

        String forged = "merchant_oid=" + merchantOid + "&status=success&total_amount=1500"
                + "&hash=" + Base64.getEncoder().encodeToString("not-the-hmac".getBytes(StandardCharsets.UTF_8));
        ResponseEntity<String> response = postNotification(forged);

        assertThat(response.getStatusCode())
                .as("confirming an unverified notification would be confirming money nobody "
                        + "proved was paid — the PayTR doc calls that out as financial loss")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).contains("VALIDATION");
        assertThat(response.getBody())
                .as("the rejection must not echo the submitted notification back to the caller")
                .doesNotContain(merchantOid);

        assertThat(webhookEventCount())
                .as("an unauthenticated payload must not occupy a dedup slot")
                .isEqualTo(rowsBefore);
        assertThat(paymentRow(merchantOid).get("status")).isEqualTo("NOT_PAID");
    }

    // ------------------------------------------------------------------ the failure transition

    @Test
    @DisplayName("valid hash + status=failed answers \"OK\", marks the payment FAILED, activates nothing")
    void failedNotificationMarksFailedWithoutActivation() {
        long editionId = createTryEdition("paytr-hook-d", "20.00");
        long tenantId = ensureTenant("paytr-hook-tenant-d");
        String merchantOid = startPayTRCheckout(tenantId, editionId);
        int eventsBefore = subscriptionEventCount(tenantId);

        ResponseEntity<String> response = postNotification(failedBody(merchantOid, "2000"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("a failure report is a SUCCESSFULLY RECEIVED notification — it must be "
                        + "acknowledged with the same literal OK, or PayTR keeps redelivering it")
                .isEqualTo("OK");

        Map<String, Object> payment = paymentRow(merchantOid);
        assertThat(payment.get("status")).isEqualTo("FAILED");
        assertThat(payment.get("paid_at")).isNull();

        assertThat(subscriptionEventCount(tenantId))
                .as("a failed charge must not touch the subscription")
                .isEqualTo(eventsBefore);

        Map<String, Object> event = webhookEventRow(merchantOid + ":failed");
        assertThat(event.get("event_type")).isEqualTo("PAYMENT_FAILED");
        assertThat(event.get("status")).isEqualTo("PROCESSED");
    }

    @Test
    @DisplayName("a late 'failed' AFTER a settled success answers \"OK\" and the payment STAYS PAID")
    void lateFailedAfterSuccessKeepsThePaymentPaid() {
        long editionId = createTryEdition("paytr-hook-e", "25.00");
        long tenantId = ensureTenant("paytr-hook-tenant-e");
        String merchantOid = startPayTRCheckout(tenantId, editionId);

        assertThat(postNotification(successBody(merchantOid, "2500")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        int eventsAfterSuccess = subscriptionEventCount(tenantId);

        // Contradictory second status for the same oid: its event id (oid:failed) is NEW, so dedup
        // admits it by design — the payment-status guard is what must hold the line here.
        ResponseEntity<String> lateFailed = postNotification(failedBody(merchantOid, "2500"));
        assertThat(lateFailed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lateFailed.getBody()).isEqualTo("OK");

        Map<String, Object> payment = paymentRow(merchantOid);
        assertThat(payment.get("status"))
                .as("money that settled must stay settled — a late failure report cannot undo "
                        + "an activation the money already bought")
                .isEqualTo("PAID");
        assertThat(subscriptionOf(getSubscription(tenantId)).path("status").asText())
                .isEqualTo("ACTIVE");
        assertThat(subscriptionEventCount(tenantId)).isEqualTo(eventsAfterSuccess);
        assertThat(webhookEventRow(merchantOid + ":failed").get("status"))
                .as("the contradictory notification is stored as evidence, acted on as nothing")
                .isEqualTo("PROCESSED");
    }

    /**
     * failed → success for the SAME {@code merchant_oid} is a LEGITIMATE sequence, not an anomaly:
     * the PayTR iframe lets the buyer retry the card inside the session ({@code timeout_limit}),
     * so a first attempt's {@code failed} notification can be followed by a hash-valid
     * {@code success} for the same oid. The success hash PROVES money moved; refusing to activate
     * on it (the old {@code status != NOT_PAID} refusal) left money settled with no activation —
     * and the FAILED row shape fell through the runbook's NOT_PAID-only reconciliation query too.
     * Mutation proof: with the FAILED→PAID transition reverted to the refusal, this test goes red
     * ("FAILED" where "PAID" is expected, ACTIVE never reached; output recorded in the report).
     */
    @Test
    @DisplayName("failed then hash-valid success for the same oid answers \"OK\" and ACTIVATES")
    void failedThenSuccessActivates() {
        long editionId = createTryEdition("paytr-hook-h", "40.00");
        long tenantId = ensureTenant("paytr-hook-tenant-h");
        String merchantOid = startPayTRCheckout(tenantId, editionId);

        assertThat(postNotification(failedBody(merchantOid, "4000")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(paymentRow(merchantOid).get("status")).isEqualTo("FAILED");

        ResponseEntity<String> success = postNotification(successBody(merchantOid, "4000"));
        assertThat(success.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(success.getBody()).isEqualTo("OK");

        Map<String, Object> payment = paymentRow(merchantOid);
        assertThat(payment.get("status"))
                .as("the success hash proves the retry attempt COLLECTED; a webhook-written "
                        + "FAILED must be reversible by the webhook that wrote it")
                .isEqualTo("PAID");
        assertThat(payment.get("paid_at")).isNotNull();

        JsonNode subscription = subscriptionOf(getSubscription(tenantId));
        assertThat(subscription.path("editionId").asLong())
                .as("settled money must buy the edition — this is the row shape that used to "
                        + "fall through every net (no activation, no reconciliation hit)")
                .isEqualTo(editionId);
        assertThat(subscription.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(webhookEventRow(merchantOid + ":success").get("status")).isEqualTo("PROCESSED");
    }

    /**
     * DETERMINISTIC pin for the base64-{@code +} survival across all three decode/encode hops:
     * test URL-encodes the hash ({@code +} → {@code %2B}) → {@code CachedBodyHttpServletRequest}
     * decodes → Spring's {@code getBodyFromServletRequestParameters} RE-encodes → the provider's
     * {@code parseFormBody} decodes again. A naive hop treating {@code +} as a space corrupts the
     * hash and fails verification — and only ~50% of HMAC outputs contain {@code +}, so the other
     * tests exercise this class probabilistically. The oid below was SEARCHED OFFLINE (independent
     * Python HMAC over this class's dummy key/salt, iterating {@code ZPPLUSVEC%02d} suffixes until
     * the base64 contained {@code +}): {@code ZPPLUSVEC00} → hash
     * {@code +m26aNOVWWUUN2eaxclKcYytdePN217CuL5a7EUall0=}. The containsPlus assertion keeps the
     * vector honest — if key/salt/inputs ever drift, the test refuses to certify vacuously.
     */
    @Test
    @DisplayName("a hash containing base64 '+' survives the container round-trip end to end")
    void plusBearingHashSurvivesTheContainerRoundTrip() {
        String plusOid = "ZPPLUSVEC00";
        assertThat(notificationHash(plusOid, "success", "999"))
                .as("the searched vector must still produce a '+'-bearing hash, or this test "
                        + "has silently stopped measuring the hop it exists for")
                .isEqualTo("+m26aNOVWWUUN2eaxclKcYytdePN217CuL5a7EUall0=");

        long editionId = createTryEdition("paytr-hook-i", "9.99");
        long tenantId = ensureTenant("paytr-hook-tenant-i");
        String generatedOid = startPayTRCheckout(tenantId, editionId);
        // Re-key the payment to the searched oid (same move as BillingWebhookIT's re-key): the
        // notification must name the EXACT oid whose hash carries the '+'.
        jdbc.update("update payments set external_session_id = ? where external_session_id = ?",
                plusOid, generatedOid);

        ResponseEntity<String> response = postNotification(successBody(plusOid, "999"));

        assertThat(response.getStatusCode())
                .as("got %s: %s — a non-200 here means one of the three hops reshaped the '+'",
                        response.getStatusCode(), response.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("OK");
        assertThat(paymentRow(plusOid).get("status")).isEqualTo("PAID");
    }

    // ------------------------------------------------------------------ provider selection

    @Test
    @DisplayName("checkout names an unknown provider -> 400 naming the enabled ids, no payment row")
    void unknownCheckoutProviderIsRejected() {
        long editionId = createTryEdition("paytr-hook-f", "30.00");
        long tenantId = ensureTenant("paytr-hook-tenant-f");
        Integer paymentsBefore = jdbc.queryForObject(
                "select count(*) from payments where target_edition_id = ?", Integer.class, editionId);

        ResponseEntity<JsonNode> response = postCheckout(tenantId, editionId, "iyzico");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("VALIDATION");
        assertThat(response.getBody().path("detail").asText())
                .as("the 400 names the ENABLED ids (configuration facts), never the submitted value")
                .contains("paytr")
                .doesNotContain("iyzico");
        assertThat(jdbc.queryForObject(
                "select count(*) from payments where target_edition_id = ?", Integer.class, editionId))
                .isEqualTo(paymentsBefore);
    }

    @Test
    @DisplayName("checkout may omit the provider when exactly one is enabled — it resolves to paytr")
    void omittedProviderDefaultsToTheSingleEnabledOne() {
        long editionId = createTryEdition("paytr-hook-g", "35.00");
        long tenantId = ensureTenant("paytr-hook-tenant-g");

        ResponseEntity<JsonNode> response = postCheckout(tenantId, editionId, null);

        assertThat(response.getStatusCode())
                .as("got %s: %s", response.getStatusCode(), response.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        String merchantOid = response.getBody().path("sessionId").asText();
        assertThat(merchantOid)
                .as("the session id is a PayTR merchant_oid: alphanumeric only, bounded")
                .matches("^[a-zA-Z0-9]{1,64}$");
        assertThat(response.getBody().path("url").asText())
                .startsWith("https://www.paytr.com/odeme/guvenli/");
        assertThat(paymentRow(merchantOid).get("status")).isEqualTo("NOT_PAID");
    }

    // ------------------------------------------------------------------ plumbing

    /** TRY on purpose: the PayTR adapter's currency contract (kuruş at the edge — ADR-0017). */
    private long createTryEdition(String prefix, String monthlyPrice) {
        return createEdition(editionBody(uniqueEditionName(prefix), monthlyPrice, null, "TRY", 0, 7));
    }

    private ResponseEntity<JsonNode> postCheckout(long tenantId, long editionId, String provider) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", tenantId);
        body.put("editionId", editionId);
        body.put("billingPeriod", "MONTHLY");
        body.put("successUrl", "https://app.example.com/billing/success");
        body.put("cancelUrl", "https://app.example.com/billing/cancel");
        if (provider != null) {
            body.put("provider", provider);
        }
        return restTemplate.exchange("/api/billing/checkout", HttpMethod.POST,
                new HttpEntity<>(body, host()), JsonNode.class);
    }

    /** Starts a PayTR checkout as the host admin and returns the {@code merchant_oid}. */
    private String startPayTRCheckout(long tenantId, long editionId) {
        ResponseEntity<JsonNode> response = postCheckout(tenantId, editionId, "paytr");
        assertThat(response.getStatusCode())
                .as("checkout must start, got %s: %s", response.getStatusCode(), response.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody().path("sessionId").asText();
    }

    /** Form-encoded, exactly as PayTR posts it — no JSON anywhere near this endpoint. */
    private ResponseEntity<String> postNotification(String formBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        return restTemplate.exchange(WEBHOOK_PATH, HttpMethod.POST,
                new HttpEntity<>(formBody, headers), String.class);
    }

    private String successBody(String merchantOid, String totalAmountKurus) {
        return notificationBody(merchantOid, "success", totalAmountKurus, "");
    }

    private String failedBody(String merchantOid, String totalAmountKurus) {
        return notificationBody(merchantOid, "failed", totalAmountKurus,
                "&failed_reason_code=6&failed_reason_msg=INSUFFICIENT_FUNDS");
    }

    private String notificationBody(String merchantOid, String status, String totalAmountKurus,
                                    String extraFields) {
        return "merchant_oid=" + merchantOid + "&status=" + status
                + "&total_amount=" + totalAmountKurus
                + "&hash=" + urlEncode(notificationHash(merchantOid, status, totalAmountKurus))
                + extraFields;
    }

    /**
     * The documented formula, computed with plain {@code javax.crypto} — independent of the
     * production implementation on purpose: {@code base64(HMAC-SHA256(merchant_oid + merchant_salt
     * + status + total_amount, key = merchant_key))}.
     */
    private static String notificationHash(String merchantOid, String status, String totalAmount) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(TEST_MERCHANT_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal((merchantOid + TEST_MERCHANT_SALT + status + totalAmount)
                    .getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(raw);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Cannot compute the test notification hash", ex);
        }
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // --- database state (asserted directly: DB truth, not response truth) ---

    private Map<String, Object> paymentRow(String merchantOid) {
        return jdbc.queryForMap("select * from payments where external_session_id = ?", merchantOid);
    }

    private int webhookEventCount() {
        Integer count = jdbc.queryForObject("select count(*) from webhook_events", Integer.class);
        return count == null ? 0 : count;
    }

    private int webhookEventCountFor(String eventId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from webhook_events where provider = 'paytr' and event_id = ?",
                Integer.class, eventId);
        return count == null ? 0 : count;
    }

    private Map<String, Object> webhookEventRow(String eventId) {
        return jdbc.queryForMap(
                "select * from webhook_events where provider = 'paytr' and event_id = ?", eventId);
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
