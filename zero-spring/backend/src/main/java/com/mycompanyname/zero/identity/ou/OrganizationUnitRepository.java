package com.mycompanyname.zero.identity.ou;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrganizationUnitRepository extends JpaRepository<OrganizationUnit, Long> {

    List<OrganizationUnit> findAllByOrderByCodeAsc();

    Optional<OrganizationUnit> findFirstByParentIdOrderByCodeDesc(Long parentId);

    Optional<OrganizationUnit> findFirstByParentIdIsNullOrderByCodeDesc();

    List<OrganizationUnit> findByCodeStartingWithOrderByCodeAsc(String codePrefix);

    /**
     * Single-statement bulk delete used to remove a whole subtree at once. The self-referencing
     * {@code parent_id} FK uses the default NO ACTION rule, so deleting parent and child rows in the
     * same statement is valid. The caller restricts {@code ids} to the current tenant's subtree.
     */
    @Modifying
    @Query("delete from OrganizationUnit o where o.id in :ids")
    void deleteByIdIn(@Param("ids") Collection<Long> ids);
}
