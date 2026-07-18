package com.mycompanyname.zero.saas.edition;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EditionRepository extends JpaRepository<Edition, Long> {

    Optional<Edition> findByNameIgnoreCase(String name);

    Page<Edition> findAllByOrderBySortOrderAscIdAsc(Pageable pageable);

    /** The edition new tenants are provisioned with: the first active one in display order. */
    Optional<Edition> findFirstByActiveTrueOrderBySortOrderAscIdAsc();

    /** Guards deletion: an edition referenced as another edition's downgrade target may not be removed. */
    long countByExpiringEditionId(Long expiringEditionId);
}
