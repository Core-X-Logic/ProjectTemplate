package com.mycompanyname.zero.saas.feature;

/**
 * Static metadata describing a known feature: its name, localization key, value type, the fallback
 * used when neither the tenant nor its edition overrides it, and whether it should be advertised on
 * a public pricing table.
 *
 * <p>Deliberately separate from {@code SettingDefinition}: settings and features are different
 * concepts with different storage and resolution chains (F5-ARCHITECTURE §6).
 */
public record FeatureDefinition(
        String name,
        String displayNameKey,
        FeatureType type,
        String defaultValue,
        boolean visibleOnPricingTable) {
}
