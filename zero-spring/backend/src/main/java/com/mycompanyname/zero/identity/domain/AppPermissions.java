package com.mycompanyname.zero.identity.domain;

import java.util.Set;

public final class AppPermissions {

    // Phase 1 (kept, unchanged string values)
    public static final String USERS_READ = "users.read";
    public static final String USERS_CREATE = "users.create";
    public static final String USERS_UPDATE = "users.update";
    public static final String USERS_DELETE = "users.delete";
    public static final String ROLES_MANAGE = "roles.manage";
    public static final String TENANTS_MANAGE = "tenants.manage";

    // Phase 2 additions
    public static final String USERS_IMPERSONATE = "users.impersonate";
    public static final String USERS_UNLOCK = "users.unlock";
    public static final String ROLES_READ = "roles.read";
    public static final String ROLES_CREATE = "roles.create";
    public static final String ROLES_UPDATE = "roles.update";
    public static final String ROLES_DELETE = "roles.delete";
    public static final String OU_MANAGE = "organizationunits.manage";
    public static final String AUDITLOGS_READ = "auditlogs.read";
    public static final String SETTINGS_TENANT = "settings.tenant.manage";
    public static final String SETTINGS_HOST = "settings.host.manage";   // HOST-ONLY
    public static final String LANGUAGES_MANAGE = "languages.manage";    // HOST-ONLY

    // Phase 5 (SaaS) — all HOST-ONLY.
    // The values are intentionally repeated here as plain string literals instead of importing
    // com.mycompanyname.zero.saas.SaasPermissions: the saas module core must never depend on
    // identity, and importing it the other way round would create the very cycle F5-R1 forbids.
    // SaasPermissionsAlignmentTest fails the build if the two lists ever drift apart.
    public static final String EDITIONS_READ = "editions.read";
    public static final String EDITIONS_MANAGE = "editions.manage";
    public static final String SUBSCRIPTIONS_READ = "subscriptions.read";
    public static final String SUBSCRIPTIONS_MANAGE = "subscriptions.manage";
    public static final String TENANT_FEATURES_MANAGE = "tenantfeatures.manage";

    private AppPermissions() {
    }

    public static Set<String> all() {
        return Set.of(
                USERS_READ, USERS_CREATE, USERS_UPDATE, USERS_DELETE, USERS_UNLOCK, USERS_IMPERSONATE,
                ROLES_READ, ROLES_CREATE, ROLES_UPDATE, ROLES_DELETE, ROLES_MANAGE,
                OU_MANAGE, AUDITLOGS_READ,
                SETTINGS_TENANT, SETTINGS_HOST, LANGUAGES_MANAGE,
                TENANTS_MANAGE,
                EDITIONS_READ, EDITIONS_MANAGE,
                SUBSCRIPTIONS_READ, SUBSCRIPTIONS_MANAGE,
                TENANT_FEATURES_MANAGE);
    }
}
