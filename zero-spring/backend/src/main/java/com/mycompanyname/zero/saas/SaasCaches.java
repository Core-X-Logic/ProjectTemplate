package com.mycompanyname.zero.saas;

/**
 * Names of the caches the SaaS module reads through. They live in the module's base package (which
 * is exposed) so {@code CacheConfig} can configure them without the {@code saas} module having to
 * depend on {@code config} — the dependency direction stays {@code config -> saas}.
 *
 * <p>Both caches are invalidated eagerly rather than expired: every write that can change a resolved
 * feature value (edition feature, tenant override, package assignment) or a subscription status
 * evicts them in full (F5-R2). The TTL configured for Redis is only a safety net.
 */
public final class SaasCaches {

    /**
     * Resolved feature values, entry key {@code feature:{tenantId}:{featureName}}
     * ({@code host} instead of the id for host-scope resolution).
     */
    public static final String FEATURES = "features";

    /** Subscription validity per tenant, entry key {@code tenantId} (F5-ARCHITECTURE §7.1). */
    public static final String SUBSCRIPTION_VALIDITY = "subscription-validity";

    private SaasCaches() {
    }
}
