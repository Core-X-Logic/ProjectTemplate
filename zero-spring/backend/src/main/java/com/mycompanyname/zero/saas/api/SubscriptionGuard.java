package com.mycompanyname.zero.saas.api;

/**
 * Tells whether a tenant's subscription currently permits access to business endpoints.
 *
 * <p>This interface only defines the check. It is applied in {@code TenantResolverFilter}
 * (403 {@code SUBSCRIPTION_INVALID}) via the {@code TenantAccessCheck} SPI, together with the
 * exempt-path list — see ARCHITECTURE-RULES.md — "Abonelik geçerlilik kapısı filtrede ve cache'li".
 */
public interface SubscriptionGuard {

    /**
     * {@code false} when the tenant's subscription is {@code EXPIRED} or {@code PENDING_PAYMENT}.
     * Host requests ({@code tenantId == null}) and tenants without a subscription are not blocked.
     */
    boolean isTenantSubscriptionValid(Long tenantId);
}
