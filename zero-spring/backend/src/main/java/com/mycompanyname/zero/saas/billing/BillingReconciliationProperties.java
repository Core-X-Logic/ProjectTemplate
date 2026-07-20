package com.mycompanyname.zero.saas.billing;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Knobs of the scheduled payment reconciliation (P2'-B), {@code zero.billing.reconciliation.*}.
 * The SCHEDULE itself ({@code initial-delay}, {@code interval}, {@code lock-at-least-for},
 * {@code lock-at-most-for}) is read directly by the {@code @Scheduled}/{@code @SchedulerLock}
 * placeholders on {@link BillingReconciliationJob}, the {@code zero.saas.lifecycle} pattern; this
 * class binds the two values the SERVICE reads per run.
 */
@Component
@ConfigurationProperties(prefix = "zero.billing.reconciliation")
@Getter
@Setter
public class BillingReconciliationProperties {

    /**
     * ON by default, deliberately — the inverse of the provider flags. The job is a safety net
     * whose whole point is catching deliveries that never arrived; a net that must be remembered
     * is not a net. It is inert on a fresh clone anyway: with no query-capable provider enabled,
     * every scan resolves to counts of zero.
     */
    private boolean enabled = true;

    /**
     * How old a {@code NOT_PAID}/{@code FAILED} payment must be before the job asks the provider
     * about it. The floor exists because a young NOT_PAID row is usually a buyer mid-checkout —
     * querying those would "reconcile" sessions that are simply not finished yet. One hour matches
     * the runbook §3.9 manual query this job automates.
     */
    private Duration minAge = Duration.ofHours(1);

    /**
     * The most rows one pass may query (stack-review Finding 2a): the scan fetches {@code cap+1},
     * processes {@code cap}, and the probe row makes truncation a WARN, never a silent cap (the
     * {@code BoundedExport} rule). This number is one input of the ShedLock arithmetic on
     * {@link BillingReconciliationJob} — raising it without re-doing that arithmetic can push a
     * pass past {@code lock-at-most-for} and let a second instance start the same pass.
     */
    private int maxRowsPerPass = 50;

    /**
     * The most wall-clock one provider query may cost the pass (stack-review Finding 2b). This
     * bound CANNOT live in the SDK: iyzipay-java 2.0.142's {@code Options} has no timeout knobs,
     * and its {@code HttpClient} hardcodes 140 000 ms connect AND read timeouts (measured with
     * {@code javap -c}; worst case 280 s per call). So the pass enforces it: each query runs on a
     * daemon worker and the pass waits at most this long — on timeout the payment counts as
     * unresolved (WARN, retried next run) and the abandoned SDK call dies alone at its own
     * ceiling, holding NO database connection (the confirmation transaction rolled back and
     * released it when the timeout propagated).
     */
    private Duration queryTimeout = Duration.ofSeconds(30);
}
