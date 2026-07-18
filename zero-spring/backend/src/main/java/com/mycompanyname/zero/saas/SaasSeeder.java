package com.mycompanyname.zero.saas;

import com.mycompanyname.zero.saas.edition.Edition;
import com.mycompanyname.zero.saas.edition.EditionRepository;
import com.mycompanyname.zero.saas.subscription.SubscriptionRepository;
import com.mycompanyname.zero.saas.subscription.SubscriptionService;
import com.mycompanyname.zero.tenancy.Tenant;
import com.mycompanyname.zero.tenancy.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the SaaS baseline: a default {@code Standard} edition plus a subscription for the
 * {@code default} tenant.
 *
 * <p>Deliberately keyed on the <em>edition's</em> existence rather than on the host-admin check that
 * gates identity seeding (F5-R6). Tying it to the identity check would silently skip SaaS seeding on
 * every already-provisioned database, leaving the platform with no sellable package.
 *
 * <p>Lives in the module root package so {@code seed} can call it without reaching into
 * {@code saas.edition} / {@code saas.subscription} internals and breaking the module boundary.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SaasSeeder {

    public static final String DEFAULT_EDITION_NAME = "Standard";
    private static final String DEFAULT_TENANT_NAME = "default";

    /**
     * Must match {@code DataSeeder.SEED_ADVISORY_LOCK_KEY}. Duplicated rather than imported because
     * the module boundary forbids {@code saas -> seed}; the two values are asserted equal by
     * {@code SaasSeederIdempotencyIT}.
     */
    static final long SEED_ADVISORY_LOCK_KEY = 8_274_411_903_551_233_001L;

    private final EditionRepository editionRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionService subscriptionService;
    private final TenantRepository tenantRepository;
    private final JdbcTemplate jdbcTemplate;

    /**
     * <p>PROD-R15a: the "does it already exist?" checks below are read-then-write, so two replicas
     * booting together can both read "absent" and both insert. The advisory lock serialises them.
     * It is transaction-scoped, so PostgreSQL releases it at commit or rollback. When called from
     * {@code DataSeeder} this joins that transaction and re-acquires the same key, which is a no-op
     * for the holding session.
     */
    @Transactional
    public void seedDefaults() {
        jdbcTemplate.query("select pg_advisory_xact_lock(?)", resultSet -> null, SEED_ADVISORY_LOCK_KEY);
        seedStandardEdition();
        seedDefaultTenantSubscription();
    }

    private void seedStandardEdition() {
        if (editionRepository.findByNameIgnoreCase(DEFAULT_EDITION_NAME).isPresent()) {
            return;
        }
        Edition standard = new Edition();
        standard.setName(DEFAULT_EDITION_NAME);
        standard.setDisplayName("Standard");
        standard.setDescription("Default edition every new tenant is provisioned with.");
        // Free by design (no price at all): new tenants land on an ACTIVE, never-expiring subscription
        // instead of being locked out behind PENDING_PAYMENT before any billing provider exists.
        standard.setMonthlyPrice(null);
        standard.setAnnualPrice(null);
        standard.setCurrency(null);
        standard.setTrialDayCount(0);   // a free edition may not offer a trial
        standard.setGraceDayCount(0);
        standard.setActive(true);
        standard.setSortOrder(0);
        editionRepository.save(standard);
        log.info("Seeded default '{}' edition", DEFAULT_EDITION_NAME);
    }

    private void seedDefaultTenantSubscription() {
        Tenant tenant = tenantRepository.findByNameIgnoreCase(DEFAULT_TENANT_NAME).orElse(null);
        if (tenant == null) {
            return; // identity seeding disabled or not run yet; nothing to subscribe
        }
        if (subscriptionRepository.findByTenantId(tenant.getId()).isPresent()) {
            return;
        }
        subscriptionService.provisionDefaultSubscription(tenant.getId());
        log.info("Seeded subscription for the '{}' tenant", DEFAULT_TENANT_NAME);
    }
}
