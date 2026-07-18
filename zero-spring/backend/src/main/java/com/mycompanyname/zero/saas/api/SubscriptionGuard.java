package com.mycompanyname.zero.saas.api;

/**
 * Tells whether a tenant's subscription currently permits access to business endpoints.
 *
 * <p>Slice A only defines and implements the check; wiring it into {@code TenantResolverFilter}
 * (403 {@code SUBSCRIPTION_INVALID}) together with the exempt-path list is Slice B scope
 * (F5-ARCHITECTURE §7.1).
 */
public interface SubscriptionGuard {

    /**
     * {@code false} when the tenant's subscription is {@code EXPIRED} or {@code PENDING_PAYMENT}.
     * Host requests ({@code tenantId == null}) and tenants without a subscription are not blocked.
     */
    boolean isTenantSubscriptionValid(Long tenantId);
}
