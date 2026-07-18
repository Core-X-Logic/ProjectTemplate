package com.mycompanyname.zero.saas.subscription;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scheduled entry point of the subscription lifecycle.
 *
 * <p>The class is intentionally thin: scheduling and locking live here, the domain work lives in
 * {@link SubscriptionLifecycleProcessor}. Splitting them means the lifecycle rules can be exercised
 * in a test without fighting the distributed lock, and the lock can be exercised without depending
 * on which subscriptions happen to be due.
 *
 * <p><b>Single execution across instances (K10).</b> {@code @SchedulerLock} takes a row in the
 * {@code shedlock} table for the duration of the run. {@code lockAtLeastFor} keeps that row held for
 * a short while <em>after</em> the run finishes, which is what protects against two nodes whose
 * clocks are slightly apart firing back to back. ShedLock's default {@code PROXY_METHOD} mode means
 * the lock is honoured for every call through the bean proxy, not just for scheduler-driven ones.
 *
 * <p>The interval comes from {@code zero.saas.lifecycle.interval} (default one hour); the test
 * profile pushes it far out so the suite drives the job explicitly instead of racing it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionLifecycleJob {

    public static final String LOCK_NAME = "subscription-lifecycle";

    private final SubscriptionLifecycleProcessor processor;

    /** Counts runs that actually acquired the lock — the evidence {@code ShedLockIT} asserts on. */
    private final AtomicInteger executions = new AtomicInteger();

    @Scheduled(
            initialDelayString = "${zero.saas.lifecycle.initial-delay:PT1M}",
            fixedDelayString = "${zero.saas.lifecycle.interval:PT1H}")
    @SchedulerLock(
            name = LOCK_NAME,
            lockAtLeastFor = "${zero.saas.lifecycle.lock-at-least-for:PT30S}",
            lockAtMostFor = "${zero.saas.lifecycle.lock-at-most-for:PT10M}")
    public void run() {
        executions.incrementAndGet();
        int changed = processor.processDueSubscriptions();
        log.debug("Subscription lifecycle job finished, {} subscription(s) advanced", changed);
    }

    /** Number of runs that acquired the lock since startup. */
    public int executionCount() {
        return executions.get();
    }
}
