package com.mycompanyname.zero.saas.feature;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TenantFeatureRepository extends JpaRepository<TenantFeature, Long> {

    List<TenantFeature> findByTenantId(Long tenantId);

    Optional<TenantFeature> findByTenantIdAndFeatureName(Long tenantId, String featureName);
}
