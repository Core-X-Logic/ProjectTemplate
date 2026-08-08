package com.mycompanyname.zero.saas;

import java.util.Set;

/**
 * SaaS permission constants. They live in the {@code saas} module (never in {@code identity}) so the
 * module core stays free of an identity dependency — {@code saas} may not depend on
 * {@code identity}. Registration into the permission tree happens on the identity side, which
 * repeats the same string values; {@code SaasPermissionsAlignmentTest} keeps both lists in sync.
 *
 * <p>Every SaaS permission is {@code Side.HOST}: a tenant can never raise its own limits. SaaS
 * entities carry no tenant {@code @Filter}, so a permission wrongly granted here leaks data
 * instead of returning an empty result — see ARCHITECTURE-RULES.md — "Tenant kendi limitini
 * yükseltemez".
 */
public final class SaasPermissions {

    public static final String EDITIONS_READ = "editions.read";
    public static final String EDITIONS_MANAGE = "editions.manage";
    public static final String SUBSCRIPTIONS_READ = "subscriptions.read";
    public static final String SUBSCRIPTIONS_MANAGE = "subscriptions.manage";
    public static final String TENANT_FEATURES_MANAGE = "tenantfeatures.manage";
    /** Managed billing credentials + failover order (ADR-0020): the installation's merchant accounts. */
    public static final String BILLING_CREDENTIALS_MANAGE = "billing.credentials.manage";

    private SaasPermissions() {
    }

    public static Set<String> all() {
        return Set.of(
                EDITIONS_READ,
                EDITIONS_MANAGE,
                SUBSCRIPTIONS_READ,
                SUBSCRIPTIONS_MANAGE,
                TENANT_FEATURES_MANAGE,
                BILLING_CREDENTIALS_MANAGE);
    }
}
