package com.mycompanyname.zero.saas.feature.web.dto;

/**
 * The resolved state of one feature for a tenant, with every link of the chain exposed so the admin
 * override panel can show what the effective value comes from.
 *
 * @param value         effective value (override &rarr; edition &rarr; default)
 * @param overrideValue the tenant-level override, {@code null} when not overridden
 * @param editionValue  the value inherited from the tenant's edition, {@code null} when unset
 * @param defaultValue  the {@code FeatureDefinition} fallback
 */
public record TenantFeatureDto(
        String name,
        String type,
        String value,
        String overrideValue,
        String editionValue,
        String defaultValue) {
}
