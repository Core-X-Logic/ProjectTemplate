package com.mycompanyname.zero.saas.subscription;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    Optional<Subscription> findByTenantId(Long tenantId);

    Page<Subscription> findAllByOrderByTenantIdAsc(Pageable pageable);

    /** Guards edition deletion: an edition still sold to a tenant may not be removed. */
    long countByEditionId(Long editionId);

    // --- lifecycle job queries (states S6-S10 of the SubscriptionStatus transition table) ---

    /** S6: trials whose {@code trial_end_at} has passed. */
    List<Subscription> findByStatusAndTrialEndAtLessThanEqualOrderByIdAsc(SubscriptionStatus status, Instant at);

    /** S7/S8: paid subscriptions whose billed period has passed. */
    List<Subscription> findByStatusAndCurrentPeriodEndAtLessThanEqualOrderByIdAsc(
            SubscriptionStatus status, Instant at);

    /** S9: subscriptions whose grace window has passed. */
    List<Subscription> findByStatusAndGraceEndAtLessThanEqualOrderByIdAsc(SubscriptionStatus status, Instant at);

    /** S10: candidates for the downgrade onto their edition's (free) expiring edition. */
    List<Subscription> findByStatusOrderByIdAsc(SubscriptionStatus status);
}
