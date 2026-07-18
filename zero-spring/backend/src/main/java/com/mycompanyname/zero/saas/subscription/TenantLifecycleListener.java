package com.mycompanyname.zero.saas.subscription;

import com.mycompanyname.zero.tenancy.TenantCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provisions the default subscription for every newly created tenant (F5-ARCHITECTURE §7).
 *
 * <p>Deliberately a plain synchronous {@code @EventListener} rather than
 * {@code @ApplicationModuleListener}: the contract requires tenant and subscription to be created in
 * a <em>single</em> transaction, so the source system's split two-unit-of-work provisioning (and its
 * "tenant exists but has no subscription" failure mode) cannot reappear.
 *
 * <p>The event flows {@code tenancy -> saas} only, so {@code tenancy} stays a leaf module (F5-R1).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TenantLifecycleListener {

    private final SubscriptionService subscriptionService;

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onTenantCreated(TenantCreatedEvent event) {
        subscriptionService.provisionDefaultSubscription(event.tenantId());
        log.debug("Provisioned default subscription for tenant {}", event.tenantId());
    }
}
