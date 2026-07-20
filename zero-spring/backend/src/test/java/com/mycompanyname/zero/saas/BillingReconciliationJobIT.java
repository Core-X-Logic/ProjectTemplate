package com.mycompanyname.zero.saas;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.saas.billing.BillingConfirmationService;
import com.mycompanyname.zero.saas.billing.BillingProvider;
import com.mycompanyname.zero.saas.billing.BillingProviderRegistry;
import com.mycompanyname.zero.saas.billing.BillingReconciliationJob;
import com.mycompanyname.zero.saas.billing.BillingReconciliationProperties;
import com.mycompanyname.zero.saas.billing.BillingReconciliationService;
import com.mycompanyname.zero.saas.billing.IyzicoBillingProviderTestHook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reconciliation job (P2'-B): the second defence line of ADR-0014, automated — a payment whose
 * success notification never arrived (throttled 413, retry budget exhausted, webhook mis-routed)
 * must not stay unsettled for ever on an installation whose provider CAN be asked directly. The
 * retrieve is faked at the same SPI seam the webhook funnel uses ({@code IyzicoTestProviderConfig}),
 * which is the contract's design requirement made testable: the job exercises literally the same
 * {@code BillingConfirmationService} call, so there is no job-only activation path to drift.
 *
 * <p><b>Vacuity guard, per the contract.</b> The happy-path test FAILS ITSELF when the scan matches
 * zero fixtures (see the message in
 * {@link #stuckIyzicoPaymentsAreConfirmedAndActivatedByThePass}) — the {@code ExportRowBoundIT}
 * rule: an assertion proven against an empty set documents nothing, and a scan query that silently
 * stopped matching (wrong status list, wrong threshold arithmetic, dropped provider attribution)
 * must go red HERE, not in production three weeks later.
 *
 * <p><b>Mutation evidence carried by this class</b> (run against deliberately broken code, red
 * output in the slice report): job disabled by default / scan's provider filter dropped →
 * {@link #stuckIyzicoPaymentsAreConfirmedAndActivatedByThePass} red (the first flips nothing to
 * PAID, the second turns the skipped-count assertion red because PayTR rows get queried instead of
 * skipped).
 *
 * <p>Fixture aging is done by rewinding {@code created_at} in the database rather than advancing a
 * mutable clock: this context runs the production {@code Clock} bean, and the scan threshold is
 * {@code clock.instant() - min-age}, so a two-hour-old row is simply a row whose timestamp says so.
 */
@Import(IyzicoTestProviderConfig.class)
@TestPropertySource(properties = {
        "zero.billing.iyzico.enabled=true",
        "zero.billing.iyzico.api-key=" + IyzicoWebhookIT.TEST_API_KEY,
        "zero.billing.iyzico.secret-key=" + IyzicoWebhookIT.TEST_SECRET_KEY,
        "zero.billing.iyzico.base-url=https://sandbox-api.iyzipay.com",
        // PayTR enabled FOR REAL (no double): the skip filter's `supportsQueryConfirmation` half
        // then runs against a genuine registered non-query provider, not merely against a null
        // registry miss — which is the production shape of a TR installation running both.
        "zero.billing.paytr.enabled=true",
        "zero.billing.paytr.merchant-id=999002",
        "zero.billing.paytr.merchant-key=it_dummy_recon_merchant_key",
        "zero.billing.paytr.merchant-salt=it_dummy_recon_merchant_salt",
        "zero.billing.paytr.test-mode=true"
})
class BillingReconciliationJobIT extends AbstractSaasIT {

    @Autowired
    private BillingReconciliationService reconciliationService;

    @Autowired
    private BillingReconciliationProperties reconciliationProperties;

    @Autowired
    private BillingReconciliationJob reconciliationJob;

    @Autowired
    private BillingConfirmationService confirmationService;

    @Autowired
    private BillingProviderRegistry providerRegistry;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("stuck NOT_PAID and FAILED iyzico payments with a confirming retrieve flip to PAID + activate")
    void stuckIyzicoPaymentsAreConfirmedAndActivatedByThePass() {
        long editionId = createTryEdition("recon-a", "9.99");
        long tenantNotPaid = ensureTenant("recon-tenant-notpaid");
        long tenantFailed = ensureTenant("recon-tenant-failed");

        // Fixture 1: NOT_PAID, aged past min-age, retrieve confirms — the classic lost webhook.
        String stuckNotPaid = startIyzicoCheckout(tenantNotPaid, editionId);
        age(stuckNotPaid);
        canCollected(stuckNotPaid, "ipay-recon-a");

        // Fixture 2: FAILED, aged, retrieve confirms — the buyer-retried-in-session shape that a
        // NOT_PAID-only scan missed (the runbook §3.9 lesson, now automated).
        String stuckFailed = startIyzicoCheckout(tenantFailed, editionId);
        jdbc.update("update payments set status = 'FAILED' where external_session_id = ?", stuckFailed);
        age(stuckFailed);
        canCollected(stuckFailed, "ipay-recon-b");

        // Fixture 3: an aged stuck row attributed to PayTR — NO query API exists for it, so the
        // pass must COUNT and SKIP it (runbook §3.9 stays its net), never query it.
        String stuckPaytr = startIyzicoCheckout(tenantNotPaid, editionId);
        jdbc.update("update payments set provider = 'paytr' where external_session_id = ?", stuckPaytr);
        age(stuckPaytr);

        // Fixture 4: a YOUNG NOT_PAID row (a buyer plausibly mid-checkout) — must stay untouched.
        String young = startIyzicoCheckout(tenantNotPaid, editionId);
        canCollected(young, "ipay-recon-young");

        int retrievesBefore = IyzicoTestProviderConfig.RETRIEVE_CALLS.get();
        BillingReconciliationService.ReconciliationRun run = reconciliationService.reconcile();

        assertThat(run.candidates())
                .withFailMessage("VACUITY GUARD: the reconciliation scan matched ZERO rows although "
                        + "this test just planted aged stuck fixtures. Every assertion below would "
                        + "then document nothing (the ExportRowBoundIT rule: a bound proven against "
                        + "an empty set proves no bound). The scan query — statuses, threshold "
                        + "arithmetic, or the fixture-aging step — is broken; fix THAT, do not relax "
                        + "this guard. Scan result: %s", run)
                .isGreaterThan(0);

        assertThat(paymentRow(stuckNotPaid).get("status"))
                .as("a stuck NOT_PAID payment whose retrieve confirms must be settled by the pass")
                .isEqualTo("PAID");
        assertThat(paymentRow(stuckNotPaid).get("external_payment_id"))
                .isEqualTo("ipay-recon-a");
        assertThat(subscriptionOf(getSubscription(tenantNotPaid)).path("status").asText())
                .isEqualTo("ACTIVE");
        assertThat(lastSubscriptionEventActor(tenantNotPaid))
                .as("the trail must name the reconciliation, not a webhook that never came")
                .isEqualTo("iyzico-reconciliation");

        assertThat(paymentRow(stuckFailed).get("status"))
                .as("FAILED is webhook-written and the provider's own query proves collection — "
                        + "the reversible-by-provider rule, held by the job too")
                .isEqualTo("PAID");
        assertThat(subscriptionOf(getSubscription(tenantFailed)).path("status").asText())
                .isEqualTo("ACTIVE");

        assertThat(paymentRow(stuckPaytr).get("status"))
                .as("no query API for PayTR is CAPTURED (PROD-R41): the row must be skipped, "
                        + "not guessed at — dropping the provider filter turns this red")
                .isEqualTo("NOT_PAID");
        assertThat(run.skippedWithoutQuerySupport())
                .as("the skip must be COUNTED and logged — it is the operator's measure of how "
                        + "much runbook §3.9 manual work remains")
                .isGreaterThanOrEqualTo(1);

        assertThat(paymentRow(young).get("status"))
                .as("a row younger than min-age is a buyer mid-checkout, not a stuck payment")
                .isEqualTo("NOT_PAID");

        assertThat(run.resolved()).isGreaterThanOrEqualTo(2);
        assertThat(IyzicoTestProviderConfig.RETRIEVE_CALLS.get() - retrievesBefore)
                .as("exactly the aged, iyzico-attributed rows may reach the provider — the young "
                        + "row and the PayTR row must not")
                .isEqualTo(run.resolved() + run.unresolved());
    }

    @Test
    @DisplayName("with the job disabled the same stuck payment stays stuck (the negative half)")
    void disabledReconciliationLeavesStuckPaymentsStuck() {
        long editionId = createTryEdition("recon-b", "12.00");
        long tenantId = ensureTenant("recon-tenant-disabled");
        String stuck = startIyzicoCheckout(tenantId, editionId);
        age(stuck);
        canCollected(stuck, "ipay-recon-disabled");
        int retrievesBefore = IyzicoTestProviderConfig.RETRIEVE_CALLS.get();

        reconciliationProperties.setEnabled(false);
        try {
            BillingReconciliationService.ReconciliationRun run = reconciliationService.reconcile();

            assertThat(run.ran()).isFalse();
            assertThat(paymentRow(stuck).get("status"))
                    .as("disabled means DISABLED: a pass that still settles rows would make the "
                            + "flag a lie in both directions")
                    .isEqualTo("NOT_PAID");
            assertThat(IyzicoTestProviderConfig.RETRIEVE_CALLS.get())
                    .as("no provider query either — the net is down and says so in the log")
                    .isEqualTo(retrievesBefore);
        } finally {
            reconciliationProperties.setEnabled(true);
        }
    }

    /**
     * Stack-review Finding 2a: the pass is CAPPED, the cap is LOUD, and the remainder is reachable.
     * The cap is lowered to 2 instead of the fixture raised to 51 — the property is the thing under
     * test, so it is the thing configured (the {@code ExportRowBoundIT} rule). Every fixture is
     * RESOLVABLE on purpose: with the scan admitting only query-capable providers, nothing can
     * permanently clog the window, so "the remainder waits for the next run" is a claim later
     * passes must be able to make TRUE — and the loop at the end measures exactly that.
     */
    @Test
    @DisplayName("a scan over the cap truncates LOUDLY (WARN + flag) and later passes drain the rest")
    void capExceededScanTruncatesLoudlyAndLaterPassesDrainTheRest() {
        long editionId = createTryEdition("recon-cap", "8.00");
        long tenantId = ensureTenant("recon-tenant-cap");
        String[] tokens = new String[3];
        for (int i = 0; i < tokens.length; i++) {
            tokens[i] = startIyzicoCheckout(tenantId, editionId);
            age(tokens[i]);
            canCollected(tokens[i], "ipay-cap-" + i);
        }

        Logger serviceLogger = (Logger) LoggerFactory.getLogger(BillingReconciliationService.class);
        ListAppender<ILoggingEvent> capturedLog = new ListAppender<>();
        capturedLog.start();
        serviceLogger.addAppender(capturedLog);
        int originalCap = reconciliationProperties.getMaxRowsPerPass();
        try {
            reconciliationProperties.setMaxRowsPerPass(2);
            BillingReconciliationService.ReconciliationRun first = reconciliationService.reconcile();

            assertThat(first.candidates())
                    .withFailMessage("VACUITY GUARD: the capped scan matched ZERO rows although "
                            + "three aged resolvable fixtures were just planted — the assertions "
                            + "below would document nothing. Scan result: %s", first)
                    .isGreaterThan(0);
            assertThat(first.candidates())
                    .as("the pass must process EXACTLY the cap when more rows wait — cap+1 is a "
                            + "probe, never a served row (the BoundedExport boundary rule)")
                    .isEqualTo(2);
            assertThat(first.truncated())
                    .as("truncation must be a REPORTED fact, not something the operator infers")
                    .isTrue();
            assertThat(capturedLog.list)
                    .withFailMessage("VACUITY GUARD: the log capture saw no events at all — the "
                            + "appender is not attached to the logger that speaks, and the WARN "
                            + "assertion below would pass vacuously inverted or fail unfairly")
                    .isNotEmpty();
            assertThat(capturedLog.list.stream().anyMatch(event ->
                    event.getLevel() == ch.qos.logback.classic.Level.WARN
                            && event.getFormattedMessage().contains("truncated at 2 row")))
                    .as("no silent caps (house rule): the truncation must be WARNed with the "
                            + "configured number in it")
                    .isTrue();

            long paidAfterFirst = paidCountAmong(tokens);
            assertThat(paidAfterFirst)
                    .as("a cap of 2 cannot have settled all three fixtures in one pass — if it "
                            + "did, the cap is decorative")
                    .isLessThan(3);

            for (int pass = 0; pass < 5 && paidCountAmong(tokens) < 3; pass++) {
                reconciliationService.reconcile();
            }
            assertThat(paidCountAmong(tokens))
                    .as("the truncated remainder must be REACHABLE: repeated passes drain it — a "
                            + "cap that starves rows forever would be a silent loss channel")
                    .isEqualTo(3);
        } finally {
            reconciliationProperties.setMaxRowsPerPass(originalCap);
            serviceLogger.detachAppender(capturedLog);
            capturedLog.stop();
        }
    }

    /**
     * Stack-review Finding 3: a payment attributed to ANOTHER provider must never be settled on
     * this provider's answer. The canned retrieve for the token is deliberately COLLECTED — with
     * the guard absent this test would activate, so its green is evidence the refusal happens
     * BEFORE the query, not that the query happened to refuse.
     */
    @Test
    @DisplayName("a cross-provider confirmation is refused before any provider query")
    void crossProviderConfirmationIsRefusedBeforeAnyQuery() {
        long editionId = createTryEdition("recon-cross", "7.00");
        long tenantId = ensureTenant("recon-tenant-cross");
        String token = startIyzicoCheckout(tenantId, editionId);
        jdbc.update("update payments set provider = 'paytr' where external_session_id = ?", token);
        canCollected(token, "ipay-cross");
        BillingProvider iyzico = providerRegistry.find("iyzico").orElseThrow();
        int retrievesBefore = IyzicoTestProviderConfig.RETRIEVE_CALLS.get();

        Logger serviceLogger = (Logger) LoggerFactory.getLogger(BillingConfirmationService.class);
        ListAppender<ILoggingEvent> capturedLog = new ListAppender<>();
        capturedLog.start();
        serviceLogger.addAppender(capturedLog);
        BillingConfirmationService.Outcome outcome;
        try {
            outcome = confirmationService.confirmBySessionQuery(iyzico, token, "iyzico-crosscheck");
        } finally {
            serviceLogger.detachAppender(capturedLog);
            capturedLog.stop();
        }

        assertThat(outcome).isEqualTo(BillingConfirmationService.Outcome.NOT_CONFIRMED);
        assertThat(IyzicoTestProviderConfig.RETRIEVE_CALLS.get())
                .as("the refusal must come BEFORE the network: asking iyzico about a PayTR row "
                        + "proves nothing about this payment, whatever the answer")
                .isEqualTo(retrievesBefore);
        assertThat(paymentRow(token).get("status"))
                .as("a canned COLLECTED answer was waiting — activation here would mean the "
                        + "guard is missing and money settles on the wrong authority's word")
                .isEqualTo("NOT_PAID");
        assertThat(capturedLog.list.stream().anyMatch(event ->
                event.getLevel() == ch.qos.logback.classic.Level.WARN
                        && event.getFormattedMessage().contains("refusing the cross-provider query")))
                .as("the refusal must be WARNed — a silently dropped trigger is undiagnosable")
                .isTrue();
    }

    @Test
    @DisplayName("two back-to-back job triggers acquire the ShedLock once (single execution across nodes)")
    void twoBackToBackJobTriggersRunOnce() {
        int before = reconciliationJob.executionCount();

        reconciliationJob.run();
        reconciliationJob.run();

        assertThat(reconciliationJob.executionCount() - before)
                .as("the second trigger must find the billing-reconciliation lock held "
                        + "(lockAtLeastFor) and skip — the ShedLockIT rule for this job")
                .isEqualTo(1);

        Map<String, Object> lock = jdbc.queryForMap(
                "select name, locked_by, lock_until > locked_at as held_past_run "
                        + "from shedlock where name = ?", BillingReconciliationJob.LOCK_NAME);
        assertThat(lock.get("name")).isEqualTo(BillingReconciliationJob.LOCK_NAME);
        assertThat(lock.get("locked_by")).isNotNull();
        assertThat(lock.get("held_past_run")).isEqualTo(true);
    }

    // ------------------------------------------------------------------ plumbing

    private long createTryEdition(String prefix, String monthlyPrice) {
        return createEdition(editionBody(uniqueEditionName(prefix), monthlyPrice, null, "TRY", 0, 7));
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

    /** Rewinds the row two hours — comfortably past the default PT1H min-age. */
    private void age(String token) {
        jdbc.update("update payments set created_at = created_at - interval '2 hours' "
                + "where external_session_id = ?", token);
    }

    /** Cans a CONFIRMING retrieve through the real predicate (fraud approved, paid, outer ok). */
    private static void canCollected(String token, String paymentId) {
        IyzicoTestProviderConfig.RETRIEVE_RESULTS.put(token, IyzicoBillingProviderTestHook
                .mapRetrieve("success", "SUCCESS", 1, paymentId, null));
    }

    private Map<String, Object> paymentRow(String token) {
        return jdbc.queryForMap("select * from payments where external_session_id = ?", token);
    }

    /** How many of THESE tokens are settled — leftover rows from other tests cannot perturb it. */
    private long paidCountAmong(String... tokens) {
        return List.of(tokens).stream()
                .filter(token -> "PAID".equals(paymentRow(token).get("status")))
                .count();
    }

    private String lastSubscriptionEventActor(long tenantId) {
        return jdbc.queryForObject(
                "select e.actor from subscription_events e "
                        + "join subscriptions s on s.id = e.subscription_id "
                        + "where s.tenant_id = ? order by e.id desc limit 1",
                String.class, tenantId);
    }
}
