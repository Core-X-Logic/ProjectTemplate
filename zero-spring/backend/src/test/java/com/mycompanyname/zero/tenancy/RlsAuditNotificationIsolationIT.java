package com.mycompanyname.zero.tenancy;

import com.mycompanyname.zero.AbstractIntegrationIT;
import com.mycompanyname.zero.notification.NotificationLevel;
import com.mycompanyname.zero.notification.NotificationService;
import com.mycompanyname.zero.shared.tenant.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
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
 * The evidence for the LAST policed table group ({@code audit_logs}, {@code entity_changes},
 * {@code user_notifications}). Migration {@code V13__rls_audit_notification.sql}; host branch
 * {@code ADR-0018}, exemption set {@code ADR-0019}.
 *
 * <p><b>What makes this group different, and this class necessary.</b>
 *
 * <ul>
 *   <li><b>Host cross-tenant visibility is the PRODUCT here.</b> "Show me what happened in tenant
 *       X" is the host support feature, and {@link TenantFilterCoverageIT} asserts it in both
 *       directions at the {@code @Filter} layer. The policy's host branch must keep that promise at
 *       the floor — so "host sees BOTH tenants' rows" is asserted below in the same direction, and
 *       a host branch mutation turns both classes red together.</li>
 *   <li><b>A policy mistake here is SILENT.</b> {@code AuditLogInterceptor.afterCompletion} and
 *       {@code EntityChangeListener}'s after-commit hook both swallow runtime failures with a
 *       {@code log.warn}: a {@code WITH CHECK} that refuses the real writers would not fail a
 *       single request — it would quietly stop recording the audit trail. That is why the HTTP
 *       tests below assert the row was WRITTEN, never merely that the request answered 2xx.</li>
 *   <li><b>No host-global read branch.</b> Unlike {@code users}/{@code roles} in V12, no flow reads
 *       a {@code tenant_id IS NULL} row from a tenant context in these tables — so the branch V12
 *       justified with {@code backToImpersonator()} is deliberately absent, and the host operator's
 *       own audit trail stays invisible to every tenant context. Asserted below, because writing
 *       the same shape into every group "for consistency" is exactly the widening V12's header
 *       warns against.</li>
 * </ul>
 *
 * <p>Mechanics follow {@link RlsIdentityIsolationIT}: settings are bound with the aspect's own
 * constants, each refused write gets its own transaction, and the fixture's own inserts are the
 * positive control for {@code WITH CHECK}.
 */
class RlsAuditNotificationIsolationIT extends AbstractIntegrationIT {

    /** The group V13 covers. Every visibility claim below is made about all three. */
    private static final List<String> GROUP_TABLES =
            List.of("audit_logs", "entity_changes", "user_notifications");

    /** Per-table column carrying this class's unique marker, so shared tables stay shareable. */
    private static final Map<String, String> MARKER_COLUMN = Map.of(
            "audit_logs", "username",
            "entity_changes", "entity_type_name",
            "user_notifications", "notification_name");

    private static final String POLICY_VIOLATION = "row-level security policy";

    private static final String DEFAULT_TENANT = "default";

    /**
     * Tenant ids that deliberately match no row in {@code tenants} — neither {@code audit_logs} nor
     * {@code entity_changes} carries a foreign key on {@code tenant_id} (V2: the record must outlive
     * its source), and {@code user_notifications} only references {@code users.id}, which is global.
     * Distinct from {@link TenantFilterCoverageIT}'s 900_00x so the two fixtures can never collide.
     */
    private static final long TENANT_A = 910_001L;
    private static final long TENANT_B = 910_002L;

    private static final String BIND_SETTINGS_SQL =
            "select set_config('" + HibernateTenantFilterAspect.CURRENT_TENANT_SETTING + "', ?, true),"
                    + " set_config('" + HibernateTenantFilterAspect.IS_HOST_SETTING + "', ?, true)";

    private static final ResultSetExtractor<Void> DISCARD_RESULT = resultSet -> null;

    private static final String INSERT_AUDIT_LOG = """
            insert into audit_logs (tenant_id, username, execution_time, execution_duration_ms,
                                    http_method, url, http_status_code)
            values (?, ?, now(), 1, 'GET', '/rls-audit-probe', 200)
            returning id""";

    /**
     * {@code tenant_id} as a literal {@code null} rather than a bound parameter, for the reason
     * {@link RlsIdentityIsolationIT} records: an untyped null parameter is a driver-level coin flip,
     * and these statements must fail (or succeed) for policy reasons only.
     */
    private static final String INSERT_HOST_SCOPED_AUDIT_LOG = """
            insert into audit_logs (tenant_id, username, execution_time, execution_duration_ms,
                                    http_method, url, http_status_code)
            values (null, ?, now(), 1, 'GET', '/rls-audit-probe', 200)
            returning id""";

    private static final String INSERT_ENTITY_CHANGE = """
            insert into entity_changes (tenant_id, entity_type_name, entity_id, change_type, change_time)
            values (?, ?, '1', 'UPDATED', now())
            returning id""";

    private static final String INSERT_HOST_SCOPED_ENTITY_CHANGE = """
            insert into entity_changes (tenant_id, entity_type_name, entity_id, change_type, change_time)
            values (null, ?, '1', 'UPDATED', now())
            returning id""";

    private static final String INSERT_NOTIFICATION = """
            insert into user_notifications (tenant_id, user_id, notification_name, title)
            values (?, ?, ?, 'rls probe')
            returning id""";

    private static final String INSERT_HOST_SCOPED_NOTIFICATION = """
            insert into user_notifications (tenant_id, user_id, notification_name, title)
            values (null, ?, ?, 'rls probe')
            returning id""";

    private static final AtomicInteger SEQ = new AtomicInteger();

    /** The seeded host operator's global user id — {@code user_notifications.user_id} needs a real user. */
    private static Long hostRecipientId;

    private static Map<String, Long> rowsOfTenantA;
    private static Map<String, Long> rowsOfTenantB;

    /**
     * One {@code tenant_id IS NULL} row per table: the host operator's own audit trail and inbox.
     * Their visibility gets its own assertions — the {@code tenant_id = <setting>} branch can never
     * return them, so only the host branch decides, and V13 deliberately gives them NO tenant-context
     * read branch.
     */
    private static Map<String, Long> hostScopedRows;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private NotificationService notificationService;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void ensureRowsOfBothTenantsAndTheHost() {
        if (rowsOfTenantA == null) {
            hostRecipientId = asHost(() -> requireId("select min(id) from users where tenant_id is null"));
            rowsOfTenantA = asHost(() -> seedGroupRows(TENANT_A));
            rowsOfTenantB = asHost(() -> seedGroupRows(TENANT_B));
            hostScopedRows = asHost(this::seedHostScopedRows);
        }
    }

    /** The service-path tests below switch the thread's context; never leak it to the next test. */
    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    // ---------------------------------------------------------------------------------------
    // Fail-closed: no setting means no rows — host rows and tenant rows alike
    // ---------------------------------------------------------------------------------------

    @Test
    void aTransactionWithNoSettingSeesNoRowsAtAll() {
        assertEveryTable(asHost(this::countAllTables), (table, count) -> assertThat(count)
                .as("precondition: %s holds rows under the host setting — otherwise the zeros below "
                        + "would be the absence of data, not the presence of a policy", table)
                .isPositive());

        assertEveryTable(withoutAnySetting(this::countAllTables), (table, count) -> assertThat(count)
                .as("no app.current_tenant, no app.is_host: %s must answer 0 rows — an audit trail "
                        + "that leaks to an unannounced transaction leaks every tenant's request "
                        + "history at once", table)
                .isZero());
    }

    /**
     * {@code nullif(..., '')}: a transaction-local setting reverts to the placeholder default — the
     * EMPTY STRING, not "never set" (ADR-0018, measured on postgres:16). Cleared must read as absent
     * instead of raising, or every recycled pooled connection would turn policy checks into errors.
     */
    @Test
    void aClearedSettingIsTreatedAsAbsentInsteadOfRaising() {
        Map<String, Long> afterClearing = transactions().execute(status -> {
            bindSettings(Long.toString(TENANT_A), HibernateTenantFilterAspect.CLEARED);
            assertEveryTable(countAllTables(), (table, count) -> assertThat(count)
                    .as("precondition: with tenant A named, its rows in %s are visible on THIS "
                            + "connection", table)
                    .isPositive());

            bindSettings(HibernateTenantFilterAspect.CLEARED, HibernateTenantFilterAspect.CLEARED);
            return countAllTables();
        });

        assertEveryTable(afterClearing, (table, count) -> assertThat(count)
                .as("a cleared (empty, not absent) setting must read as 'no context' and leave %s at "
                        + "0 rows; if this throws instead of asserting, the policy lost its nullif()",
                        table)
                .isZero());
    }

    // ---------------------------------------------------------------------------------------
    // Reads: tenant vs tenant, tenant vs host rows, and the host's product-level view
    // ---------------------------------------------------------------------------------------

    @Test
    void oneTenantsRowsAreInvisibleToTheOther() {
        assertNoneVisible(inTenant(TENANT_A, () -> rowVisibility(rowsOfTenantB)),
                "these are bare `where id = ?` probes with no predicate at all, so only the policy "
                        + "can keep tenant B's audit trail and inbox out of tenant A's answer");

        assertNoneVisible(inTenant(TENANT_B, () -> rowVisibility(rowsOfTenantA)),
                "the mirror direction: closing one side only is a regression nobody would see");

        assertAllVisible(inTenant(TENANT_A, () -> rowVisibility(rowsOfTenantA)),
                "precondition: tenant A does see its own rows, so the invisibility above is "
                        + "isolation and not a broken fixture");
    }

    /**
     * The PRODUCT direction, at the floor. {@link TenantFilterCoverageIT} pins the same claim at the
     * {@code @Filter} layer and over HTTP; this is the assertion a host-branch mutation must turn
     * red — cross-tenant audit review IS the host feature, so a policy without the host branch would
     * pass every tenant-side test while silently blinding host support.
     */
    @Test
    void hostSeesEveryTenantsRowsAndItsOwnHostScopedOnes() {
        assertAllVisible(asHost(() -> rowVisibility(rowsOfTenantA)),
                "ADR-0018's host branch ignores tenant_id entirely — tenant A's rows included");
        assertAllVisible(asHost(() -> rowVisibility(rowsOfTenantB)),
                "both tenants, neither of which is named on the connection");
        assertAllVisible(asHost(() -> rowVisibility(hostScopedRows)),
                "and the tenant_id IS NULL rows, which no other branch can ever return");
    }

    /**
     * The deliberate difference from V12: no host-global read branch. {@code users}/{@code roles}
     * earned theirs with a measured flow ({@code backToImpersonator()}); here no tenant-context flow
     * reads a {@code tenant_id IS NULL} row — {@code AuditLogService} adds an explicit tenant
     * predicate, {@code NotificationService.list} keys on the recipient's global id and tenant
     * users' rows always carry their own tenant tag. So the host operator's own request trail and
     * inbox stay invisible to every tenant context, established or not.
     */
    @Test
    void hostScopedRowsAreInvisibleToAnEstablishedTenantContext() {
        assertNoneVisible(inTenant(TENANT_A, () -> rowVisibility(hostScopedRows)),
                "V13 has no `tenant_id IS NULL` read branch: the host operator's audit trail is not "
                        + "part of any tenant's view, established context or not");
    }

    // ---------------------------------------------------------------------------------------
    // Writes: WITH CHECK in both directions
    // ---------------------------------------------------------------------------------------

    @Test
    void aTenantContextCanWriteOnlyItsOwnTag() {
        assertThatCode(() -> inTenant(TENANT_A, () -> {
            insertReturningId(INSERT_AUDIT_LOG, TENANT_A, unique("rlsan-own"));
            insertReturningId(INSERT_ENTITY_CHANGE, TENANT_A, unique("rlsan-own"));
            insertReturningId(INSERT_NOTIFICATION, TENANT_A, hostRecipientId, unique("rlsan-own"));
            return null;
        }))
                .as("positive control: a tenant context writing its OWN tag must pass WITH CHECK — "
                        + "the real audit writers run exactly this shape on every tenant request, and "
                        + "a policy too strict here fails SILENTLY in production (both writers swallow "
                        + "the refusal with log.warn)")
                .doesNotThrowAnyException();
    }

    @Test
    void writingAnotherTenantsTagIsRejectedByThePolicy() {
        assertPolicyRejects(TENANT_A,
                "insert into audit_logs carrying tenant B's tag — forging another tenant's audit trail",
                INSERT_AUDIT_LOG, TENANT_B, unique("rlsan-steal"));
        assertPolicyRejects(TENANT_A,
                "insert into entity_changes carrying tenant B's tag",
                INSERT_ENTITY_CHANGE, TENANT_B, unique("rlsan-steal"));
        assertPolicyRejects(TENANT_A,
                "insert into user_notifications tagged for tenant B — injecting into another "
                        + "tenant's inbox view",
                INSERT_NOTIFICATION, TENANT_B, hostRecipientId, unique("rlsan-steal"));

        assertPolicyRejects(TENANT_A,
                "re-tagging an own audit row to tenant B by update: USING shows tenant A the row, "
                        + "WITH CHECK must refuse the new image",
                "update audit_logs set tenant_id = ? where id = ?",
                TENANT_B, rowsOfTenantA.get("audit_logs"));
    }

    /**
     * The NULL direction. A tenant context that could write {@code tenant_id IS NULL} would be
     * hiding rows from its own tenant's view (NULL matches no tenant branch) while parking them in
     * host scope — audit noise at best, trail-laundering at worst. No legitimate writer does this:
     * {@code AuditPrincipal.tenantId()} reads the SAME TenantContext the aspect mirrors into the
     * setting, so a tenant-context writer always carries its own tag by construction.
     */
    @Test
    void aTenantContextCannotWriteAHostScopedRow() {
        assertPolicyRejects(TENANT_A,
                "insert into audit_logs with tenant_id = NULL from a tenant context",
                INSERT_HOST_SCOPED_AUDIT_LOG, unique("rlsan-launder"));
        assertPolicyRejects(TENANT_A,
                "insert into entity_changes with tenant_id = NULL from a tenant context",
                INSERT_HOST_SCOPED_ENTITY_CHANGE, unique("rlsan-launder"));
        assertPolicyRejects(TENANT_A,
                "insert into user_notifications with tenant_id = NULL from a tenant context",
                INSERT_HOST_SCOPED_NOTIFICATION, hostRecipientId, unique("rlsan-launder"));

        assertThatCode(() -> asHost(() -> insertReturningId(
                INSERT_HOST_SCOPED_AUDIT_LOG, unique("rlsan-host-ok"))))
                .as("control: the same NULL-tagged insert under the host setting is accepted, so the "
                        + "refusals above are about who is asking and not about the statement")
                .doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------------------------------
    // The real writers, end to end — asserted as WRITTEN, because their failure mode is silent
    // ---------------------------------------------------------------------------------------

    /**
     * A real HTTP request in a real tenant context must still produce its {@code audit_logs} row,
     * tagged with that tenant. This cannot be inferred from the response: the interceptor catches
     * the recording failure and answers 200 regardless, so a WITH CHECK that broke this path would
     * pass every other test in the suite while the audit trail silently stopped.
     */
    @Test
    void aRealTenantRequestStillWritesItsAuditRow() {
        String marker = unique("rlsan_http");
        HttpHeaders tenantAdmin = bearerHeaders(
                accessToken(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), DEFAULT_TENANT);

        ResponseEntity<JsonNode> audited = restTemplate.exchange(
                "/api/audit-logs?userName=" + marker + "&page=0&size=1",
                HttpMethod.GET, new HttpEntity<>(tenantAdmin), JsonNode.class);
        assertThat(audited.getStatusCode()).isEqualTo(HttpStatus.OK);

        long defaultTenantId = requirePlainId("select id from tenants where name = ?", DEFAULT_TENANT);
        Long written = pollForOne(() -> asHostDatabase(() -> jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where tenant_id = ? and parameters like ?",
                Long.class, defaultTenantId, "%" + marker + "%")));
        assertThat(written)
                .as("the audited GET must land in audit_logs TAGGED with the default tenant's id — "
                        + "AuditLogInterceptor swallows a policy refusal with log.warn, so only this "
                        + "row count can tell a working WITH CHECK from a silently broken one")
                .isPositive();
    }

    /**
     * Same claim for the Hibernate listener path. The {@code entity_changes} INSERT runs after the
     * business commit, in {@code EntityChangeWriter}'s own REQUIRES_NEW transaction — so the setting
     * it is judged under is the one the aspect binds inside THAT transaction, from the same
     * thread-local context that served the request. This test is the measurement that the deferred
     * write really does carry the tenant's setting rather than a cleared one.
     */
    @Test
    void aRealTrackedEntityMutationStillWritesItsChangeRow() {
        String displayName = unique("rlsan_ou");
        HttpHeaders tenantAdmin = bearerHeaders(
                accessToken(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), DEFAULT_TENANT);

        ResponseEntity<JsonNode> created = restTemplate.exchange(
                "/api/organization-units", HttpMethod.POST,
                new HttpEntity<>(Map.of("displayName", displayName), tenantAdmin), JsonNode.class);
        assertThat(created.getStatusCode().is2xxSuccessful())
                .as("organization unit creation must succeed, got %s", created.getStatusCode())
                .isTrue();
        long ouId = created.getBody().path("id").asLong();

        long defaultTenantId = requirePlainId("select id from tenants where name = ?", DEFAULT_TENANT);
        Long written = pollForOne(() -> asHostDatabase(() -> jdbcTemplate.queryForObject(
                "select count(*) from entity_changes where entity_id = ? and tenant_id = ? "
                        + "and entity_type_name like '%OrganizationUnit'",
                Long.class, String.valueOf(ouId), defaultTenantId)));
        assertThat(written)
                .as("the CREATED history row must exist and carry the tenant's tag — the listener's "
                        + "after-commit hook swallows a policy refusal with log.warn, so the 2xx above "
                        + "proves nothing about this table")
                .isPositive();
    }

    /**
     * Both write shapes of {@code NotificationService.publish}, through the real service so the
     * production aspect binds the settings: a tenant-context publish tagged with that same tenant
     * (the welcome-notification shape) and a HOST-context publish tagged with the tenant the alert
     * is about (the {@code SubscriptionNotificationBridge} shape — the very reason WITH CHECK needs
     * its host branch).
     */
    @Test
    void notificationPublishStillWorksFromBothContexts() {
        long defaultTenantId = requirePlainId("select id from tenants where name = ?", DEFAULT_TENANT);

        String tenantShape = unique("rlsan_pub_tenant");
        TenantContext.setTenantId(defaultTenantId);
        try {
            notificationService.publish(hostRecipientId, defaultTenantId, tenantShape,
                    NotificationLevel.INFO, "tenant-context publish", null, null);
        } finally {
            TenantContext.clear();
        }
        assertThat(inTenantDatabase(defaultTenantId, () -> jdbcTemplate.queryForObject(
                "select count(*) from user_notifications where notification_name = ?",
                Long.class, tenantShape)))
                .as("a tenant-context publish tagged with its own tenant must be written and visible "
                        + "to that tenant")
                .isEqualTo(1L);

        String bridgeShape = unique("rlsan_pub_bridge");
        notificationService.publish(hostRecipientId, TENANT_A, bridgeShape,
                NotificationLevel.INFO, "host-context publish about a tenant", null, null);
        assertThat(asHostDatabase(() -> jdbcTemplate.queryForObject(
                "select count(*) from user_notifications where notification_name = ?",
                Long.class, bridgeShape)))
                .as("a HOST-context publish tagged with the tenant the alert is ABOUT must be "
                        + "written — this is the bridge shape WITH CHECK's host branch exists for")
                .isEqualTo(1L);
    }

    // --- fixtures ---

    /** One marked row per table for the given tag; runs under whatever setting the caller bound. */
    private Map<String, Long> seedGroupRows(long tenantId) {
        Map<String, Long> ids = new LinkedHashMap<>();
        ids.put("audit_logs", insertReturningId(INSERT_AUDIT_LOG, tenantId, unique("rlsan-seed")));
        ids.put("entity_changes", insertReturningId(INSERT_ENTITY_CHANGE, tenantId, unique("rlsan-seed")));
        ids.put("user_notifications", insertReturningId(
                INSERT_NOTIFICATION, tenantId, hostRecipientId, unique("rlsan-seed")));
        return ids;
    }

    private Map<String, Long> seedHostScopedRows() {
        Map<String, Long> ids = new LinkedHashMap<>();
        ids.put("audit_logs", insertReturningId(INSERT_HOST_SCOPED_AUDIT_LOG, unique("rlsan-hostrow")));
        ids.put("entity_changes", insertReturningId(INSERT_HOST_SCOPED_ENTITY_CHANGE, unique("rlsan-hostrow")));
        ids.put("user_notifications", insertReturningId(
                INSERT_HOST_SCOPED_NOTIFICATION, hostRecipientId, unique("rlsan-hostrow")));
        return ids;
    }

    private String unique(String prefix) {
        return prefix + "_" + System.nanoTime() + "_" + SEQ.incrementAndGet();
    }

    private long requireId(String sql, Object... args) {
        Long id = jdbcTemplate.queryForObject(sql, Long.class, args);
        assertThat(id).as("no row for: %s", sql).isNotNull();
        return id;
    }

    /** For tables OUTSIDE the policed set ({@code tenants} carries no tenant_id): no setting needed. */
    private long requirePlainId(String sql, Object... args) {
        Long id = jdbcTemplate.queryForObject(sql, Long.class, args);
        assertThat(id).as("no row for: %s", sql).isNotNull();
        return id;
    }

    // --- probing ---

    private Map<String, Long> countAllTables() {
        Map<String, Long> counts = new LinkedHashMap<>();
        GROUP_TABLES.forEach(table -> counts.put(table, jdbcTemplate.queryForObject(
                "select count(*) from " + table, Long.class)));
        return counts;
    }

    private Map<String, Boolean> rowVisibility(Map<String, Long> rowIds) {
        Map<String, Boolean> visible = new LinkedHashMap<>();
        GROUP_TABLES.forEach(table -> visible.put(table, isVisible(table, rowIds.get(table))));
        return visible;
    }

    private boolean isVisible(String table, long id) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from " + table + " where id = ?", Long.class, id);
        return count != null && count == 1;
    }

    /**
     * The audit row lands after the response ({@code afterCompletion}, and for {@code entity_changes}
     * after the commit) — polled briefly rather than slept for, mirroring {@code AuditLogIT}.
     */
    private Long pollForOne(Supplier<Long> count) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        Long seen = 0L;
        while (System.nanoTime() < deadline) {
            seen = count.get();
            if (seen != null && seen > 0) {
                return seen;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return seen;
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

    /** See {@link RlsIdentityIsolationIT#assertPolicyRejects}: own transaction, policy-only reds. */
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
