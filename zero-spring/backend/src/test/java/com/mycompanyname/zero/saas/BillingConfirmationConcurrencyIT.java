package com.mycompanyname.zero.saas;

import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.saas.billing.BillingConfirmationService;
import com.mycompanyname.zero.saas.billing.BillingProvider;
import com.mycompanyname.zero.saas.billing.BillingProviderRegistry;
import com.mycompanyname.zero.saas.billing.IyzicoBillingProviderTestHook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stale-first-level-cache race in {@code BillingConfirmationService} (stack-review Finding 1,
 * HIGH), measured with two REAL concurrent transactions.
 *
 * <p><b>The defect this pins.</b> The service peeks at the payment before the provider query. When
 * that peek loads the {@code Payment} ENTITY, it enters the transaction's persistence context —
 * and Hibernate resolves query results by identity: the later {@code PESSIMISTIC_WRITE} lookup
 * executes its {@code select ... for update} (taking the SQL row lock correctly) but hands back
 * the ALREADY-LOADED instance with its STALE state. The losing transaction therefore blocks on the
 * row lock, wins it after the winner commits, and then reads {@code NOT_PAID} out of its own cache
 * — the PAID guard passes, and the payment is activated a SECOND time (duplicate
 * {@code to_status=ACTIVE} trail, ACTIVE→PENDING_PAYMENT→ACTIVE flap). The fix makes the peek a
 * PROJECTION so no entity enters the persistence context before the locked read.
 *
 * <p><b>How the window is held open deterministically.</b> The race window is "both transactions
 * past the peek, neither at the locked read yet" — exactly where the provider query sits, so the
 * test parks a two-party {@code CyclicBarrier} inside the stubbed retrieve
 * ({@code IyzicoTestProviderConfig.RETRIEVE_INTERCEPTOR}): neither thread can proceed to the
 * locked read until BOTH have peeked. No sleep, no luck.
 *
 * <p><b>Negative evidence (recorded in the slice report):</b> against the entity-peek code this
 * test is RED — both threads return {@code CONFIRMED_ACTIVATED} and two {@code to_status=ACTIVE}
 * rows carry the test actor; with the projection peek it is green: one winner, one
 * {@code ALREADY_PAID} loser, one activation row.
 */
@Import(IyzicoTestProviderConfig.class)
@TestPropertySource(properties = {
        "zero.billing.iyzico.enabled=true",
        "zero.billing.iyzico.api-key=" + IyzicoWebhookIT.TEST_API_KEY,
        "zero.billing.iyzico.secret-key=" + IyzicoWebhookIT.TEST_SECRET_KEY,
        "zero.billing.iyzico.base-url=https://sandbox-api.iyzipay.com"
})
class BillingConfirmationConcurrencyIT extends AbstractSaasIT {

    private static final String ACTOR = "iyzico-conc-test";

    @Autowired
    private BillingConfirmationService confirmationService;

    @Autowired
    private BillingProviderRegistry providerRegistry;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("two simultaneous confirmations of ONE session activate exactly once")
    void twoSimultaneousConfirmationsActivateExactlyOnce() throws Exception {
        long editionId = createEdition(editionBody(uniqueEditionName("conc-a"), "9.99", null,
                "TRY", 0, 7));
        long tenantId = ensureTenant("conc-tenant-a");
        String token = startIyzicoCheckout(tenantId, editionId);
        IyzicoTestProviderConfig.RETRIEVE_RESULTS.put(token,
                IyzicoBillingProviderTestHook.mapRetrieve("success", "SUCCESS", 1, "ipay-conc", null));
        BillingProvider provider = providerRegistry.find("iyzico").orElseThrow();

        // Both transactions must be PAST the peek and INSIDE the provider query before either may
        // proceed to the locked read — the exact stale-cache window, held open deterministically.
        CyclicBarrier bothInsideTheQuery = new CyclicBarrier(2);
        IyzicoTestProviderConfig.RETRIEVE_INTERCEPTOR.set(() -> {
            try {
                bothInsideTheQuery.await(15, TimeUnit.SECONDS);
            } catch (Exception ex) {
                throw new IllegalStateException("The two confirmations never met in the window — "
                        + "the race this test exists to measure was not staged", ex);
            }
        });

        List<BillingConfirmationService.Outcome> outcomes;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<BillingConfirmationService.Outcome>> futures = pool.invokeAll(List.of(
                    () -> confirmationService.confirmBySessionQuery(provider, token, ACTOR),
                    () -> confirmationService.confirmBySessionQuery(provider, token, ACTOR)));
            outcomes = List.of(futures.get(0).get(30, TimeUnit.SECONDS),
                    futures.get(1).get(30, TimeUnit.SECONDS));
        } finally {
            IyzicoTestProviderConfig.RETRIEVE_INTERCEPTOR.set(null);
            pool.shutdownNow();
        }

        assertThat(outcomes)
                .as("exactly ONE transaction may win the activation; the loser must re-read the "
                        + "winner's committed PAID under the row lock — both reporting "
                        + "CONFIRMED_ACTIVATED is the stale-first-level-cache defect")
                .containsExactlyInAnyOrder(
                        BillingConfirmationService.Outcome.CONFIRMED_ACTIVATED,
                        BillingConfirmationService.Outcome.ALREADY_PAID);

        assertThat(jdbc.queryForMap("select * from payments where external_session_id = ?", token)
                .get("status")).isEqualTo("PAID");
        assertThat(subscriptionOf(getSubscription(tenantId)).path("status").asText())
                .isEqualTo("ACTIVE");

        Integer activations = jdbc.queryForObject(
                "select count(*) from subscription_events e "
                        + "join subscriptions s on s.id = e.subscription_id "
                        + "where s.tenant_id = ? and e.actor = ? and e.to_status = 'ACTIVE'",
                Integer.class, tenantId, ACTOR);
        assertThat(activations)
                .as("one payment buys ONE activation: a second to_status=ACTIVE row under this "
                        + "actor is the double-activation (with its PENDING_PAYMENT flap) on the trail")
                .isEqualTo(1);
    }

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
}
