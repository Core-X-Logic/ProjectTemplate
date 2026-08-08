package com.mycompanyname.zero.saas;

import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.saas.billing.credentials.BillingCheckoutCircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checkout failover, the cool-down and webhook routing after a failover (ADR-0020), against TWO
 * fake providers under the real ids — enabled through the REAL admin API, so the candidate order
 * and enablement rules exercised are production code; only the outbound calls are canned
 * ({@link FailoverBillingProvidersConfig}).
 *
 * <p>Runs in its own context ({@code @Import} forks it): the fakes replace the real paytr/iyzico
 * beans per id, and {@link MutableClockConfig} substitutes the {@code Clock} the breaker reads, so
 * a cool-down expires by ADVANCING TIME, not by sleeping. The database is shared with every other
 * context, so each test cleans its credential rows and resets clock, fakes and breaker.
 */
@Import({FailoverBillingProvidersConfig.class, MutableClockConfig.class})
class BillingCheckoutFailoverIT extends AbstractSaasIT {

    private static final String PROVIDERS_PATH = "/api/billing/providers";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    @Autowired
    private BillingCheckoutCircuitBreaker circuitBreaker;

    @BeforeEach
    void enableBothProvidersThroughTheRealAdminApi() {
        FailoverBillingProvidersConfig.reset();
        clock.reset();
        circuitBreaker.recordSuccess("paytr");
        circuitBreaker.recordSuccess("iyzico");
        putCredentials("paytr", Map.of(
                "merchantId", "123456", "merchantKey", "fk", "merchantSalt", "fs"));
        putCredentials("iyzico", Map.of("apiKey", "fake-api-key", "secretKey", "fake-secret"));
        putOrder(List.of("paytr", "iyzico"));
    }

    @AfterEach
    void cleanUp() {
        FailoverBillingProvidersConfig.reset();
        clock.reset();
        circuitBreaker.recordSuccess("paytr");
        circuitBreaker.recordSuccess("iyzico");
        jdbc.update("delete from billing_provider_credentials");
    }

    @Test
    @DisplayName("a transport failure on the first provider fails over: the session and the payment row carry the SECOND provider")
    void transportFailureFailsOverToTheNextProvider() {
        FailoverBillingProvidersConfig.PAYTR_FAILURE.set(
                new ResourceAccessException("connect timed out (simulated)"));
        long editionId = createPaidEdition("failover-ok", "10.00", null, 0, 7);
        long tenantId = ensureTenant("failover-tenant");

        JsonNode body = startCheckoutOk(tenantId, editionId);

        assertThat(body.path("sessionId").asText())
                .as("the session must come from the SECOND provider")
                .startsWith(FailoverBillingProvidersConfig.IYZICO_SESSION_PREFIX);
        assertThat(FailoverBillingProvidersConfig.PAYTR_CALLS.get())
                .as("the first provider was tried first (stored order)").isEqualTo(1);
        assertThat(FailoverBillingProvidersConfig.IYZICO_CALLS.get()).isEqualTo(1);

        Map<String, Object> payment = paymentRowBySessionHere(body.path("sessionId").asText());
        assertThat(payment.get("provider"))
                .as("payments.provider must name the provider that ACTUALLY issued the session — "
                        + "the reconciliation job and the webhook route by it")
                .isEqualTo("iyzico");
        assertThat(payment.get("status")).isEqualTo("NOT_PAID");
    }

    @Test
    @DisplayName("a 4xx does NOT fail over: the provider answered and refused — no second attempt, no payment row")
    void clientErrorDoesNotFailOver() {
        FailoverBillingProvidersConfig.PAYTR_FAILURE.set(
                HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
                        HttpHeaders.EMPTY, new byte[0], null));
        long editionId = createPaidEdition("failover-4xx", "11.00", null, 0, 7);
        long tenantId = ensureTenant("failover-tenant-4xx");

        ResponseEntity<JsonNode> response = postCheckout(tenantId, editionId, host());

        assertThat(response.getStatusCode().is5xxServerError())
                .as("a refused initiation is this installation's problem: loud 500, got %s: %s",
                        response.getStatusCode(), response.getBody())
                .isTrue();
        assertThat(FailoverBillingProvidersConfig.IYZICO_CALLS.get())
                .as("4xx must not reach the second provider").isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from payments where target_edition_id = ?", Integer.class, editionId))
                .as("the rollback must leave no orphan payment row").isZero();
    }

    @Test
    @DisplayName("two consecutive transport failures open the cool-down: the provider is skipped, and comes back when time passes")
    void coolDownSkipsAndRecovers() {
        FailoverBillingProvidersConfig.PAYTR_FAILURE.set(
                new ResourceAccessException("connect timed out (simulated)"));
        long editionId = createPaidEdition("failover-cd", "12.00", null, 0, 7);
        long tenantId = ensureTenant("failover-tenant-cd");

        startCheckoutOk(tenantId, editionId); // paytr failure #1, settled by iyzico
        startCheckoutOk(tenantId, editionId); // paytr failure #2 -> circuit opens
        assertThat(FailoverBillingProvidersConfig.PAYTR_CALLS.get()).isEqualTo(2);

        startCheckoutOk(tenantId, editionId); // paytr cooling down -> not even tried
        assertThat(FailoverBillingProvidersConfig.PAYTR_CALLS.get())
                .as("an open circuit skips the provider instead of spending a timeout on it")
                .isEqualTo(2);

        clock.advance(Duration.ofSeconds(61));
        startCheckoutOk(tenantId, editionId); // cool-down over -> tried again
        assertThat(FailoverBillingProvidersConfig.PAYTR_CALLS.get())
                .as("after the cool-down the provider re-enters the rotation")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("after a failover the payment finishes through the provider that issued the session — its webhook, its row")
    void webhookStaysBoundToTheIssuingProvider() {
        FailoverBillingProvidersConfig.PAYTR_FAILURE.set(
                new ResourceAccessException("connect timed out (simulated)"));
        long editionId = createPaidEdition("failover-wh", "13.00", null, 0, 7);
        long tenantId = ensureTenant("failover-tenant-wh");
        String sessionId = startCheckoutOk(tenantId, editionId).path("sessionId").asText();
        assertThat(sessionId).startsWith(FailoverBillingProvidersConfig.IYZICO_SESSION_PREFIX);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-IYZ-SIGNATURE-V3", FailoverBillingProvidersConfig.FAKE_PROOF_HEADER_VALUE);
        ResponseEntity<String> webhook = restTemplate.exchange("/api/billing/webhook/iyzico",
                HttpMethod.POST,
                new HttpEntity<>("{\"session\":\"" + sessionId + "\"}", headers), String.class);
        assertThat(webhook.getStatusCode())
                .as("the issuing provider's webhook settles the payment it started, got %s: %s",
                        webhook.getStatusCode(), webhook.getBody())
                .isEqualTo(HttpStatus.OK);

        Map<String, Object> payment = paymentRowBySessionHere(sessionId);
        assertThat(payment.get("status")).isEqualTo("PAID");
        assertThat(payment.get("provider")).isEqualTo("iyzico");
        assertThat(jdbc.queryForObject(
                "select provider from webhook_events where event_id = ?", String.class,
                "fake-evt-" + sessionId))
                .as("the dedup ledger records the event under the SOURCE provider")
                .isEqualTo("iyzico");
    }

    // --- plumbing ---

    /** {@code AbstractBillingIT}'s checkout helpers, local: this context is not the Stripe one. */
    private ResponseEntity<JsonNode> postCheckout(long tenantId, long editionId, HttpHeaders headers) {
        Map<String, Object> body = Map.of(
                "tenantId", tenantId,
                "editionId", editionId,
                "billingPeriod", "MONTHLY",
                "successUrl", "https://app.example.com/billing/success",
                "cancelUrl", "https://app.example.com/billing/cancel");
        return restTemplate.exchange("/api/billing/checkout", HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);
    }

    private JsonNode startCheckoutOk(long tenantId, long editionId) {
        ResponseEntity<JsonNode> response = postCheckout(tenantId, editionId, host());
        assertThat(response.getStatusCode())
                .as("checkout must start, got %s: %s", response.getStatusCode(), response.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private void putCredentials(String provider, Map<String, String> credentials) {
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                PROVIDERS_PATH + "/" + provider + "/credentials", HttpMethod.PUT,
                new HttpEntity<>(Map.of("enabled", true, "credentials", credentials), host()),
                JsonNode.class);
        assertThat(response.getStatusCode())
                .as("fixture credential write must succeed, got %s: %s",
                        response.getStatusCode(), response.getBody())
                .isEqualTo(HttpStatus.OK);
    }

    private void putOrder(List<String> order) {
        ResponseEntity<JsonNode> response = restTemplate.exchange(PROVIDERS_PATH + "/order",
                HttpMethod.PUT, new HttpEntity<>(Map.of("order", order), host()), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** Local copy of {@code AbstractBillingIT#paymentRowBySession} — this IT is not a Stripe IT. */
    private Map<String, Object> paymentRowBySessionHere(String sessionId) {
        return jdbc.queryForMap("select * from payments where external_session_id = ?", sessionId);
    }
}
