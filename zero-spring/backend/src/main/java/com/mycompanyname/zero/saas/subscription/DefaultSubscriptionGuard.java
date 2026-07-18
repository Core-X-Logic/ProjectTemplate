package com.mycompanyname.zero.saas.subscription;

import com.mycompanyname.zero.saas.api.SubscriptionGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link SubscriptionGuard}: a tenant is blocked only while its subscription is
 * {@code EXPIRED} or {@code PENDING_PAYMENT}. {@code CANCELLED} still grants access until the end of
 * the paid period, which is why cancellation preserves {@code currentPeriodEndAt}.
 *
 * <p>Slice A only provides the answer; enforcing it in {@code TenantResolverFilter} (403
 * {@code SUBSCRIPTION_INVALID}) together with the exempt-path list is Slice B.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefaultSubscriptionGuard implements SubscriptionGuard {

    private final SubscriptionRepository subscriptionRepository;

    @Override
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
