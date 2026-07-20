package com.mycompanyname.zero.identity.bootstrap;

import com.mycompanyname.zero.tenancy.TenantCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bootstraps the Admin role and admin user for every newly created tenant (Issue #1).
 *
 * <p>Deliberately a plain synchronous {@code @EventListener} rather than
 * {@code @ApplicationModuleListener}, for the same reason as the saas module's
 * {@code TenantLifecycleListener}: tenant and admin must be created in a <em>single</em>
 * transaction. Splitting them into two units of work admits exactly the "tenant exists but nobody
 * can log in" state this listener exists to abolish. {@code Propagation.MANDATORY} turns a missing
 * transaction into a loud failure instead of a silent split.
 *
 * <p>The event flows {@code tenancy -> identity} only, so {@code tenancy} stays a leaf module —
 * see ARCHITECTURE-RULES.md — "Modül bağımlılıkları döngü kurmaz".
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TenantAdminBootstrapListener {

    private final TenantAdminBootstrapper bootstrapper;

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onTenantCreated(TenantCreatedEvent event) {
        bootstrapper.bootstrapAdmin(event.tenantId(), event.adminEmail(),
                event.adminPassword(), event.adminPasswordGenerated());
        log.debug("Bootstrapped admin for tenant {}", event.tenantId());
    }
}
