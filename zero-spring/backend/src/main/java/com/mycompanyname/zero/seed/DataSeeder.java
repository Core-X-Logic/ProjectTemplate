package com.mycompanyname.zero.seed;

import com.mycompanyname.zero.identity.domain.AppPermissions;
import com.mycompanyname.zero.identity.domain.PermissionDefinitions;
import com.mycompanyname.zero.identity.domain.Role;
import com.mycompanyname.zero.identity.domain.User;
import com.mycompanyname.zero.identity.repo.RoleRepository;
import com.mycompanyname.zero.identity.repo.UserRepository;
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
    private final boolean seedEnabled;
    private final String hostAdminPassword;

    public DataSeeder(TenantRepository tenantRepository,
                      UserRepository userRepository,
                      RoleRepository roleRepository,
                      PasswordEncoder passwordEncoder,
                      Environment environment,
                      @Value("${zero.seed.enabled:true}") boolean seedEnabled,
                      @Value("${zero.seed.host-admin-password:Admin123!}") String hostAdminPassword) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.environment = environment;
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
            if (userRepository.findByUsernameIgnoreCaseAndTenantIdIsNull(ADMIN_USERNAME).isPresent()) {
                log.info("Seed data already present, skipping");
                return;
            }
            seedHost();
            seedDefaultTenant();
            log.info("Data seeding completed");
        } finally {
            TenantContext.clear();
        }
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
            // Tenant Admin gets every permission except the HOST-ONLY ones
            // (tenants.manage, settings.host.manage, languages.manage).
            Set<String> tenantPermissions = new HashSet<>(AppPermissions.all());
            tenantPermissions.removeAll(PermissionDefinitions.hostOnlyPermissionNames());

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
