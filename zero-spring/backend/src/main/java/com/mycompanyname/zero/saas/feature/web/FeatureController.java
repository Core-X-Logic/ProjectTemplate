package com.mycompanyname.zero.saas.feature.web;

import com.mycompanyname.zero.saas.SaasPermissions;
import com.mycompanyname.zero.saas.feature.FeatureDefinition;
import com.mycompanyname.zero.saas.feature.FeatureDefinitions;
import com.mycompanyname.zero.saas.feature.TenantFeatureService;
import com.mycompanyname.zero.saas.feature.web.dto.FeatureDefinitionDto;
import com.mycompanyname.zero.saas.feature.web.dto.FeatureValueDto;
import com.mycompanyname.zero.saas.feature.web.dto.TenantFeatureDto;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Feature metadata and per-tenant overrides. Two path groups live here on purpose: the definition
 * catalogue belongs to the edition editor ({@code editions.read}), while writing a tenant override is
 * a distinct, stricter capability ({@code tenantfeatures.manage}).
 */
@RestController
@RequiredArgsConstructor
public class FeatureController {

    private final TenantFeatureService tenantFeatureService;

    /** Every known feature with its type and default, so the UI can render the right editor. */
    @GetMapping("/api/features/definitions")
    @PreAuthorize("hasAuthority('" + SaasPermissions.EDITIONS_READ + "')")
    public List<FeatureDefinitionDto> definitions() {
        return FeatureDefinitions.ALL.stream().map(FeatureController::toDto).toList();
    }

    @GetMapping("/api/tenant-features/{tenantId}")
    @PreAuthorize("hasAuthority('" + SaasPermissions.TENANT_FEATURES_MANAGE + "')")
    public List<TenantFeatureDto> tenantFeatures(@PathVariable Long tenantId) {
        return tenantFeatureService.list(tenantId);
    }

    @PutMapping("/api/tenant-features/{tenantId}")
    @PreAuthorize("hasAuthority('" + SaasPermissions.TENANT_FEATURES_MANAGE + "')")
    public List<TenantFeatureDto> updateTenantFeatures(@PathVariable Long tenantId,
                                                       @RequestBody List<FeatureValueDto> updates) {
        return tenantFeatureService.setValues(tenantId, updates);
    }

    private static FeatureDefinitionDto toDto(FeatureDefinition definition) {
        return new FeatureDefinitionDto(
                definition.name(),
                definition.displayNameKey(),
                definition.type().name(),
                definition.defaultValue(),
                definition.visibleOnPricingTable());
    }
}
