package com.mycompanyname.zero.saas.subscription;

import com.mycompanyname.zero.tenancy.TenantCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provisions the default subscription for every newly created tenant.
 *
 * <p>Deliberately a plain synchronous {@code @EventListener} rather than
 * {@code @ApplicationModuleListener}: tenant and subscription must be created in a <em>single</em>
 * transaction. Splitting them into two units of work admits a "tenant exists but has no
 * subscription" state that nothing later repairs.
 *
 * <p>The event flows {@code tenancy -> saas} only, so {@code tenancy} stays a leaf module — see
 * ARCHITECTURE-RULES.md — "Modül bağımlılıkları döngü kurmaz".
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
