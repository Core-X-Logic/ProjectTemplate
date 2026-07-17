package com.mycompanyname.zero.audit.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface EntityChangeRepository extends JpaRepository<EntityChange, Long>, JpaSpecificationExecutor<EntityChange> {
}
