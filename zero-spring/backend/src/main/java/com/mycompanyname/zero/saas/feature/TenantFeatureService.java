package com.mycompanyname.zero.saas.feature;

import com.mycompanyname.zero.saas.feature.web.dto.FeatureValueDto;
import com.mycompanyname.zero.saas.feature.web.dto.TenantFeatureDto;
import com.mycompanyname.zero.shared.domain.DomainException;
import com.mycompanyname.zero.tenancy.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Host-side administration of per-tenant feature overrides. Writing is deliberately host-only
 * (F5-R3): a tenant must never be able to raise its own limits, so this service is only reachable
 * through {@code tenantfeatures.manage}, a {@code Side.HOST} permission.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class TenantFeatureService {

    private final TenantFeatureRepository tenantFeatureRepository;
    private final FeatureValueResolver resolver;
    private final TenantRepository tenantRepository;

    @Transactional(readOnly = true)
    public List<TenantFeatureDto> list(Long tenantId) {
        requireTenant(tenantId);
        return FeatureDefinitions.ALL.stream().map(definition -> describe(tenantId, definition)).toList();
    }

    /**
     * Batch upsert of tenant overrides. A {@code null}/blank value deletes the override so the
     * tenant falls back to its edition; unknown names and type-incompatible values are rejected.
     */
    public List<TenantFeatureDto> setValues(Long tenantId, List<FeatureValueDto> updates) {
        requireTenant(tenantId);
        if (updates == null) {
            return list(tenantId);
        }
        for (FeatureValueDto update : updates) {
            if (update == null || update.name() == null) {
                continue;
            }
            String normalized = FeatureDefinitions.normalize(update.name(), update.value());
            Optional<TenantFeature> existing =
                    tenantFeatureRepository.findByTenantIdAndFeatureName(tenantId, update.name());
            if (normalized == null) {
                existing.ifPresent(tenantFeatureRepository::delete);
                continue;
            }
            TenantFeature feature = existing.orElseGet(TenantFeature::new);
            feature.setTenantId(tenantId);
            feature.setFeatureName(update.name());
            feature.setValue(normalized);
            tenantFeatureRepository.save(feature);
        }
        return list(tenantId);
    }

    private TenantFeatureDto describe(Long tenantId, FeatureDefinition definition) {
        String override = resolver.tenantOverride(tenantId, definition.name());
        String editionValue = resolver.editionValue(tenantId, definition.name());
        String effective = override != null ? override
                : editionValue != null ? editionValue
                : definition.defaultValue();
        return new TenantFeatureDto(
                definition.name(),
                definition.type().name(),
                effective,
                override,
                editionValue,
                definition.defaultValue());
    }

    private void requireTenant(Long tenantId) {
        if (tenantId == null || !tenantRepository.existsById(tenantId)) {
            throw DomainException.notFound("Tenant not found: " + tenantId);
        }
    }
}
