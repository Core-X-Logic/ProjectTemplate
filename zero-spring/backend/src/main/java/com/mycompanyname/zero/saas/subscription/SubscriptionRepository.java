package com.mycompanyname.zero.saas.subscription;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    Optional<Subscription> findByTenantId(Long tenantId);

    Page<Subscription> findAllByOrderByTenantIdAsc(Pageable pageable);

    /** Guards edition deletion: an edition still sold to a tenant may not be removed. */
    long countByEditionId(Long editionId);
}
