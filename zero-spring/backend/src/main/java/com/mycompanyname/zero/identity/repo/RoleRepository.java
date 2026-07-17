package com.mycompanyname.zero.identity.repo;

import com.mycompanyname.zero.identity.domain.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByTenantIdAndNameIgnoreCase(Long tenantId, String name);

    Optional<Role> findByNameIgnoreCaseAndTenantIdIsNull(String name);

    List<Role> findAllByTenantId(Long tenantId);

    List<Role> findAllByTenantIdIsNull();

    @EntityGraph(attributePaths = "permissions")
    Page<Role> findAllByTenantId(Long tenantId, Pageable pageable);

    @EntityGraph(attributePaths = "permissions")
    Page<Role> findAllByTenantIdIsNull(Pageable pageable);
}
