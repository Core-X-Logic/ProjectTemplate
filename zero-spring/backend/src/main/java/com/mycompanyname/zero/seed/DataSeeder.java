package com.mycompanyname.zero.seed;

import com.mycompanyname.zero.identity.domain.AppPermissions;
import com.mycompanyname.zero.identity.domain.PermissionDefinitions;
import com.mycompanyname.zero.identity.domain.Role;
import com.mycompanyname.zero.identity.domain.User;
import com.mycompanyname.zero.identity.repo.RoleRepository;
import com.mycompanyname.zero.identity.repo.UserRepository;
import com.mycompanyname.zero.saas.SaasSeeder;
import com.mycompanyname.zero.shared.tenant.TenantContext;
import com.mycompanyname.zero.tenancy.Tenant;
import com.mycompanyname.zero.tenancy.TenantRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;

@Component
@Profile("!test-noseed")
@Slf4j
public class DataSeeder implements ApplicationRunner {

    /**
     * Advisory lock key that serialises startup provisioning across replicas (PROD-R15a). Any
     * arbitrary but stable 64-bit constant works; it is shared with {@link SaasSeeder} so identity
     * and SaaS provisioning cannot interleave.
     */
    public static final long SEED_ADVISORY_LOCK_KEY = 8_274_411_903_551_233_001L;

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_ROLE_NAME = "Admin";
    private static final String DEFAULT_TENANT_NAME = "default";

    /** The credential that used to be the committed default for {@code SEED_ADMIN_PASSWORD}. */
    static final String DEV_DEFAULT_PASSWORD = "Admin123!";

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;
    private final SaasSeeder saasSeeder;
    private final JdbcTemplate jdbcTemplate;
    private final boolean seedEnabled;
    private final boolean reconcilePermissions;
    private final String hostAdminPassword;

    public DataSeeder(TenantRepository tenantRepository,
                      UserRepository userRepository,
                      RoleRepository roleRepository,
                      PasswordEncoder passwordEncoder,
                      Environment environment,
                      SaasSeeder saasSeeder,
                      JdbcTemplate jdbcTemplate,
                      // B4: the fallback is `false` to match application.yml. A `true` here would
                      // quietly restore the profile-escape even after the YAML was fixed, since a
                      // deployment that overrides the property away entirely lands on this value.
                      @Value("${zero.seed.enabled:false}") boolean seedEnabled,
                      @Value("${zero.seed.reconcile-permissions:true}") boolean reconcilePermissions,
                      @Value("${zero.seed.host-admin-password:}") String hostAdminPassword) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.environment = environment;
        this.saasSeeder = saasSeeder;
        this.jdbcTemplate = jdbcTemplate;
        this.seedEnabled = seedEnabled;
        this.reconcilePermissions = reconcilePermissions;
        this.hostAdminPassword = hostAdminPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (seedEnabled) {
            requireUsableHostAdminPassword();
        }
        // PROD-R15a: replicas boot simultaneously and each one runs this. Without serialisation two
        // nodes can both pass the "does the admin exist?" check and race into the insert, where one
        // dies on the unique constraint and takes the application down with it. The advisory lock is
        // transaction-scoped, so it is released by the commit or rollback below — no cleanup path can
        // leak it, unlike a row-based flag.
        acquireSeedLock();
        TenantContext.clear();
        try {
            if (seedEnabled) {
                seedIdentity();
            } else {
                log.info("Data seeding skipped (zero.seed.enabled=false)");
            }
            // Independent of seeding on purpose: keyed on the static Admin roles' *contents*,
            // not on the host admin, so an already-provisioned database picks up permissions added by
            // a later release. Prod runs with seeding off, which is exactly where that drift appears.
            reconcileStaticRolePermissions();
            if (seedEnabled) {
                // Separate, independently idempotent step: it keys on the edition's existence, not on
                // the host admin above, so an already-provisioned database still receives the SaaS
                // baseline.
                saasSeeder.seedDefaults();
                log.info("Data seeding completed");
            }
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * PROD-R2. This check used to be conditional on the {@code prod} profile, which meant the one
     * failure mode it was written for — {@code SPRING_PROFILES_ACTIVE} unset or misspelled, so the
     * base config's defaults apply — slipped straight past it and seeded a host admin whose password
     * is published in this repository. The profile is no longer consulted: a blank or dev-default
     * password is refused everywhere. Dev and test supply {@code Admin123!} explicitly through their
     * own profile configuration, which is what keeps them working.
     */
    private void requireUsableHostAdminPassword() {
        boolean unusable = hostAdminPassword == null
                || hostAdminPassword.isBlank()
                || DEV_DEFAULT_PASSWORD.equals(hostAdminPassword);
        if (!unusable) {
            return;
        }
        if (environment.acceptsProfiles(Profiles.of("dev", "test"))
                && DEV_DEFAULT_PASSWORD.equals(hostAdminPassword)) {
            return;
        }
        throw new IllegalStateException(
                "Seeding is enabled but zero.seed.host-admin-password is missing, blank, or still the "
                        + "dev default. Set a strong SEED_ADMIN_PASSWORD or disable seeding "
                        + "(SEED_ENABLED=false).");
    }

    /**
     * Blocks until any other replica's seeding transaction has committed. Held for the remainder of
     * this transaction; PostgreSQL releases it automatically at commit/rollback.
     */
    private void acquireSeedLock() {
        jdbcTemplate.query("select pg_advisory_xact_lock(?)", resultSet -> null, SEED_ADVISORY_LOCK_KEY);
    }

    /**
     * Reconciles the permission set of the STATIC {@code Admin} roles on every startup.
     *
     * <p>Why this cannot live inside {@link #seedIdentity()}: that step is keyed on the host admin
     * user's existence, so on an already-provisioned database it short-circuits and permissions
     * introduced by a later release never reach the roles that are supposed to carry all of them.
     * Observed in production after the SaaS permissions landed: the host admin held 17 of 22
     * permissions and {@code GET /api/editions} answered 403. A clean-database test suite cannot see
     * this, because there the seed runs from scratch and writes the complete set.
     *
     * <p>Scope and safety:
     * <ul>
     *   <li>Host static {@code Admin} → exactly {@link AppPermissions#all()}.</li>
     *   <li>Every tenant's static {@code Admin} → {@code all()} minus the HOST-ONLY permissions, so a
     *       host-only permission that leaked into a tenant role is revoked, not just topped up.</li>
     *   <li>Roles with {@code isStatic = false} — anything an operator created or tailored by hand —
     *       are never read for reconciliation and never rewritten.</li>
     *   <li>A role whose set already matches is left untouched, so an ordinary restart issues no
     *       UPDATE and produces no audit noise.</li>
     * </ul>
     *
     * <p><b>Why its own flag.</b> This used to be gated on
     * {@code zero.seed.enabled}. Prod ships with seeding off — that is the whole point of
     * {@code SEED_ENABLED=false} — so reconciliation never ran in the one environment whose database
     * is old enough to have drifted, while every clean-database test suite passed. It is now gated on
     * {@code zero.seed.reconcile-permissions} (default true, prod included), because reconciling an
     * existing role's permissions is not seeding: it creates nothing and touches only roles marked
     * static. See ARCHITECTURE-RULES.md — "İzin uzlaştırması seed bayrağından bağımsızdır".
     *
     * <p>Public so the reconciliation can be exercised on its own (see
     * {@code RolePermissionReconciliationIT}) without re-running the whole {@link ApplicationRunner}.
     */
    @Transactional
    public void reconcileStaticRolePermissions() {
        if (!reconcilePermissions) {
            log.info("Static role permission reconciliation skipped (zero.seed.reconcile-permissions=false)");
            return;
        }
        TenantContext.clear();
        int updated;
        try {
            updated = reconcileHostAdminRole() + reconcileTenantAdminRoles();
        } finally {
            TenantContext.clear();
        }
        if (updated > 0) {
            log.info("Reconciled the permission set of {} static '{}' role(s)", updated, ADMIN_ROLE_NAME);
        }
    }

    private int reconcileHostAdminRole() {
        return roleRepository.findByNameIgnoreCaseAndTenantIdIsNull(ADMIN_ROLE_NAME)
                .map(role -> applyPermissions(role, AppPermissions.all()))
                .orElse(0);
    }

    private int reconcileTenantAdminRoles() {
        Set<String> tenantPermissions = tenantAdminPermissions();
        int updated = 0;
        for (Tenant tenant : tenantRepository.findAll()) {
            TenantContext.setTenantId(tenant.getId());
            try {
                updated += roleRepository.findByTenantIdAndNameIgnoreCase(tenant.getId(), ADMIN_ROLE_NAME)
                        .map(role -> applyPermissions(role, tenantPermissions))
                        .orElse(0);
            } finally {
                TenantContext.clear();
            }
        }
        return updated;
    }

    /**
     * Rewrites a role's permission set to {@code desired}, but only when the role is static AND the
     * set actually differs. Returns the number of roles written (0 or 1) so the caller can log a
     * single summary line instead of one per role.
     */
    private int applyPermissions(Role role, Set<String> desired) {
        if (!role.isStatic()) {
            return 0;
        }
        Set<String> current = role.getPermissions();
        if (current.size() == desired.size() && current.containsAll(desired)) {
            return 0;
        }
        current.clear();
        current.addAll(desired);
        roleRepository.save(role);
        log.info("Static role '{}' (tenantId={}) reconciled to {} permission(s)",
                role.getName(), role.getTenantId(), desired.size());
        return 1;
    }

    /**
     * Tenant Admin gets every permission except the HOST-ONLY ones (tenants.manage,
     * settings.host.manage, languages.manage and the whole SaaS group). Delegates to
     * {@link PermissionDefinitions#tenantAdminPermissionNames()} — the same recipe the
     * tenant-creation bootstrap uses — so seeded and bootstrapped Admin roles cannot drift.
     */
    private static Set<String> tenantAdminPermissions() {
        return new HashSet<>(PermissionDefinitions.tenantAdminPermissionNames());
    }

    private void seedIdentity() {
        if (userRepository.findByUsernameIgnoreCaseAndTenantIdIsNull(ADMIN_USERNAME).isPresent()) {
            log.info("Identity seed data already present, skipping");
            return;
        }
        seedHost();
        seedDefaultTenant();
    }

    private void seedHost() {
        Role hostAdminRole = roleRepository.findByNameIgnoreCaseAndTenantIdIsNull(ADMIN_ROLE_NAME)
                .orElseGet(() -> {
                    Role role = new Role();
                    role.setTenantId(null);
                    role.setName(ADMIN_ROLE_NAME);
                    role.setDisplayName(ADMIN_ROLE_NAME);
                    role.setStatic(true);
                    role.setDefault(false);
                    role.setPermissions(new HashSet<>(AppPermissions.all()));
                    return roleRepository.save(role);
                });

        User hostAdmin = new User();
        hostAdmin.setTenantId(null);
        hostAdmin.setUsername(ADMIN_USERNAME);
        hostAdmin.setEmail("admin@host.local");
        hostAdmin.setPasswordHash(passwordEncoder.encode(hostAdminPassword));
        hostAdmin.setActive(true);
        hostAdmin.getRoles().add(hostAdminRole);
        userRepository.save(hostAdmin);
        log.info("Seeded host admin user");
    }

    private void seedDefaultTenant() {
        Tenant tenant = tenantRepository.findByNameIgnoreCase(DEFAULT_TENANT_NAME)
                .orElseGet(() -> {
                    Tenant created = new Tenant();
                    created.setName(DEFAULT_TENANT_NAME);
                    created.setDisplayName("Default");
                    created.setActive(true);
                    return tenantRepository.save(created);
                });

        TenantContext.setTenantId(tenant.getId());
        try {
            Set<String> tenantPermissions = tenantAdminPermissions();

            Role tenantAdminRole = roleRepository.findByTenantIdAndNameIgnoreCase(tenant.getId(), ADMIN_ROLE_NAME)
                    .orElseGet(() -> {
                        Role role = new Role();
                        role.setTenantId(tenant.getId());
                        role.setName(ADMIN_ROLE_NAME);
                        role.setDisplayName(ADMIN_ROLE_NAME);
                        role.setStatic(true);
                        role.setDefault(false);
                        role.setPermissions(tenantPermissions);
                        return roleRepository.save(role);
                    });

            if (userRepository.findByTenantIdAndUsernameIgnoreCase(tenant.getId(), ADMIN_USERNAME).isEmpty()) {
                User tenantAdmin = new User();
                tenantAdmin.setTenantId(tenant.getId());
                tenantAdmin.setUsername(ADMIN_USERNAME);
                tenantAdmin.setEmail("admin@default.local");
                tenantAdmin.setPasswordHash(passwordEncoder.encode(hostAdminPassword));
                tenantAdmin.setActive(true);
                tenantAdmin.getRoles().add(tenantAdminRole);
                userRepository.save(tenantAdmin);
                log.info("Seeded default tenant admin user");
            }
        } finally {
            TenantContext.clear();
        }
    }
}
