package com.mycompanyname.zero.identity.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Static registry of the permission tree. Group nodes organize the tree; leaf nodes correspond to
 * grantable {@link AppPermissions} values.
 */
public final class PermissionDefinitions {

    // Group node names
    public static final String GROUP_ADMINISTRATION = "Pages.Administration";
    public static final String GROUP_USERS = "Pages.Administration.Users";
    public static final String GROUP_ROLES = "Pages.Administration.Roles";
    public static final String GROUP_ORGANIZATION_UNITS = "Pages.Administration.OrganizationUnits";
    public static final String GROUP_AUDIT_LOGS = "Pages.Administration.AuditLogs";
    public static final String GROUP_SETTINGS = "Pages.Administration.Settings";
    public static final String GROUP_TENANTS = "Pages.Administration.Tenants";
    public static final String GROUP_LANGUAGES = "Pages.Administration.Languages";
    public static final String GROUP_SAAS = "Pages.Administration.Saas";

    private static final String KEY_PREFIX = "Permission.";

    public static final List<PermissionDefinition> TREE = List.of(
            group(GROUP_ADMINISTRATION, null, Side.BOTH),

            group(GROUP_USERS, GROUP_ADMINISTRATION, Side.BOTH),
            leaf(AppPermissions.USERS_READ, GROUP_USERS, Side.BOTH),
            leaf(AppPermissions.USERS_CREATE, GROUP_USERS, Side.BOTH),
            leaf(AppPermissions.USERS_UPDATE, GROUP_USERS, Side.BOTH),
            leaf(AppPermissions.USERS_DELETE, GROUP_USERS, Side.BOTH),
            leaf(AppPermissions.USERS_UNLOCK, GROUP_USERS, Side.BOTH),
            leaf(AppPermissions.USERS_IMPERSONATE, GROUP_USERS, Side.BOTH),

            group(GROUP_ROLES, GROUP_ADMINISTRATION, Side.BOTH),
            leaf(AppPermissions.ROLES_READ, GROUP_ROLES, Side.BOTH),
            leaf(AppPermissions.ROLES_CREATE, GROUP_ROLES, Side.BOTH),
            leaf(AppPermissions.ROLES_UPDATE, GROUP_ROLES, Side.BOTH),
            leaf(AppPermissions.ROLES_DELETE, GROUP_ROLES, Side.BOTH),

            group(GROUP_ORGANIZATION_UNITS, GROUP_ADMINISTRATION, Side.BOTH),
            leaf(AppPermissions.OU_MANAGE, GROUP_ORGANIZATION_UNITS, Side.BOTH),

            group(GROUP_AUDIT_LOGS, GROUP_ADMINISTRATION, Side.BOTH),
            leaf(AppPermissions.AUDITLOGS_READ, GROUP_AUDIT_LOGS, Side.BOTH),

            group(GROUP_SETTINGS, GROUP_ADMINISTRATION, Side.BOTH),
            leaf(AppPermissions.SETTINGS_TENANT, GROUP_SETTINGS, Side.BOTH),
            leaf(AppPermissions.SETTINGS_HOST, GROUP_SETTINGS, Side.HOST),

            group(GROUP_TENANTS, GROUP_ADMINISTRATION, Side.BOTH),
            leaf(AppPermissions.TENANTS_MANAGE, GROUP_TENANTS, Side.HOST),

            group(GROUP_LANGUAGES, GROUP_ADMINISTRATION, Side.BOTH),
            leaf(AppPermissions.LANGUAGES_MANAGE, GROUP_LANGUAGES, Side.HOST),

            // SaaS: every leaf is HOST-only, so the seeder automatically withholds them from the
            // tenant Admin role and a tenant can never resell or re-scope itself
            // (see ARCHITECTURE-RULES.md — "Tenant kendi limitini yükseltemez").
            group(GROUP_SAAS, GROUP_ADMINISTRATION, Side.BOTH),
            leaf(AppPermissions.EDITIONS_READ, GROUP_SAAS, Side.HOST),
            leaf(AppPermissions.EDITIONS_MANAGE, GROUP_SAAS, Side.HOST),
            leaf(AppPermissions.SUBSCRIPTIONS_READ, GROUP_SAAS, Side.HOST),
            leaf(AppPermissions.SUBSCRIPTIONS_MANAGE, GROUP_SAAS, Side.HOST),
            leaf(AppPermissions.TENANT_FEATURES_MANAGE, GROUP_SAAS, Side.HOST),
            leaf(AppPermissions.BILLING_CREDENTIALS_MANAGE, GROUP_SAAS, Side.HOST));

    private PermissionDefinitions() {
    }

    private static PermissionDefinition group(String name, String parent, Side side) {
        return new PermissionDefinition(name, parent, KEY_PREFIX + name, side);
    }

    private static PermissionDefinition leaf(String name, String parent, Side side) {
        return new PermissionDefinition(name, parent, KEY_PREFIX + name, side);
    }

    public static List<PermissionDefinition> tree() {
        return TREE;
    }

    public static List<PermissionDefinition> roots() {
        List<PermissionDefinition> roots = new ArrayList<>();
        for (PermissionDefinition definition : TREE) {
            if (definition.parent() == null) {
                roots.add(definition);
            }
        }
        return roots;
    }

    public static List<PermissionDefinition> childrenOf(String parentName) {
        List<PermissionDefinition> children = new ArrayList<>();
        for (PermissionDefinition definition : TREE) {
            if (parentName.equals(definition.parent())) {
                children.add(definition);
            }
        }
        return children;
    }

    /** A node is a group when at least one other node declares it as its parent. */
    public static boolean isGroup(String name) {
        for (PermissionDefinition definition : TREE) {
            if (name.equals(definition.parent())) {
                return true;
            }
        }
        return false;
    }

    /** Leaf permission names (grantable), i.e. every node that is not a group. */
    public static Set<String> leafPermissionNames() {
        Set<String> names = new LinkedHashSet<>();
        for (PermissionDefinition definition : TREE) {
            if (!isGroup(definition.name())) {
                names.add(definition.name());
            }
        }
        return names;
    }

    /**
     * Permissions grantable to a TENANT-side static {@code Admin} role: everything in
     * {@link AppPermissions#all()} minus the HOST-only names. The single source for both the
     * startup seeder ({@code DataSeeder}) and the tenant-creation bootstrap
     * ({@code TenantAdminBootstrapper}), so the two recipes can never drift apart — a drift here
     * is invisible to a clean-database suite and surfaces as an unexplained 403 in one path only.
     */
    public static Set<String> tenantAdminPermissionNames() {
        Set<String> names = new LinkedHashSet<>(AppPermissions.all());
        names.removeAll(hostOnlyPermissionNames());
        return names;
    }

    /** Host-only permission names: leaf nodes whose side is {@link Side#HOST}. */
    public static Set<String> hostOnlyPermissionNames() {
        Set<String> names = new LinkedHashSet<>();
        for (PermissionDefinition definition : TREE) {
            if (definition.side() == Side.HOST && !isGroup(definition.name())) {
                names.add(definition.name());
            }
        }
        return names;
    }
}
