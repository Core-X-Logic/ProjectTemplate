package com.mycompanyname.zero.settings.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SettingRepository extends JpaRepository<Setting, Long> {

    Optional<Setting> findByScopeAndScopeIdAndName(Scope scope, Long scopeId, String name);

    Optional<Setting> findByScopeAndScopeIdIsNullAndName(Scope scope, String name);
}
