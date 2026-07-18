package com.mycompanyname.zero.tenancy;

import java.util.Optional;

/**
 * Extension point that lets another module veto a tenant-scoped request at the very edge of the
 * stack, inside {@link TenantResolverFilter}.
 *
 * <p><b>Why an SPI instead of a direct call.</b> F5-ARCHITECTURE §7.1 puts the subscription validity
 * gate into {@code TenantResolverFilter}, while the common rules of CONTRACT-phase5 forbid a
 * {@code tenancy -> saas} dependency ({@code tenancy} must stay a leaf module). Inverting the
 * dependency solves both: {@code tenancy} owns this interface and {@code saas} implements it
 * ({@code SubscriptionAccessCheck}), so the arrow points {@code saas -> tenancy}, which is allowed.
 *
 * <p>Implementations run <em>before</em> authentication and on every request that carries a resolved
 * tenant, so they must be cheap — the SaaS implementation is backed by a cache.
 */
@FunctionalInterface
public interface TenantAccessCheck {

    /**
     * @param tenantId    the tenant resolved from the request header; never {@code null} here
     * @param requestPath the servlet path of the request, context path already stripped
     * @return empty when the request may proceed, otherwise the reason it is refused with 403
     */
    Optional<String> denyReason(Long tenantId, String requestPath);
}
