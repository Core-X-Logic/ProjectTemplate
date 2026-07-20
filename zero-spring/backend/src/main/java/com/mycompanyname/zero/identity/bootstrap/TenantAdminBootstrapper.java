package com.mycompanyname.zero.identity.bootstrap;

import com.mycompanyname.zero.identity.domain.PermissionDefinitions;
import com.mycompanyname.zero.identity.domain.Role;
import com.mycompanyname.zero.identity.domain.User;
import com.mycompanyname.zero.identity.password.PasswordHistoryService;
import com.mycompanyname.zero.identity.password.PasswordPolicyValidator;
import com.mycompanyname.zero.identity.repo.RoleRepository;
import com.mycompanyname.zero.identity.repo.UserRepository;
import com.mycompanyname.zero.shared.tenant.TenantContext;
import com.mycompanyname.zero.tenancy.HibernateTenantFilterAspect;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.HashSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Session;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provisions the static {@code Admin} role and the {@code admin} user of a tenant (Issue #1: a
 * freshly created tenant used to have nobody who could ever log in). Runtime seeding only — the
 * existing schema carries everything needed; no migration is involved.
 *
 * <p><b>Idempotent by lookup-first.</b> Both the role and the user are looked up before being
 * created, so a second bootstrap of the same tenant — a replayed event, a future repair path —
 * creates nothing and, critically, never touches an existing admin's password. The
 * {@code uq_roles_tenant_name} / {@code uq_users_tenant_username_live} unique constraints back the
 * lookups up against races.
 *
 * <p><b>Cross-tenant write mechanics (the tenancy trap).</b> This runs inside the HOST caller's
 * request, where {@code HibernateTenantFilterAspect} has armed the {@code hostFilter}
 * ({@code tenant_id is null}) on the shared Session. Left in place, that filter silently ANDs
 * itself onto the tenant-scoped lookups below, which then find nothing — the first bootstrap would
 * work by accident and every rerun would die on the unique constraint instead of no-opping. So the
 * session filters are disabled per call, exactly the way {@code AccountService} does for its
 * cross-tenant flows, and scoping relies on the explicit {@code tenant_id}-qualified queries.
 * {@link TenantContext} is switched to the new tenant for the duration of the writes (and restored
 * in a {@code finally}) so entity-change auditing attributes the rows to the tenant that owns
 * them, matching what {@code DataSeeder} does for the default tenant.
 *
 * <p><b>The raw password never leaves this method.</b> It is encoded immediately; nothing here
 * logs it, and the recorded password history stores the hash only.
 *
 * <p>{@code shouldChangePassword} is set because the initial credential is by definition known to
 * the host operator (it was chosen by them, or generated and shown to them once), so the tenant
 * admin must rotate it on first login.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TenantAdminBootstrapper {

    static final String ADMIN_USERNAME = "admin";
    static final String ADMIN_ROLE_NAME = "Admin";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicyValidator passwordPolicyValidator;
    private final PasswordHistoryService passwordHistoryService;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * @param passwordGenerated when {@code false} the password was chosen by the caller and is
     *        validated against the tenant's resolved password policy (a fresh tenant resolves to
     *        the application-level policy); a generated one is strong by construction and skips
     *        the check, so a policy stricter than the generator cannot make every password-less
     *        tenant creation fail opaquely
     */
    @Transactional
    public void bootstrapAdmin(Long tenantId, String adminEmail, String rawPassword, boolean passwordGenerated) {
        if (!passwordGenerated) {
            passwordPolicyValidator.validate(
                    passwordPolicyValidator.resolvePolicy(tenantId, null), rawPassword);
        }
        Session session = entityManager.unwrap(Session.class);
        disableTenantFilters(session);
        Long previousTenantId = TenantContext.getTenantId();
        TenantContext.setTenantId(tenantId);
        try {
            Role adminRole = roleRepository.findByTenantIdAndNameIgnoreCase(tenantId, ADMIN_ROLE_NAME)
                    .orElseGet(() -> createAdminRole(tenantId));
            boolean userCreated = false;
            User admin = userRepository.findByTenantIdAndUsernameIgnoreCase(tenantId, ADMIN_USERNAME)
                    .orElse(null);
            if (admin == null) {
                admin = createAdminUser(tenantId, adminEmail, rawPassword, adminRole);
                userCreated = true;
            } else if (admin.getRoles().stream().noneMatch(role -> role.getId().equals(adminRole.getId()))) {
                // Repair path: the user survived but lost (or never got) the Admin role.
                admin.getRoles().add(adminRole);
                userRepository.save(admin);
            }
            log.info("Bootstrapped tenant admin (tenantId={}, userCreated={})", tenantId, userCreated);
        } finally {
            if (previousTenantId != null) {
                TenantContext.setTenantId(previousTenantId);
            } else {
                TenantContext.clear();
            }
            // Re-arm the session for the RESTORED context, mirroring the aspect. Without this a
            // nested @Service call made above (password history) leaves tenantFilter(newTenantId)
            // enabled on the shared session, and the aspect never disables the opposite filter —
            // the stale filter would silently ride along into whatever else this transaction runs
            // (the saas listener provisioning the default subscription, for one).
            disableTenantFilters(session);
            if (previousTenantId != null) {
                session.enableFilter(HibernateTenantFilterAspect.TENANT_FILTER)
                        .setParameter("tenantId", previousTenantId);
            } else {
                session.enableFilter(HibernateTenantFilterAspect.HOST_FILTER);
            }
        }
    }

    private Role createAdminRole(Long tenantId) {
        Role role = new Role();
        role.setTenantId(tenantId);
        role.setName(ADMIN_ROLE_NAME);
        role.setDisplayName(ADMIN_ROLE_NAME);
        // Static, like the seeded Admin roles: DataSeeder's startup reconciliation rewrites static
        // Admin roles to the current tenant-side permission set, so bootstrapped tenants pick up
        // permissions added by later releases too.
        role.setStatic(true);
        role.setDefault(false);
        role.setPermissions(new HashSet<>(PermissionDefinitions.tenantAdminPermissionNames()));
        return roleRepository.save(role);
    }

    private User createAdminUser(Long tenantId, String adminEmail, String rawPassword, Role adminRole) {
        User admin = new User();
        admin.setTenantId(tenantId);
        admin.setUsername(ADMIN_USERNAME);
        admin.setEmail(adminEmail);
        admin.setPasswordHash(passwordEncoder.encode(rawPassword));
        admin.setActive(true);
        admin.setShouldChangePassword(true);
        admin.getRoles().add(adminRole);
        User saved = userRepository.save(admin);
        // The initial password counts toward the reuse window, same as UserService.createUser.
        passwordHistoryService.record(saved.getId(), saved.getPasswordHash());
        return saved;
    }

    /**
     * Disables the tenant/host Hibernate filters the tenancy aspect armed for the calling
     * {@code @Service} chain; see the class javadoc. The caller re-arms them for the restored
     * context on the way out.
     */
    private void disableTenantFilters(Session session) {
        session.disableFilter(HibernateTenantFilterAspect.TENANT_FILTER);
        session.disableFilter(HibernateTenantFilterAspect.HOST_FILTER);
    }
}
