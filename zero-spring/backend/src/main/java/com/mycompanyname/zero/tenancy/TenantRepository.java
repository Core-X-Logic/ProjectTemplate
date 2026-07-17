package com.mycompanyname.zero.tenancy;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<Tenant, Long> {

    Optional<Tenant> findByNameIgnoreCase(String name);
}
