package com.mycompanyname.zero.saas;

import com.mycompanyname.zero.AbstractIntegrationIT;
import com.mycompanyname.zero.seed.DataSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Evidence for PROD-R15b.
 *
 * <p>{@code SaasSeeder} runs on every boot and its steps are read-then-write ("is there a Standard
 * edition? no — create one"). Nothing tested that running it twice produces one edition rather than
 * two, which is exactly what a rolling restart or a second replica does. A duplicate {@code Standard}
 * edition is not cosmetic: {@code findByNameIgnoreCase} would then return an arbitrary one of them,
 * so which feature set a tenant resolves against becomes a coin flip.
 */
class SaasSeederIdempotencyIT extends AbstractIntegrationIT {

    private static final String DEFAULT_TENANT_NAME = "default";

    @Autowired
    private SaasSeeder saasSeeder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void seedingTwiceLeavesExactlyOneEditionAndOneSubscription() {
        // The context has already seeded once during startup; this is the second and third pass.
        saasSeeder.seedDefaults();
        saasSeeder.seedDefaults();

        assertThat(countEditionsNamed(SaasSeeder.DEFAULT_EDITION_NAME))
                .as("a duplicate '%s' edition makes feature resolution non-deterministic",
                        SaasSeeder.DEFAULT_EDITION_NAME)
                .isEqualTo(1);
        assertThat(countSubscriptionsForDefaultTenant())
                .as("the default tenant must hold exactly one subscription")
                .isEqualTo(1);
    }

    @Test
    void theSeedersShareOneAdvisoryLockKey() {
        // PROD-R15a. The key is duplicated rather than imported because the module boundary forbids
        // saas -> seed; if the two ever drift apart, identity and SaaS provisioning stop excluding
        // each other and the race the lock exists to prevent quietly returns.
        assertThat(SaasSeeder.SEED_ADVISORY_LOCK_KEY).isEqualTo(DataSeeder.SEED_ADVISORY_LOCK_KEY);
    }

    private int countEditionsNamed(String name) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from editions where lower(name) = lower(?)", Integer.class, name);
        return count == null ? 0 : count;
    }

    private int countSubscriptionsForDefaultTenant() {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from subscriptions s
                join tenants t on t.id = s.tenant_id
                where lower(t.name) = lower(?)
                """, Integer.class, DEFAULT_TENANT_NAME);
        return count == null ? 0 : count;
    }
}
