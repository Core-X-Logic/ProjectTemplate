package com.mycompanyname.zero.identity.repo;

import com.mycompanyname.zero.identity.domain.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByTenantIdAndNameIgnoreCase(Long tenantId, String name);

    Optional<Role> findByNameIgnoreCaseAndTenantIdIsNull(String name);

    List<Role> findAllByTenantId(Long tenantId);

    List<Role> findAllByTenantIdIsNull();

    /**
     * Paged role listing. Carries NO {@code permissions} fetch, and must not grow one: the listing
     * produces {@code RoleDto}, which has no permissions field at all, so the {@code @EntityGraph}
     * this method used to declare fetched an element collection that {@code RoleService.toDto} then
     * threw away — while costing the whole table on every request, because Hibernate cannot apply a
     * {@code LIMIT} on top of a collection fetch and falls back to reading every row and slicing in
     * memory ({@code HHH90003004}).
     *
     * <p>Permissions belong to the DETAIL view ({@code RoleDetailDto}), which is loaded one role at a
     * time by {@code findById} and pages nothing.
     *
     * <p>If a future {@code RoleDto} does need permissions, do NOT re-add {@code @EntityGraph} here —
     * use the two-stage form {@code UserRepository.searchIdsByTenantId} +
     * {@code findAllByIdIn} demonstrates, and restore the id order on the way out.
     */
    Page<Role> findAllByTenantId(Long tenantId, Pageable pageable);

    /** Host-scope ({@code tenant_id is null}) counterpart of {@link #findAllByTenantId(Long, Pageable)}. */
    Page<Role> findAllByTenantIdIsNull(Pageable pageable);
}
