package com.mycompanyname.zero.saas.api;

/**
 * Read-only feature resolution for the tenant in the current {@code TenantContext}. Values are
 * resolved through the chain {@code tenant_features} (host override) &rarr; {@code edition_features}
 * (the tenant's edition) &rarr; {@code FeatureDefinition.defaultValue}.
 *
 * <p>Slice A exposes reading only; declarative enforcement ({@code @RequiresFeature} AOP) and the
 * Redis-backed cache arrive in Slice B.
 */
public interface FeatureChecker {

    /** {@code true} when the feature resolves to a truthy value. Non-BOOLEAN features yield {@code false}. */
    boolean isEnabled(String featureName);

    /** Raw resolved value, never {@code null} for a known feature (falls back to the definition default). */
    String value(String featureName);

    /**
     * Numeric value of the feature, {@code 0} when the resolved value is not a number.
     * For limit-style features {@code 0} means <em>unlimited</em>.
     */
    int intValue(String featureName);

    /** Same as {@link #value(String)} but for an explicit tenant (host-side administration). */
    String valueFor(Long tenantId, String featureName);
}
