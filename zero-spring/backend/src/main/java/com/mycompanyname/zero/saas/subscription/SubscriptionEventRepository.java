package com.mycompanyname.zero.saas.subscription;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubscriptionEventRepository extends JpaRepository<SubscriptionEvent, Long> {

    /**
     * Ordered by id rather than {@code occurredAt}: several transitions can share the same instant,
     * and the identity sequence is the only strictly monotonic tiebreaker.
     */
    List<SubscriptionEvent> findBySubscriptionIdOrderByIdAsc(Long subscriptionId);
}
