package com.mycompanyname.zero.seed;

import com.mycompanyname.zero.AbstractIntegrationIT;
import com.mycompanyname.zero.identity.domain.AppPermissions;
import com.mycompanyname.zero.identity.domain.Role;
import com.mycompanyname.zero.identity.repo.RoleRepository;
import com.mycompanyname.zero.identity.repo.UserRepository;
import com.mycompanyname.zero.saas.SaasSeeder;
import com.mycompanyname.zero.tenancy.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Evidence for the permission-reconciliation flag fix (see ARCHITECTURE-RULES.md — "İzin
 * uzlaştırması seed bayrağından bağımsızdır") and for PROD-R2.
 *
 * <p>Both findings are about a flag doing something other than what its name says, and neither is
 * visible to a test that only exercises the wired-up bean: the shared context runs with seeding on
 * and the {@code test} profile active, which is precisely the combination where both defects hide.
 * Each test therefore builds its own {@link DataSeeder} against the real repositories, so the flag
 * combination under test is the one production would run.
 *
 * <p>Every test restores the roles it perturbed, leaving the shared database intact for the other
 * IT classes.
 */
class SeedHardeningIT extends AbstractIntegrationIT {

    private static final String ADMIN_ROLE_NAME = "Admin";
    /** 64+ bytes of entropy, standing in for a real operator-supplied SEED_ADMIN_PASSWORD. */
    private static final String STRONG_PASSWORD = "cJ8#qL2vRt6@Wm9XzB4!nH7pE0sYdA3u";

    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private Environment environment;
    @Autowired
    private SaasSeeder saasSeeder;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private DataSeeder wiredSeeder;

    private TransactionTemplate transactionTemplate;

    @Autowired
    void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void restoreCanonicalPermissions() {
        transactionTemplate.executeWithoutResult(status -> wiredSeeder.reconcileStaticRolePermissions());
    }

    @Test
    void reconciliationRunsWhenSeedingIsDisabled() {
        // The production fix in one assertion. Reconciliation used to be gated on
        // zero.seed.enabled, and prod ships with SEED_ENABLED=false — so the repair for permission
        // drift never ran in the only environment whose database is old enough to have drifted.
        long roleId = hostAdminRoleId();
        mutatePermissions(roleId, permissions -> permissions.remove(AppPermissions.EDITIONS_MANAGE));
        assertThat(permissionsOf(roleId))
                .as("arrange step must actually strip the permission")
                .doesNotContain(AppPermissions.EDITIONS_MANAGE);
        long usersBefore = userRepository.count();

        runSeeder(seederWith(false, true, ""));

        assertThat(permissionsOf(roleId))
                .as("with seeding off and reconciliation on, the drift must still be repaired")
                .containsExactlyInAnyOrderElementsOf(AppPermissions.all());
        assertThat(userRepository.count())
                .as("reconciliation must not seed anything — it repairs roles, it does not provision")
                .isEqualTo(usersBefore);
    }

    @Test
    void aBlankSeedPasswordIsRefusedWithoutTheProdProfile() {
        // PROD-R2. The old guard was conditional on the 'prod' profile, so the one failure mode it
        // existed for — SPRING_PROFILES_ACTIVE unset or misspelled — walked straight past it. This
        // context runs the 'test' profile, so a throw here proves the profile is no longer consulted.
        assertThat(environment.acceptsProfiles(org.springframework.core.env.Profiles.of("prod")))
                .as("this test is meaningless if the prod profile happens to be active")
                .isFalse();

        assertThatThrownBy(() -> runSeeder(seederWith(true, false, "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SEED_ADMIN_PASSWORD");
    }

    @Test
    void theCommittedDevDefaultPasswordIsRefusedOutsideDevAndTest() {
        // The dev default has to keep working on a laptop, so it is accepted on dev/test only. Any
        // other profile set — including none at all — must refuse it.
        DataSeeder seeder = new DataSeeder(tenantRepository, userRepository, roleRepository,
                passwordEncoder, new org.springframework.mock.env.MockEnvironment(), saasSeeder,
                jdbcTemplate, true, false, DataSeeder.DEV_DEFAULT_PASSWORD);

        assertThatThrownBy(() -> runSeeder(seeder))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dev default");
    }

    @Test
    void anOperatorSuppliedPasswordIsAccepted() {
        // Seeding is idempotent, so this is a no-op against the already-provisioned database; the
        // assertion is that the guard does not stand in the way of a correctly configured deployment.
        assertThatCode(() -> runSeeder(seederWith(true, true, STRONG_PASSWORD)))
                .doesNotThrowAnyException();
    }

    @Test
    void reconciliationCanBeTurnedOffOnItsOwnFlag() {
        long roleId = hostAdminRoleId();
        mutatePermissions(roleId, permissions -> permissions.remove(AppPermissions.EDITIONS_MANAGE));

        runSeeder(seederWith(false, false, ""));

        assertThat(permissionsOf(roleId))
                .as("zero.seed.reconcile-permissions=false must genuinely disable the repair, "
                        + "otherwise the flag is decoration")
                .doesNotContain(AppPermissions.EDITIONS_MANAGE);
        // @AfterEach puts the role back.
    }

    // --- helpers ---------------------------------------------------------

    private DataSeeder seederWith(boolean seedEnabled, boolean reconcile, String password) {
        return new DataSeeder(tenantRepository, userRepository, roleRepository, passwordEncoder,
                environment, saasSeeder, jdbcTemplate, seedEnabled, reconcile, password);
    }

    /**
     * A hand-built seeder is not a Spring proxy, so its {@code @Transactional} does nothing and the
     * LAZY permission collections would fail to load. Supplying the transaction here reproduces what
     * the container does for the real bean.
     */
    private void runSeeder(DataSeeder seeder) {
        transactionTemplate.executeWithoutResult(status -> seeder.run(null));
    }

    private long hostAdminRoleId() {
        return roleRepository.findByNameIgnoreCaseAndTenantIdIsNull(ADMIN_ROLE_NAME)
                .orElseThrow(() -> new AssertionError("seeded host Admin role must exist"))
                .getId();
    }

    private void mutatePermissions(long roleId, Consumer<Set<String>> mutation) {
        transactionTemplate.executeWithoutResult(status -> {
            Role role = requireRole(roleId);
            mutation.accept(role.getPermissions());
            roleRepository.save(role);
        });
    }

    private Set<String> permissionsOf(long roleId) {
        return transactionTemplate.execute(status -> new HashSet<>(requireRole(roleId).getPermissions()));
    }

    private Role requireRole(long roleId) {
        return roleRepository.findById(roleId)
                .orElseThrow(() -> new AssertionError("role " + roleId + " must exist"));
    }
}
