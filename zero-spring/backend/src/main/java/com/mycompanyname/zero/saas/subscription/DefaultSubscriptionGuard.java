package com.mycompanyname.zero.saas.subscription;

import com.mycompanyname.zero.saas.SaasCaches;
import com.mycompanyname.zero.saas.api.SubscriptionGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/**
 * Default {@link SubscriptionGuard}: a tenant is blocked only while its subscription is
 * {@code EXPIRED} or {@code PENDING_PAYMENT}. {@code CANCELLED} still grants access until the end of
 * the paid period, which is why cancellation preserves {@code currentPeriodEndAt}.
 *
 * <p>The answer is cached per tenant because {@code TenantResolverFilter} asks on every
 * tenant-scoped request. Every subscription mutation in
 * {@code SubscriptionService} — including the ones the lifecycle job drives — evicts the cache, so a
 * status change takes effect on the very next request.
 *
 * <p>Deliberately a plain {@code @Component} without {@code @Transactional}: keeping the caching
 * interceptor outermost means a cache hit costs neither a transaction nor a Hibernate filter setup
 * on the request hot path.
 */
@Component
@RequiredArgsConstructor
public class DefaultSubscriptionGuard implements SubscriptionGuard {

    private final SubscriptionRepository subscriptionRepository;

    @Override
    @Cacheable(cacheNames = SaasCaches.SUBSCRIPTION_VALIDITY, key = "#tenantId", condition = "#tenantId != null")
    public boolean isTenantSubscriptionValid(Long tenantId) {
        if (tenantId == null) {
            return true; // host requests are never subject to a subscription
        }
        return subscriptionRepository.findByTenantId(tenantId)
                .map(subscription -> subscription.getStatus().grantsAccess())
                // A tenant without a subscription is not blocked: provisioning may legitimately be
                // pending, and locking it out would make the platform unusable after a failed seed.
                .orElse(true);
    }
}
