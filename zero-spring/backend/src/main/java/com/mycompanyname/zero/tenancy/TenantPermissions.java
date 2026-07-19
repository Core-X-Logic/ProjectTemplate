package com.mycompanyname.zero.tenancy;

import java.util.Set;

/**
 * Tenancy permission constants, owned by the {@code tenancy} module.
 *
 * <p>They cannot live in {@code identity}: {@code identity} already depends on {@code tenancy}, so
 * importing {@code AppPermissions} here would close a module cycle and fail
 * {@code ModularityTests.verify()}. The identity side repeats the same string values to register
 * them in the permission tree; {@code PermissionRegistryAlignmentTest} fails the build if the two
 * ever drift.
 *
 * <p>{@link #TENANTS_MANAGE} is registered as {@code Side.HOST}: creating and disabling tenants is
 * an installation-level act, so a tenant must never hold it.
 */
public final class TenantPermissions {

    public static final String TENANTS_MANAGE = "tenants.manage";

    private TenantPermissions() {
    }

    public static Set<String> all() {
        return Set.of(TENANTS_MANAGE);
    }
}
