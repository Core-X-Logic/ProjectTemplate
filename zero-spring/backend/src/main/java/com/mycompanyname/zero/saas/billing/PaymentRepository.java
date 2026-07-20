package com.mycompanyname.zero.saas.billing;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** Plain lookup, for callers that only read. Backed by {@code uq_payments_external_session}. */
    Optional<Payment> findByExternalSessionId(String externalSessionId);

    /**
     * The three facts {@code BillingConfirmationService} peeks at before deciding whether to query
     * the provider — a PROJECTION, deliberately not the entity, and that is load-bearing (measured,
     * stack-review Finding 1): loading the {@code Payment} ENTITY here puts it into the
     * transaction's persistence context, and Hibernate resolves query results BY IDENTITY — the
     * later {@code PESSIMISTIC_WRITE} lookup then executes its {@code select ... for update}
     * (taking the SQL lock correctly) but returns the already-loaded instance with its STALE state.
     * The losing transaction of a race would block on the row lock, win it after the winner's
     * commit, re-read {@code NOT_PAID} out of its own cache, pass the PAID guard and activate a
     * SECOND time. A scalar projection never enters the persistence context, so the locked read
     * stays the FIRST entity load and sees the winner's committed state
     * ({@code BillingConfirmationConcurrencyIT} pins this with two latched transactions).
     */
    interface PaymentPeek {
        Long getId();
        PaymentStatus getStatus();
        String getProvider();
    }

    /** See {@link PaymentPeek}: the pre-lock read of the confirmation path, and only that path's. */
    @Query("select p.id as id, p.status as status, p.provider as provider from Payment p "
            + "where p.externalSessionId = :externalSessionId")
    Optional<PaymentPeek> peekByExternalSessionId(@Param("externalSessionId") String externalSessionId);

    /**
     * The WEBHOOK-PATH lookup, and only that path's: {@code select ... for update}.
     *
     * <p>Why the plain lookup is not enough there: the event dedup serializes deliveries of the
     * SAME event id, but two checkout-completed events with DIFFERENT ids naming the same session
     * both pass dedup. Without this lock both could read the payment as {@code NOT_PAID} and both
     * activate — a double period extension. With {@code PESSIMISTIC_WRITE} the second transaction
     * blocks on the row until the first commits, then reads {@code PAID} and the payment-status
     * guard in {@code BillingWebhookService} fires. Other callers keep the plain lookup so a
     * dashboard read can never queue behind a webhook.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.externalSessionId = :externalSessionId")
    Optional<Payment> findByExternalSessionIdForUpdate(@Param("externalSessionId") String externalSessionId);

    /**
     * The reconciliation scan (P2'-B): payments stuck in a non-terminal state older than the
     * configured age, session id present (no session = nothing any provider can be asked about),
     * attributed to a provider that CAN be asked ({@code providers} = the query-capable ids the
     * service derives from the registry). BOTH {@code NOT_PAID} and {@code FAILED} are scanned on
     * purpose — failed → success is a legitimate PayTR/iyzico sequence (buyer retries in-session),
     * so a payment whose success notification was lost can be sitting in EITHER shape; the
     * NOT_PAID-only version of this query is the measured gap the runbook §3.9 commentary
     * describes.
     *
     * <p><b>The provider filter is IN the query, deliberately</b> (stack-review Finding 2): the
     * scan is capped ({@code Pageable}, oldest id first), and a capped window that admits rows the
     * job can never resolve — PayTR/Stripe/unattributed — would be permanently CLOGGED by them:
     * the same low-id stuck heads re-selected every pass, the resolvable rows behind them starved.
     * Rows outside the capable set are still counted, loudly, by
     * {@link #countStuckOutsideProviders} — skipping must never be silent (runbook §3.9 signal).
     *
     * <p>No {@code @EntityGraph}, no collection fetch — a flat capped row scan (Rule 1 does not
     * apply). The caller fetches {@code cap + 1} and treats the extra row as "truncated" — the
     * {@code BoundedExport} probe-row pattern.
     */
    @Query("select p from Payment p where p.status in :statuses and p.externalSessionId is not null "
            + "and p.createdAt < :threshold and p.provider in :providers order by p.id")
    List<Payment> findReconciliationCandidates(@Param("statuses") Collection<PaymentStatus> statuses,
                                               @Param("threshold") Instant threshold,
                                               @Param("providers") Collection<String> providers,
                                               Pageable pageable);

    /**
     * The stuck rows the scan above deliberately does NOT select: provider null (pre-V9, backfill
     * miss) or a provider with no query API. Counted every pass so the operator's §3.9 workload is
     * a reported number, never a silently unselected set.
     */
    @Query("select count(p) from Payment p where p.status in :statuses "
            + "and p.externalSessionId is not null and p.createdAt < :threshold "
            + "and (p.provider is null or p.provider not in :providers)")
    long countStuckOutsideProviders(@Param("statuses") Collection<PaymentStatus> statuses,
                                    @Param("threshold") Instant threshold,
                                    @Param("providers") Collection<String> providers);
}
