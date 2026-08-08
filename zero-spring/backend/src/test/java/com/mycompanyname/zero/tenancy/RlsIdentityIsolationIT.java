package com.mycompanyname.zero.tenancy;

import com.mycompanyname.zero.AbstractIntegrationIT;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The evidence for the identity group's row-level policies ({@code users}, {@code roles},
 * {@code organization_units}). Migration {@code V12__rls_identity.sql}; host branch
 * {@code ADR-0018}, exemption set {@code ADR-0019}.
 *
 * <p><b>What this group demands beyond the plain policy template.</b> Two claims exist here that
 * the audit/notification group does not have:
 *
 * <ul>
 *   <li><b>Host-global rows are real.</b> {@code users} and {@code roles} carry rows with
 *       {@code tenant_id IS NULL} (the host operator and the host {@code Admin} role, written by
 *       {@code DataSeeder}). The {@code tenant_id = <setting>} branch can never return them —
 *       {@code NULL = 5} is NULL, not true — so their visibility is decided by two separate
 *       branches that must be asserted separately, and in three different contexts (host, an
 *       established tenant, and no context at all).</li>
 *   <li><b>RLS changes behaviour the Hibernate filter never did.</b> A Hibernate {@code @Filter} is
 *       not applied to {@code EntityManager.find()}, so {@code userRepository.findById(...)} has
 *       always crossed tenant boundaries. Row security has no such exemption. That is why
 *       {@code ImpersonationService.backToImpersonator()} — which reads the HOST actor by primary
 *       key while running in the impersonated TENANT's context — is the measured reason the
 *       host-global read branch exists. {@code ImpersonationIT} is the end-to-end proof; the
 *       assertions here are the same claim at the level where the policy decides it.</li>
 * </ul>
 *
 * <p><b>What is deliberately asymmetric, and why the test says so twice.</b> The host-global branch
 * is in {@code USING} only. Reading a host row from a tenant context is a measured requirement;
 * WRITING one from a tenant context would let a tenant mint its own host operator, which is an
 * escalation that skips the permission layer entirely. Both halves are asserted — the read that must
 * work and the write that must not — because a policy that only ever got the read right would look
 * identical in every positive test.
 *
 * <p>Mechanics: the same statement the aspect issues is used to bind the settings (so a renamed GUC
 * breaks this test too), each refused write gets its own transaction, and the fixture's own inserts
 * are the positive control for {@code WITH CHECK} — a policy that were too STRICT would fail there,
 * loudly, instead of quietly making assertions pass.
 */
class RlsIdentityIsolationIT extends AbstractIntegrationIT {

    /** The group V12 covers. Every visibility claim below is made about all three. */
    private static final List<String> GROUP_TABLES =
            List.of("users", "roles", "organization_units");

    /** Asserting on this text is what makes a refusal the POLICY's, not a NOT NULL violation's. */
    private static final String POLICY_VIOLATION = "row-level security policy";

    private static final String BIND_SETTINGS_SQL =
            "select set_config('" + HibernateTenantFilterAspect.CURRENT_TENANT_SETTING + "', ?, true),"
                    + " set_config('" + HibernateTenantFilterAspect.IS_HOST_SETTING + "', ?, true)";

    private static final ResultSetExtractor<Void> DISCARD_RESULT = resultSet -> null;

    private static final String INSERT_USER = """
            insert into users (tenant_id, username, email, password_hash, active)
            values (?, ?, ?, 'not-a-real-hash', true)
            returning id""";

    /**
     * {@code tenant_id} is written into the statement as a literal {@code null} instead of being bound
     * as a parameter: an untyped null parameter is a driver-level coin flip ("could not determine data
     * type"), and these statements must fail for policy reasons only.
     */
    private static final String INSERT_HOST_GLOBAL_USER = """
            insert into users (tenant_id, username, email, password_hash, active)
            values (null, ?, ?, 'not-a-real-hash', true)
            returning id""";

    private static final String INSERT_ROLE = """
            insert into roles (tenant_id, name, display_name, is_static, is_default)
            values (?, ?, ?, false, false)
            returning id""";

    private static final String INSERT_HOST_GLOBAL_ROLE = """
            insert into roles (tenant_id, name, display_name, is_static, is_default)
            values (null, ?, ?, false, false)
            returning id""";

    private static final String INSERT_ORG_UNIT = """
            insert into organization_units (tenant_id, code, display_name)
            values (?, ?, ?)
            returning id""";

    /** Literal null, same reason as {@link #INSERT_HOST_GLOBAL_USER}. */
    private static final String INSERT_HOST_GLOBAL_ORG_UNIT = """
            insert into organization_units (tenant_id, code, display_name)
            values (null, ?, ?)
            returning id""";

    private static final AtomicInteger SEQ = new AtomicInteger();

    private static Identity tenantA;
    private static Identity tenantB;
    private static HostGlobal hostGlobal;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void ensureTwoTenantsAndTheHostRows() {
        if (tenantA == null) {
            tenantA = createTenantWithIdentityRows();
            tenantB = createTenantWithIdentityRows();
            hostGlobal = readHostGlobalRows();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Fail-closed: no setting means no rows, not all rows — and not "just the host ones"
    // ---------------------------------------------------------------------------------------

    /**
     * The claim that makes RLS a floor here too, and the one the host-global branch could most easily
     * have broken. Written as plain {@code OR tenant_id IS NULL}, that branch would have handed every
     * unannounced transaction the host operators' rows — username, e-mail and password hash — while
     * every other table stayed fail-closed. So the zero asserted below is asserted over the WHOLE
     * table, host rows included, and the host precondition above it is what proves the tables are not
     * simply empty.
     */
    @Test
    void aTransactionWithNoSettingSeesNoRowsAtAllIncludingTheHostGlobalOnes() {
        Map<String, Long> underHost = asHost(this::countAllTables);
        assertEveryTable(underHost, (table, count) -> assertThat(count)
                .as("precondition: %s holds rows — otherwise the zeros below would be the absence of "
                        + "data, not the presence of a policy", table)
                .isPositive());

        assertAllVisible(inTenant(tenantA.tenantId(), () -> rowVisibility(tenantA)),
                "precondition: tenant A's own rows ARE visible under its own setting; if they were not, "
                        + "every assertion in this class would pass for the wrong reason");

        assertEveryTable(withoutAnySetting(this::countAllTables), (table, count) -> assertThat(count)
                .as("no app.current_tenant, no app.is_host: %s must answer 0 rows. A missing GUC has to "
                        + "be an empty screen, never a leak", table)
                .isZero());

        assertThat(withoutAnySetting(() -> hostGlobalVisibility()))
                .as("the host-global branch is gated on an ESTABLISHED tenant context: with no setting "
                        + "at all the host operator's user and role must be invisible too, or V12 turned "
                        + "the one guarantee RLS rests on into an exception for exactly the most "
                        + "privileged rows in the database")
                .containsOnly(false);
    }

    /**
     * {@code nullif(..., '')}: a transaction-local setting reverts to the placeholder default — the
     * EMPTY STRING — not to "never set" (ADR-0018, measured on postgres:16). Cleared must therefore
     * read as absent, quietly, both for the tenant branch and for the host-global one; the latter is
     * the new risk in V12, since {@code '' IS NOT NULL} is TRUE and a guard written on the raw setting
     * instead of on {@code nullif} would have left host rows visible on every recycled connection.
     */
    @Test
    void aClearedSettingIsTreatedAsAbsentInsteadOfRaisingOrOpeningTheHostRows() {
        Observation afterClearing = transactions().execute(status -> {
            bindSettings(Long.toString(tenantA.tenantId()), HibernateTenantFilterAspect.CLEARED);
            assertEveryTable(countAllTables(), (table, count) -> assertThat(count)
                    .as("precondition: with the tenant named, its rows in %s are visible on THIS "
                            + "connection", table)
                    .isPositive());

            bindSettings(HibernateTenantFilterAspect.CLEARED, HibernateTenantFilterAspect.CLEARED);
            return new Observation(countAllTables(), hostGlobalVisibility());
        });

        assertEveryTable(afterClearing.counts(), (table, count) -> assertThat(count)
                .as("a cleared (empty, not absent) setting must read as 'no tenant' and leave %s at 0 "
                        + "rows; if this throws instead of asserting, the policy lost its nullif()",
                        table)
                .isZero());
        assertThat(afterClearing.hostGlobalVisible())
                .as("cleared is not an established tenant context: the host operator's rows must be "
                        + "invisible on a recycled connection")
                .containsOnly(false);
    }

    // ---------------------------------------------------------------------------------------
    // Reads and writes across tenants
    // ---------------------------------------------------------------------------------------

    @Test
    void oneTenantsRowsAreInvisibleToTheOther() {
        assertNoneVisible(inTenant(tenantA.tenantId(), () -> rowVisibility(tenantB)),
                "tenant A holds no predicate here — this is a bare `where id = ?` on each table, so only "
                        + "the policy can keep tenant B's user, role and org unit out of the answer");

        assertNoneVisible(inTenant(tenantB.tenantId(), () -> rowVisibility(tenantA)),
                "the mirror direction: closing one side only is a regression nobody would see");

        assertAllVisible(inTenant(tenantB.tenantId(), () -> rowVisibility(tenantB)),
                "precondition: tenant B does see its own rows, so the invisibility above is isolation "
                        + "and not a broken fixture");
    }

    @Test
    void writingAnotherTenantsIdIsRejectedByThePolicy() {
        assertPolicyRejects(tenantA.tenantId(),
                "insert into users carrying tenant B's id",
                INSERT_USER, tenantB.tenantId(), unique("rlsid-steal"), unique("rlsid-steal") + "@x.test");
        assertPolicyRejects(tenantA.tenantId(),
                "insert into roles carrying tenant B's id",
                INSERT_ROLE, tenantB.tenantId(), unique("rlsid-steal"), unique("rlsid-steal"));
        assertPolicyRejects(tenantA.tenantId(),
                "insert into organization_units carrying tenant B's id",
                INSERT_ORG_UNIT, tenantB.tenantId(), unique("90"), unique("rlsid-steal"));

        assertPolicyRejects(tenantA.tenantId(),
                "handing an OWN org unit over to tenant B by update: USING lets tenant A see the row, "
                        + "WITH CHECK must refuse the new image",
                "update organization_units set tenant_id = ? where id = ?",
                tenantB.tenantId(), tenantA.rowId("organization_units"));

        assertAllVisible(inTenant(tenantA.tenantId(), () -> rowVisibility(tenantA)),
                "and none of the refusals damaged tenant A's own rows");
    }

    // ---------------------------------------------------------------------------------------
    // Host-global rows (tenant_id IS NULL) — the decision recorded in V12's header
    // ---------------------------------------------------------------------------------------

    /**
     * The read half of the decision. Measured requirement:
     * {@code ImpersonationService.backToImpersonator()} runs in the impersonated TENANT's context
     * (X-Tenant plus a matching {@code tenant} claim, which {@code AuthenticatedTenantFilter} treats
     * as authoritative) and has to reach the HOST actor's user row and that actor's HOST role to mint
     * the restored token's authorities. Without this branch the endpoint answers 401 "Impersonator is
     * not available"; with the user visible but the role hidden it would answer 200 and hand back a
     * PERMISSIONLESS token, which is the quieter of the two failures.
     */
    @Test
    void hostGlobalRowsAreVisibleToHostAndToAnEstablishedTenantContext() {
        assertThat(asHost(this::hostGlobalVisibility))
                .as("ADR-0018's host branch ignores tenant_id entirely, so tenant_id IS NULL rows are "
                        + "part of 'every row'")
                .containsOnly(true);

        assertThat(inTenant(tenantA.tenantId(), this::hostGlobalVisibility))
                .as("the impersonation return path reads exactly these two rows in a tenant context "
                        + "(ImpersonationIT step 4); if this is false that endpoint is 401")
                .containsOnly(true);
    }

    /**
     * The narrower, evidence-driven half: {@code organization_units} does NOT get the host-global
     * branch, because no flow reads a host org unit from a tenant context —
     * {@code OrganizationUnitService} scopes every read to {@code TenantContext} and re-checks
     * ownership on {@code findById}. Writing the same shape into all three tables "for consistency"
     * would be an unjustified widening, and this test is what keeps the difference deliberate.
     */
    @Test
    void aHostGlobalOrgUnitStaysInvisibleToATenantContext() {
        long hostOrgUnitId = asHost(() -> insertReturningId(
                INSERT_HOST_GLOBAL_ORG_UNIT, unique("99"), unique("rlsid-host-ou")));

        assertThat(asHost(() -> isVisible("organization_units", hostOrgUnitId)))
                .as("precondition: the host org unit exists and host can see it")
                .isTrue();
        assertThat(inTenant(tenantA.tenantId(), () -> isVisible("organization_units", hostOrgUnitId)))
                .as("organization_units deliberately has no host-global read branch (V12 header)")
                .isFalse();
    }

    /**
     * The write half, and the reason the branch is in {@code USING} only. A tenant context that could
     * INSERT {@code tenant_id = NULL} would be creating its own host operator — an escalation that
     * never touches {@code @PreAuthorize}, {@code Side.HOST} or any endpoint at all. The host control
     * underneath proves the refusal is about the CONTEXT and not about the statement.
     */
    @Test
    void aTenantContextCannotWriteAHostGlobalRow() {
        assertPolicyRejects(tenantA.tenantId(),
                "insert into users with tenant_id = NULL: a tenant minting a host operator",
                INSERT_HOST_GLOBAL_USER, unique("rlsid-escalate"),
                unique("rlsid-escalate") + "@x.test");
        assertPolicyRejects(tenantA.tenantId(),
                "insert into roles with tenant_id = NULL: a tenant minting a host role",
                INSERT_HOST_GLOBAL_ROLE, unique("rlsid-escalate"), unique("rlsid-escalate"));
        assertPolicyRejects(tenantA.tenantId(),
                "update users set tenant_id = NULL: promoting an own user to host scope. USING shows "
                        + "tenant A the row; WITH CHECK must refuse the new image",
                "update users set tenant_id = null where id = ?", tenantA.rowId("users"));

        assertThatCode(() -> asHost(() -> insertReturningId(
                INSERT_HOST_GLOBAL_USER, unique("rlsid-host"), unique("rlsid-host") + "@x.test")))
                .as("control: the same insert under host context is accepted, so the refusals above are "
                        + "about who is asking and not about the statement")
                .doesNotThrowAnyException();
    }

    @Test
    void hostContextSeesEveryTenantsRows() {
        assertAllVisible(asHost(() -> rowVisibility(tenantA)),
                "ADR-0018: the host branch of the policy says 'host may see every row'");
        assertAllVisible(asHost(() -> rowVisibility(tenantB)),
                "both tenants, and neither of them is the one whose setting is on the connection");
    }

    // --- fixtures ---

    /** One tenant's identity rows, keyed by table. */
    private record Identity(long tenantId, String name, Map<String, Long> rowIds) {
        long rowId(String table) {
            return rowIds.get(table);
        }
    }

    /** The two {@code tenant_id IS NULL} rows {@code DataSeeder} writes at startup. */
    private record HostGlobal(long userId, long roleId) {
    }

    /**
     * Both halves of one probe, taken on the SAME connection inside ONE transaction, so the two claims
     * cannot be a pool-timing coincidence.
     */
    private record Observation(Map<String, Long> counts, List<Boolean> hostGlobalVisible) {
    }

    /**
     * Creates a tenant over the host endpoint — {@code TenantAdminBootstrapListener} provisions its
     * {@code Admin} role and {@code admin} user in the same transaction — then adds an org unit with
     * plain SQL under that tenant's own setting. Those inserts are the positive control for
     * {@code WITH CHECK}.
     */
    private Identity createTenantWithIdentityRows() {
        String name = "rlsid" + Long.toString(System.nanoTime(), 36) + "-" + SEQ.incrementAndGet();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("displayName", "RLS identity isolation " + name);
        body.put("adminEmail", "admin@" + name + ".test");
        body.put("adminPassword", "RlsIdentity-2026-1!");

        HttpHeaders hostHeaders =
                bearerHeaders(accessToken(null, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), null);
        ResponseEntity<JsonNode> created = restTemplate.exchange("/api/tenants", HttpMethod.POST,
                new HttpEntity<>(body, hostHeaders), JsonNode.class);
        assertThat(created.getStatusCode())
                .as("the fixture tenant must be created (%s): %s", created.getStatusCode(),
                        created.getBody())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        long tenantId = created.getBody().path("id").asLong();
        assertThat(tenantId).isPositive();

        Map<String, Long> rowIds = inTenant(tenantId, () -> {
            Map<String, Long> ids = new LinkedHashMap<>();
            // The bootstrapped admin and Admin role, read under the tenant's OWN setting: that read is
            // itself a claim about the policy, and it is the one every later rowVisibility() check
            // hangs off. Both are guaranteed to exist by TenantBootstrapIT.
            ids.put("users", requireId("select min(id) from users where tenant_id = ?", tenantId));
            ids.put("roles", requireId("select min(id) from roles where tenant_id = ?", tenantId));
            ids.put("organization_units", insertReturningId(
                    INSERT_ORG_UNIT, tenantId, unique("00"), unique("rlsid-ou")));
            return ids;
        });
        return new Identity(tenantId, name, rowIds);
    }

    /**
     * The seeded host operator and host {@code Admin} role. Read under the host setting, which is the
     * only context that can see them without also naming a tenant — naming one would make this
     * fixture depend on the very branch several tests below are trying to measure.
     */
    private HostGlobal readHostGlobalRows() {
        return asHost(() -> new HostGlobal(
                requireId("select min(id) from users where tenant_id is null"),
                requireId("select min(id) from roles where tenant_id is null")));
    }

    private long requireId(String sql, Object... args) {
        Long id = jdbcTemplate.queryForObject(sql, Long.class, args);
        assertThat(id).as("no row for: %s", sql).isNotNull();
        return id;
    }

    private String unique(String prefix) {
        return prefix + "_" + System.nanoTime() + "_" + SEQ.incrementAndGet();
    }

    // --- probing ---

    private Map<String, Long> countAllTables() {
        Map<String, Long> counts = new LinkedHashMap<>();
        GROUP_TABLES.forEach(table -> counts.put(table, jdbcTemplate.queryForObject(
                "select count(*) from " + table, Long.class)));
        return counts;
    }

    /** Whether each of the tenant's three rows can be seen at all, by primary key. */
    private Map<String, Boolean> rowVisibility(Identity identity) {
        Map<String, Boolean> visible = new LinkedHashMap<>();
        GROUP_TABLES.forEach(table -> visible.put(table, isVisible(table, identity.rowId(table))));
        return visible;
    }

    /** Visibility of the two host-global rows, in a fixed order: user, then role. */
    private List<Boolean> hostGlobalVisibility() {
        return List.of(isVisible("users", hostGlobal.userId()),
                isVisible("roles", hostGlobal.roleId()));
    }

    private boolean isVisible(String table, long id) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from " + table + " where id = ?", Long.class, id);
        return count != null && count == 1;
    }

    // --- assertions over the whole group ---

    private <V> void assertEveryTable(Map<String, V> observed, BiConsumer<String, V> claim) {
        assertThat(observed)
                .as("the probe must answer for every table in the group")
                .containsOnlyKeys(GROUP_TABLES.toArray(String[]::new));
        observed.forEach(claim);
    }

    private void assertAllVisible(Map<String, Boolean> visibility, String because) {
        assertEveryTable(visibility, (table, visible) -> assertThat(visible)
                .as("%s — %s", because, table)
                .isTrue());
    }

    private void assertNoneVisible(Map<String, Boolean> visibility, String because) {
        assertEveryTable(visibility, (table, visible) -> assertThat(visible)
                .as("%s — %s", because, table)
                .isFalse());
    }

    private long insertReturningId(String sql, Object... args) {
        Long id = jdbcTemplate.queryForObject(sql, Long.class, args);
        assertThat(id).as("insert did not return an id: %s", sql).isNotNull();
        return id;
    }

    /**
     * Asserts that the statement is refused BY THE POLICY, in its own transaction (a failed statement
     * aborts the PostgreSQL transaction, so sharing one would turn the next assertion into "current
     * transaction is aborted" — a red pointing nowhere near the cause).
     *
     * <p>{@code PreparedStatement.execute()} rather than {@code update}/{@code query}: the statements
     * handed in are both {@code INSERT … RETURNING id} and plain {@code UPDATE}s, and each convenience
     * method rejects one of those shapes with a driver-level complaint that would mask the refusal.
     */
    private void assertPolicyRejects(long tenantId, String because, String sql, Object... args) {
        assertThatThrownBy(() -> transactions().execute(status -> {
            bindSettings(Long.toString(tenantId), HibernateTenantFilterAspect.CLEARED);
            return jdbcTemplate.execute(sql, (PreparedStatementCallback<Boolean>) statement -> {
                for (int index = 0; index < args.length; index++) {
                    statement.setObject(index + 1, args[index]);
                }
                return statement.execute();
            });
        }), "the policy must refuse this write: %s", because)
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining(POLICY_VIOLATION);
    }

    // --- transactions and settings ---

    private TransactionTemplate transactions() {
        if (transactionTemplate == null) {
            transactionTemplate = new TransactionTemplate(transactionManager);
        }
        return transactionTemplate;
    }

    private <T> T inTenant(long tenantId, Supplier<T> body) {
        return transactions().execute(status -> {
            bindSettings(Long.toString(tenantId), HibernateTenantFilterAspect.CLEARED);
            return body.get();
        });
    }

    private <T> T asHost(Supplier<T> body) {
        return transactions().execute(status -> {
            bindSettings(HibernateTenantFilterAspect.CLEARED, HibernateTenantFilterAspect.IS_HOST_ON);
            return body.get();
        });
    }

    /** A transaction that never announces anything — the path RLS has to fail closed on. */
    private <T> T withoutAnySetting(Supplier<T> body) {
        return transactions().execute(status -> body.get());
    }

    private void bindSettings(String currentTenant, String isHost) {
        jdbcTemplate.query(BIND_SETTINGS_SQL, DISCARD_RESULT, currentTenant, isHost);
    }
}
