package com.mycompanyname.zero.saas.billing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scheduled entry point of the billing reconciliation (P2'-B) — the {@code SubscriptionLifecycleJob}
 * pattern exactly: scheduling and locking live here, the domain work lives in
 * {@link BillingReconciliationService}, so the reconciliation rules can be exercised in a test
 * without fighting the distributed lock and vice versa.
 *
 * <p><b>Single execution across instances.</b> {@code @SchedulerLock} (V5 {@code shedlock} infra)
 * takes a row for the duration of the run; {@code lockAtLeastFor} holds it briefly past the end so
 * two nodes with slightly skewed clocks cannot fire back to back. ShedLock's default
 * {@code PROXY_METHOD} mode honours the lock for every call through the bean proxy — which is what
 * lets {@code BillingReconciliationJobIT} stand in for a second node by calling {@link #run()}
 * directly.
 *
 * <p>The schedule comes from {@code zero.billing.reconciliation.*} (default hourly); the test
 * profile pushes it far out so the suite drives the pass explicitly instead of racing it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BillingReconciliationJob {

    public static final String LOCK_NAME = "billing-reconciliation";

    private final BillingReconciliationService reconciliationService;

    /** Counts runs that actually acquired the lock — the evidence the job IT asserts on. */
    private final AtomicInteger executions = new AtomicInteger();

    // lock-at-most-for ARITHMETIC (stack-review Finding 2c) — re-do this if you touch any input.
    // A pass is bounded in both dimensions by BillingReconciliationService: at most
    // max-rows-per-pass (default 50) provider queries, each bounded by query-timeout (default
    // 30 s) at OUR layer (the SDK hardcodes 140 s and offers no knob — measured, see the service).
    // Worst case provider waits: 50 × 30 s = 25 min; local DB work and scheduling jitter ride on
    // top, so the lock ceiling is PT45M — comfortably above the bound, far below the point where a
    // dead node's stale lock would block reconciliation for a shift. Raising max-rows-per-pass or
    // query-timeout without raising this re-opens the exact overlap (two instances in one pass →
    // the BillingConfirmationConcurrencyIT window) the ceiling exists to prevent.
    @Scheduled(
            initialDelayString = "${zero.billing.reconciliation.initial-delay:PT2M}",
            fixedDelayString = "${zero.billing.reconciliation.interval:PT1H}")
    @SchedulerLock(
            name = LOCK_NAME,
            lockAtLeastFor = "${zero.billing.reconciliation.lock-at-least-for:PT30S}",
            lockAtMostFor = "${zero.billing.reconciliation.lock-at-most-for:PT45M}")
    public void run() {
        executions.incrementAndGet();
        BillingReconciliationService.ReconciliationRun run = reconciliationService.reconcile();
        log.debug("Billing reconciliation job finished: {}", run);
    }

    /** Number of runs that acquired the lock since startup. */
    public int executionCount() {
        return executions.get();
    }
}
