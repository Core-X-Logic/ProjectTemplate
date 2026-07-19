package com.mycompanyname.zero.settings;

import java.util.Set;

/**
 * Settings permission constants, owned by the {@code settings} module.
 *
 * <p>They cannot live in {@code identity}: {@code identity} already depends on {@code settings}, so
 * importing {@code AppPermissions} here would close a module cycle and fail
 * {@code ModularityTests.verify()}. The identity side repeats the same string values to register
 * them in the permission tree; {@code PermissionRegistryAlignmentTest} fails the build if the two
 * ever drift.
 *
 * <p>{@link #SETTINGS_HOST} is registered as {@code Side.HOST}: host settings configure the
 * installation itself, so a tenant must never hold it.
 */
public final class SettingsPermissions {

    public static final String SETTINGS_TENANT = "settings.tenant.manage";
    public static final String SETTINGS_HOST = "settings.host.manage";

    private SettingsPermissions() {
    }

    public static Set<String> all() {
        return Set.of(SETTINGS_TENANT, SETTINGS_HOST);
    }
}
