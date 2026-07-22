package com.mycompanyname.zero.saas.subscription;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubscriptionEventRepository extends JpaRepository<SubscriptionEvent, Long> {

    /**
     * Ordered by id rather than {@code occurredAt}: several transitions can share the same instant,
     * and the identity sequence is the only strictly monotonic tiebreaker.
     */
    List<SubscriptionEvent> findBySubscriptionIdOrderByIdAsc(Long subscriptionId);

    /**
     * Idempotency ledger for the pre-expiry notice: "was a notice already recorded inside the
     * current period's window?" — a renewal moves the window forward, which re-arms the notice.
     */
    boolean existsBySubscriptionIdAndReasonAndOccurredAtGreaterThanEqual(
            Long subscriptionId, String reason, java.time.Instant since);
}
