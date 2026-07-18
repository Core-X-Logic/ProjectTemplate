package com.mycompanyname.zero.saas.api;

/**
 * Names of the features the platform ships with. Consumers reference these constants instead of
 * raw strings; the authoritative metadata (type, default value) lives in the module-internal
 * {@code FeatureDefinitions} registry.
 */
public final class SaasFeatures {

    /** Maximum number of users a tenant may create. {@code 0} means unlimited (source semantics). */
    public static final String MAX_USER_COUNT = "app.maxUserCount";

    /** Whether audit logging is available to the tenant. */
    public static final String AUDIT_LOG = "app.auditLog";

    /** Whether the organization-unit hierarchy is available to the tenant. */
    public static final String ORGANIZATION_UNITS = "app.organizationUnits";

    private SaasFeatures() {
    }
}
