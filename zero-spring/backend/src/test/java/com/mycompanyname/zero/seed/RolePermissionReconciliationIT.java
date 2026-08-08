package com.mycompanyname.zero.seed;

import com.mycompanyname.zero.AbstractIntegrationIT;
import com.mycompanyname.zero.identity.domain.AppPermissions;
import com.mycompanyname.zero.identity.domain.PermissionDefinitions;
import com.mycompanyname.zero.identity.domain.Role;
import com.mycompanyname.zero.identity.repo.RoleRepository;
import com.mycompanyname.zero.tenancy.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the upgrade path that a clean-database test suite structurally cannot see.
 *
 * <p>Identity seeding is keyed on the host admin user's existence, so on an already-provisioned
 * database it short-circuits: permissions introduced by a later release would never reach the
 * static {@code Admin} roles. Testcontainers starts every run against an empty database, where the
 * seed writes the complete set from scratch — a false green. Each test below therefore simulates an
 * upgraded installation by mutating the seeded roles in the database first, then asserts that
 * {@link DataSeeder#reconcileStaticRolePermissions()} repairs exactly that drift.
 *
 * <p>Every test restores the roles to their canonical state (that <em>is</em> what reconciliation
 * does), so the shared Spring context and its seeded data stay intact for the other IT classes.
 */
class RolePermissionReconciliationIT extends AbstractIntegrationIT {

    private static final String ADMIN_ROLE_NAME = "Admin";
    private static final String DEFAULT_TENANT_NAME = "default";

    @Autowired
    private DataSeeder dataSeeder;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Test
    void hostAdminRoleRegainsAPermissionThatIsMissingFromAnAlreadyProvisionedDatabase() {
        long roleId = hostAdminRoleId();
        // Simulates the real defect: the database was seeded before editions.manage existed, so the
        // host Admin role never received it and GET /api/editions answered 403.
        mutatePermissions(roleId, permissions -> permissions.remove(AppPermissions.EDITIONS_MANAGE));
        assertThat(permissionsOf(roleId))
                .as("arrange step must actually strip the permission, otherwise the test proves nothing")
                .doesNotContain(AppPermissions.EDITIONS_MANAGE);

        dataSeeder.reconcileStaticRolePermissions();

        assertThat(permissionsOf(roleId))
                .as("the host static Admin role must carry every declared permission after reconciliation")
                .containsExactlyInAnyOrderElementsOf(AppPermissions.all());
    }

    @Test
    void tenantAdminRoleLosesHostOnlyPermissionsThatLeakedIntoTheDatabase() {
        long roleId = defaultTenantAdminRoleId();
        mutatePermissions(roleId, permissions -> {
            permissions.add(AppPermissions.TENANTS_MANAGE);
            permissions.add(AppPermissions.EDITIONS_MANAGE);
        });
        assertThat(permissionsOf(roleId))
                .as("arrange step must actually grant the host-only permissions")
                .contains(AppPermissions.TENANTS_MANAGE, AppPermissions.EDITIONS_MANAGE);

        dataSeeder.reconcileStaticRolePermissions();

        assertThat(permissionsOf(roleId))
                .as("reconciliation converges on the exact set, it does not merely top the role up")
                .containsExactlyInAnyOrderElementsOf(tenantAdminPermissions());
        assertThat(permissionsOf(roleId))
                .as("a tenant admin must never carry a HOST-ONLY permission")
                .doesNotContainAnyElementsOf(PermissionDefinitions.hostOnlyPermissionNames());
    }

    @Test
    void anOperatorCreatedNonStaticRoleIsNeverRewritten() {
        Set<String> operatorChosen = Set.of(AppPermissions.USERS_READ, AppPermissions.ROLES_READ);
        long roleId = createCustomTenantRole("reconciliation_custom_" + System.nanoTime(), operatorChosen);
        try {
            dataSeeder.reconcileStaticRolePermissions();

            assertThat(permissionsOf(roleId))
                    .as("a role the operator created and tailored by hand must survive untouched")
                    .containsExactlyInAnyOrderElementsOf(operatorChosen);
        } finally {
            asHostDatabase(() -> roleRepository.deleteById(roleId));
        }
    }

    @Test
    void reconciliationIsStableWhenEverythingAlreadyMatches() {
        long hostRoleId = hostAdminRoleId();
        long tenantRoleId = defaultTenantAdminRoleId();
        dataSeeder.reconcileStaticRolePermissions();
        Instant hostUpdatedAt = updatedAtOf(hostRoleId);
        assertThat(hostUpdatedAt)
                .as("auditing stamps updatedAt on insert, so the baseline must be present")
                .isNotNull();

        // A restart on a healthy database must be a no-op: converging, not oscillating.
        dataSeeder.reconcileStaticRolePermissions();

        assertThat(permissionsOf(hostRoleId)).containsExactlyInAnyOrderElementsOf(AppPermissions.all());
        assertThat(permissionsOf(tenantRoleId)).containsExactlyInAnyOrderElementsOf(tenantAdminPermissions());
        assertThat(updatedAtOf(hostRoleId))
                .as("a role whose set already matches must not be re-saved (no update, no audit noise)")
                .isEqualTo(hostUpdatedAt);
    }

    // --- helpers ---------------------------------------------------------

    /**
     * Recomputed from the permission registry rather than hard-coded, so a permission added in a
     * later release is covered by these assertions the moment it is declared.
     */
    private static Set<String> tenantAdminPermissions() {
        Set<String> permissions = new HashSet<>(AppPermissions.all());
        permissions.removeAll(PermissionDefinitions.hostOnlyPermissionNames());
        return permissions;
    }

    // Every helper below reads or writes `roles`, policed since V12. A test thread crosses no
    // @Service boundary, so nothing publishes a context for it and an unwrapped read answers 0 rows.
    // Host is the honest context: reconciliation is a cross-tenant, host-scope operation, and it is
    // exactly what DataSeeder now announces for itself.
    private long hostAdminRoleId() {
        return asHostDatabase(() -> roleRepository.findByNameIgnoreCaseAndTenantIdIsNull(ADMIN_ROLE_NAME)
                .orElseThrow(() -> new AssertionError("seeded host Admin role must exist"))
                .getId());
    }

    private long defaultTenantAdminRoleId() {
        return asHostDatabase(() -> roleRepository
                .findByTenantIdAndNameIgnoreCase(defaultTenantId(), ADMIN_ROLE_NAME)
                .orElseThrow(() -> new AssertionError("seeded default-tenant Admin role must exist"))
                .getId());
    }

    private long defaultTenantId() {
        return tenantRepository.findByNameIgnoreCase(DEFAULT_TENANT_NAME)
                .orElseThrow(() -> new AssertionError("seeded default tenant must exist"))
                .getId();
    }

    private long createCustomTenantRole(String name, Set<String> permissions) {
        return asHostDatabase(() -> {
            Role role = new Role();
            role.setTenantId(defaultTenantId());
            role.setName(name);
            role.setDisplayName(name);
            role.setStatic(false);
            role.setDefault(false);
            role.setPermissions(new HashSet<>(permissions));
            return roleRepository.save(role).getId();
        });
    }

    /** The permission collection is LAZY, so every read and write needs an open transaction. */
    private void mutatePermissions(long roleId, Consumer<Set<String>> mutation) {
        asHostDatabase(() -> {
            Role role = requireRole(roleId);
            mutation.accept(role.getPermissions());
            roleRepository.save(role);
        });
    }

    private Set<String> permissionsOf(long roleId) {
        return asHostDatabase(() -> new HashSet<>(requireRole(roleId).getPermissions()));
    }

    private Instant updatedAtOf(long roleId) {
        return asHostDatabase(() -> requireRole(roleId).getUpdatedAt());
    }

    private Role requireRole(long roleId) {
        return roleRepository.findById(roleId)
                .orElseThrow(() -> new AssertionError("role " + roleId + " must exist"));
    }
}
