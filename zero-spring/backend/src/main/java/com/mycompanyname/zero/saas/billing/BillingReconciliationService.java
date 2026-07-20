package com.mycompanyname.zero.saas.billing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The reconciliation pass (P2'-B): find payments stuck in {@code NOT_PAID}/{@code FAILED} past the
 * configured age and, for every one whose provider CAN be asked directly, run exactly the same
 * retrieve-confirm path the webhook and the callback run ({@link BillingConfirmationService}).
 * This automates the runbook §3.9 manual query for iyzico; PayTR and Stripe rows are counted and
 * skipped — neither has a captured query API — so §3.9 stays their net, now with the job's log
 * telling the operator how many rows that net must catch.
 *
 * <p><b>Bounded in BOTH dimensions</b> (stack-review Finding 2), because the ShedLock on the job
 * only means anything if a pass provably finishes inside {@code lock-at-most-for}:
 * <ul>
 *   <li><b>Rows:</b> the scan selects at most {@code max-rows-per-pass} (oldest id first, probe
 *       row cap+1 — truncation is a WARN, never silent). The scan admits ONLY query-capable
 *       providers; rows the job could never resolve must not clog the capped window (they are
 *       counted by a separate query instead — see {@code PaymentRepository}).</li>
 *   <li><b>Time per row:</b> each provider query is bounded by {@code query-timeout}, enforced at
 *       THIS layer because the SDK offers no knob — iyzipay-java 2.0.142's {@code Options} has no
 *       timeout setters and its {@code HttpClient} hardcodes 140 s connect AND read timeouts
 *       (measured via {@code javap -c}; up to 280 s per hung call). The query runs on a daemon
 *       worker and the pass waits at most {@code query-timeout}; on timeout the payment counts as
 *       unresolved, the confirmation transaction rolls back — releasing its DB connection, so
 *       reconciliation never holds more than ONE — and the abandoned SDK call dies alone at its
 *       own ceiling.</li>
 * </ul>
 * Worst-case pass duration is therefore {@code max-rows-per-pass × query-timeout} plus local DB
 * work — the arithmetic {@code lock-at-most-for} is sized against (see the job).
 *
 * <p><b>Deliberately NOT {@code @Transactional} at this level.</b> Each payment is confirmed in
 * {@code BillingConfirmationService}'s own transaction, so one failing provider query (or one
 * activation error) costs that payment's attempt and nothing else — a batch-wide transaction would
 * turn the 40th row's failure into a rollback of 39 settled confirmations.
 *
 * <p>The enabled flag is read HERE, not at bean registration: {@code BillingReconciliationJobIT}
 * proves the disabled behaviour against the same wiring production runs, and a disabled net is
 * still visible in the log (one line per scheduled run) rather than silently absent.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BillingReconciliationService {

    /** Suffix of the {@code subscription_events.actor} entry, e.g. {@code iyzico-reconciliation}. */
    static final String RECONCILIATION_ACTOR_SUFFIX = "-reconciliation";

    /** Both non-terminal shapes — see {@code PaymentRepository#findReconciliationCandidates}. */
    static final List<PaymentStatus> STUCK_STATUSES =
            List.of(PaymentStatus.NOT_PAID, PaymentStatus.FAILED);

    private static final AtomicInteger QUERY_THREAD_SEQUENCE = new AtomicInteger();

    private final BillingReconciliationProperties properties;
    private final BillingProviderRegistry providerRegistry;
    private final PaymentRepository paymentRepository;
    private final BillingConfirmationService confirmationService;
    private final java.time.Clock clock;

    public ReconciliationRun reconcile() {
        if (!properties.isEnabled()) {
            log.info("Billing reconciliation is disabled (zero.billing.reconciliation.enabled=false); "
                    + "stuck payments are covered by runbook §3.9 only");
            return ReconciliationRun.disabled();
        }
        Instant threshold = clock.instant().minus(properties.getMinAge());
        Set<String> queryCapableIds = queryCapableProviderIds();
        long skippedWithoutQuerySupport = paymentRepository.countStuckOutsideProviders(
                STUCK_STATUSES, threshold, guardAgainstEmptyInClause(queryCapableIds));
        if (queryCapableIds.isEmpty()) {
            // Nothing askable is enabled: every stuck row is §3.9's. Report and stop — running the
            // loop would be theatre.
            log.info("Billing reconciliation pass: no query-capable provider is enabled; {} stuck "
                    + "row(s) older than {} are covered by runbook §3.9 only",
                    skippedWithoutQuerySupport, properties.getMinAge());
            return new ReconciliationRun(true, 0, 0, 0, (int) skippedWithoutQuerySupport, false);
        }

        int cap = Math.max(1, properties.getMaxRowsPerPass());
        // cap + 1: the probe row, so "exactly cap" and "more than cap" are distinguishable — the
        // BoundedExport pattern. Only the first cap rows are processed.
        List<Payment> scanned = paymentRepository.findReconciliationCandidates(
                STUCK_STATUSES, threshold, queryCapableIds, PageRequest.of(0, cap + 1));
        boolean truncated = scanned.size() > cap;
        List<Payment> candidates = truncated ? scanned.subList(0, cap) : scanned;
        if (truncated) {
            // No silent caps (house rule). Named consequence: the window holds the OLDEST rows,
            // and rows that never resolve (abandoned checkouts, fraud reviews that never close)
            // keep their slots — a scan that stays truncated pass after pass is congested, not
            // merely busy, and needs §3.9 eyes.
            log.warn("Billing reconciliation scan truncated at {} row(s) "
                    + "(zero.billing.reconciliation.max-rows-per-pass); the remainder waits for "
                    + "the next run. A PERSISTENTLY truncated scan means the oldest rows are not "
                    + "resolving and are holding the window — review them per runbook §3.9", cap);
        }

        int resolved = 0;
        int unresolved = 0;
        ExecutorService queryExecutor = newQueryExecutor();
        try {
            for (Payment candidate : candidates) {
                BillingProvider provider = candidate.getProvider() == null ? null
                        : providerRegistry.find(candidate.getProvider()).orElse(null);
                if (provider == null || !provider.supportsQueryConfirmation()) {
                    // Belt to the query's braces: the scan already filters on the capable ids, so
                    // reaching this line means the registry changed between the two reads. Count
                    // it with the others rather than guessing.
                    skippedWithoutQuerySupport++;
                    continue;
                }
                try {
                    BillingConfirmationService.Outcome outcome =
                            confirmationService.confirmBySessionQuery(
                                    boundedQuery(provider, queryExecutor),
                                    candidate.getExternalSessionId(),
                                    provider.id() + RECONCILIATION_ACTOR_SUFFIX);
                    if (outcome == BillingConfirmationService.Outcome.CONFIRMED_ACTIVATED
                            || outcome == BillingConfirmationService.Outcome.ALREADY_PAID) {
                        resolved++;
                    } else {
                        // NOT_CONFIRMED / OPERATOR_CANCELLED (NO_PAYMENT_ROW cannot happen off a
                        // scan hit). The confirmation service already logged WHY at WARN; the row
                        // stays put and the next run re-asks.
                        unresolved++;
                    }
                } catch (RuntimeException ex) {
                    // One payment's provider query failing (including OUR query-timeout) must not
                    // end the pass. WARN with the payment id (ours, safe), the cause in the log,
                    // and the next run retries.
                    unresolved++;
                    log.warn("Reconciliation query for payment {} via {} failed; will retry next run",
                            candidate.getId(), provider.id(), ex);
                }
            }
        } finally {
            // shutdown(), not shutdownNow(): interrupting an HttpURLConnection frees nothing, and
            // the workers are daemons — an abandoned call dies at the SDK's own 140 s ceiling
            // without being able to hold the JVM open.
            queryExecutor.shutdown();
        }

        // Summary ALWAYS, even at zero: a net whose silence is indistinguishable from a net that
        // did not run is the "green ≠ verified" failure this project keeps re-learning.
        log.info("Billing reconciliation pass: {} candidate(s) older than {}{}, {} resolved, "
                + "{} unresolved (left for the next run), {} skipped without a query-capable "
                + "provider (runbook §3.9 covers those)", candidates.size(), properties.getMinAge(),
                truncated ? " (TRUNCATED at the per-pass cap)" : "",
                resolved, unresolved, skippedWithoutQuerySupport);
        return new ReconciliationRun(true, candidates.size(), resolved, unresolved,
                (int) skippedWithoutQuerySupport, truncated);
    }

    /** Registry ids whose providers can actually be asked ({@code supportsQueryConfirmation}). */
    private Set<String> queryCapableProviderIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (String id : providerRegistry.ids()) {
            if (providerRegistry.find(id).map(BillingProvider::supportsQueryConfirmation).orElse(false)) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** JPQL {@code in ()} over an empty collection is an error, not an empty set. */
    private static Set<String> guardAgainstEmptyInClause(Set<String> ids) {
        return ids.isEmpty() ? Set.of("__none__") : ids;
    }

    /**
     * Bounds ONE thing: how long {@code confirmBySessionQuery} may keep the pass (and the
     * confirmation transaction's DB connection) waiting — see the class contract for why the bound
     * cannot live in the SDK. Every other SPI method delegates untouched.
     */
    private BillingProvider boundedQuery(BillingProvider provider, ExecutorService executor) {
        long timeoutMillis = properties.getQueryTimeout().toMillis();
        return new BillingProvider() {
            @Override
            public String id() {
                return provider.id();
            }

            @Override
            public BillingEvent verifyAndParse(String payload, String signatureHeader) {
                return provider.verifyAndParse(payload, signatureHeader);
            }

            @Override
            public String successAckBody() {
                return provider.successAckBody();
            }

            @Override
            public CheckoutSession createCheckoutSession(CheckoutRequest request) {
                return provider.createCheckoutSession(request);
            }

            @Override
            public boolean supportsQueryConfirmation() {
                return provider.supportsQueryConfirmation();
            }

            @Override
            public ProviderPaymentConfirmation confirmBySessionQuery(String sessionId) {
                Future<ProviderPaymentConfirmation> answer =
                        executor.submit(() -> provider.confirmBySessionQuery(sessionId));
                try {
                    return answer.get(timeoutMillis, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ex) {
                    answer.cancel(true);
                    throw new IllegalStateException("The " + provider.id() + " session query "
                            + "exceeded zero.billing.reconciliation.query-timeout ("
                            + properties.getQueryTimeout() + "); the pass moves on and the next "
                            + "run re-asks", ex);
                } catch (ExecutionException ex) {
                    if (ex.getCause() instanceof RuntimeException runtime) {
                        throw runtime;
                    }
                    throw new IllegalStateException(
                            "The " + provider.id() + " session query failed", ex.getCause());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "Interrupted while waiting for the " + provider.id() + " session query", ex);
                }
            }
        };
    }

    private static ExecutorService newQueryExecutor() {
        return Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable,
                    "billing-recon-query-" + QUERY_THREAD_SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * What one pass saw and did — returned so the job can log and the IT can assert (including the
     * vacuity guard: a happy-path test whose {@code candidates} is 0 measured nothing and says so).
     */
    public record ReconciliationRun(
            boolean ran,
            int candidates,
            int resolved,
            int unresolved,
            int skippedWithoutQuerySupport,
            boolean truncated) {

        static ReconciliationRun disabled() {
            return new ReconciliationRun(false, 0, 0, 0, 0, false);
        }
    }
}
