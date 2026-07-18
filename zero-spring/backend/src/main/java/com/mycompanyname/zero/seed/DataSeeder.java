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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;

@Component
@Profile("!test-noseed")
@Slf4j
public class DataSeeder implements ApplicationRunner {

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_ROLE_NAME = "Admin";
    private static final String DEFAULT_TENANT_NAME = "default";
    private static final String DEV_DEFAULT_PASSWORD = "Admin123!";

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;
    private final SaasSeeder saasSeeder;
    private final boolean seedEnabled;
    private final String hostAdminPassword;

    public DataSeeder(TenantRepository tenantRepository,
                      UserRepository userRepository,
                      RoleRepository roleRepository,
                      PasswordEncoder passwordEncoder,
                      Environment environment,
                      SaasSeeder saasSeeder,
                      @Value("${zero.seed.enabled:true}") boolean seedEnabled,
                      @Value("${zero.seed.host-admin-password:Admin123!}") String hostAdminPassword) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.environment = environment;
        this.saasSeeder = saasSeeder;
        this.seedEnabled = seedEnabled;
        this.hostAdminPassword = hostAdminPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!seedEnabled) {
            log.info("Data seeding skipped (zero.seed.enabled=false)");
            return;
        }
        // Fail fast: seeding in prod with a missing or dev-default admin password would ship a
        // publicly known credential. Either set a strong SEED_ADMIN_PASSWORD or disable seeding.
        if (environment.acceptsProfiles(Profiles.of("prod"))
                && (hostAdminPassword == null
                    || hostAdminPassword.isBlank()
                    || DEV_DEFAULT_PASSWORD.equals(hostAdminPassword))) {
            throw new IllegalStateException(
                    "Seeding is enabled with the 'prod' profile but SEED_ADMIN_PASSWORD is missing, blank, "
                            + "or still the dev default. Set a strong SEED_ADMIN_PASSWORD or disable seeding "
                            + "(SEED_ENABLED=false).");
        }
        TenantContext.clear();
        try {
            seedIdentity();
            // Separate, independently idempotent step: it keys on the static Admin roles' *contents*,
            // not on the host admin above, so an already-provisioned database picks up permissions
            // that were added after it was first seeded.
            reconcileStaticRolePermissions();
            // Separate, independently idempotent step: it keys on the edition's existence, not on the
            // host admin above, so an already-provisioned database still receives the SaaS baseline.
            saasSeeder.seedDefaults();
            log.info("Data seeding completed");
        } finally {
            TenantContext.clear();
        }
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
     * <p>Public so the reconciliation can be exercised on its own (see
     * {@code RolePermissionReconciliationIT}) without re-running the whole {@link ApplicationRunner}.
     */
    @Transactional
    public void reconcileStaticRolePermissions() {
        if (!seedEnabled) {
            log.info("Static role permission reconciliation skipped (zero.seed.enabled=false)");
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
     * settings.host.manage, languages.manage and the whole SaaS group).
     */
    private static Set<String> tenantAdminPermissions() {
        Set<String> permissions = new HashSet<>(AppPermissions.all());
        permissions.removeAll(PermissionDefinitions.hostOnlyPermissionNames());
        return permissions;
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
