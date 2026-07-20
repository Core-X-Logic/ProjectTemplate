package com.mycompanyname.zero.saas;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
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
 * Fixtures for the billing slice. Runs in its OWN Spring context (billing enabled with dummy
 * secrets + the recording provider), separate from the default-context suite where
 * {@code zero.billing.stripe.enabled} stays {@code false} — which is itself load-bearing: the rest
 * of the suite proves the application is whole with billing off.
 *
 * <p>Signatures are computed here with plain {@code javax.crypto} against Stripe's documented
 * scheme ({@code HMAC-SHA256(secret, "<timestamp>.<payload>")}, header {@code t=...,v1=...}) rather
 * than with Stripe test utilities, so the test cannot accidentally share a code path with the
 * verification it is testing.
 */
@TestPropertySource(properties = {
        "zero.billing.stripe.enabled=true",
        "zero.billing.stripe.secret-key=" + AbstractBillingIT.TEST_SECRET_KEY,
        "zero.billing.stripe.webhook-secret=" + AbstractBillingIT.TEST_WEBHOOK_SECRET,
        "zero.billing.stripe.publishable-key=pk_test_dummy"
})
abstract class AbstractBillingIT extends AbstractSaasIT {

    static final String TEST_SECRET_KEY = "sk_test_dummy_never_a_real_key";
    static final String TEST_WEBHOOK_SECRET = "whsec_test_dummy_signing_secret_for_it";

    protected static final String WEBHOOK_PATH = "/api/billing/webhook/stripe";
    protected static final String STRIPE_SIGNATURE_HEADER = "Stripe-Signature";

    @Autowired
    protected JdbcTemplate jdbc;

    // --- webhook plumbing ---

    protected String uniqueEventId() {
        return "evt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** A currently-valid Stripe signature header for {@code payload}, per the documented scheme. */
    protected String signatureFor(String payload) {
        long timestamp = System.currentTimeMillis() / 1000L;
        return "t=" + timestamp + ",v1=" + hmacSha256Hex(TEST_WEBHOOK_SECRET, timestamp + "." + payload);
    }

    protected ResponseEntity<JsonNode> postWebhook(String payload, String signatureHeader) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (signatureHeader != null) {
            headers.set(STRIPE_SIGNATURE_HEADER, signatureHeader);
        }
        return restTemplate.exchange(WEBHOOK_PATH, HttpMethod.POST,
                new HttpEntity<>(payload, headers), JsonNode.class);
    }

    protected String checkoutCompletedPayload(String eventId, String sessionId, String paymentIntent) {
        return ("{\"id\":\"%s\",\"object\":\"event\",\"type\":\"checkout.session.completed\","
                + "\"data\":{\"object\":{\"id\":\"%s\",\"object\":\"checkout.session\","
                + "\"payment_intent\":\"%s\"}}}").formatted(eventId, sessionId, paymentIntent);
    }

    protected String unknownEventPayload(String eventId) {
        return ("{\"id\":\"%s\",\"object\":\"event\",\"type\":\"customer.created\","
                + "\"data\":{\"object\":{\"id\":\"cus_123\",\"object\":\"customer\"}}}")
                .formatted(eventId);
    }

    protected String invoicePaidSubscriptionCyclePayload(String eventId) {
        return ("{\"id\":\"%s\",\"object\":\"event\",\"type\":\"invoice.paid\","
                + "\"data\":{\"object\":{\"id\":\"in_123\",\"object\":\"invoice\","
                + "\"billing_reason\":\"subscription_cycle\",\"payment_intent\":\"pi_cycle_1\"}}}")
                .formatted(eventId);
    }

    // --- checkout plumbing ---

    protected ResponseEntity<JsonNode> postCheckout(long tenantId, long editionId, HttpHeaders headers) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", tenantId);
        body.put("editionId", editionId);
        body.put("billingPeriod", "MONTHLY");
        body.put("successUrl", "https://app.example.com/billing/success");
        body.put("cancelUrl", "https://app.example.com/billing/cancel");
        return restTemplate.exchange("/api/billing/checkout", HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);
    }

    /** Starts a checkout as the host admin and returns the response body (paymentId/sessionId/url). */
    protected JsonNode startCheckoutOk(long tenantId, long editionId) {
        ResponseEntity<JsonNode> response = postCheckout(tenantId, editionId, host());
        assertThat(response.getStatusCode())
                .as("checkout must start, got %s: %s", response.getStatusCode(), response.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    // --- database state (asserted directly: DB truth, not response truth) ---

    protected int webhookEventCount() {
        Integer count = jdbc.queryForObject("select count(*) from webhook_events", Integer.class);
        return count == null ? 0 : count;
    }

    protected int webhookEventCountFor(String eventId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from webhook_events where provider = 'stripe' and event_id = ?",
                Integer.class, eventId);
        return count == null ? 0 : count;
    }

    protected Map<String, Object> webhookEventRow(String eventId) {
        return jdbc.queryForMap(
                "select * from webhook_events where provider = 'stripe' and event_id = ?", eventId);
    }

    protected Map<String, Object> paymentRowBySession(String sessionId) {
        return jdbc.queryForMap("select * from payments where external_session_id = ?", sessionId);
    }

    protected int subscriptionEventCount(long tenantId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from subscription_events e "
                        + "join subscriptions s on s.id = e.subscription_id where s.tenant_id = ?",
                Integer.class, tenantId);
        return count == null ? 0 : count;
    }

    protected String lastSubscriptionEventActor(long tenantId) {
        return jdbc.queryForObject(
                "select e.actor from subscription_events e "
                        + "join subscriptions s on s.id = e.subscription_id "
                        + "where s.tenant_id = ? order by e.id desc limit 1",
                String.class, tenantId);
    }

    private static String hmacSha256Hex(String secret, String signedPayload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Cannot compute the test webhook signature", ex);
        }
    }
}
