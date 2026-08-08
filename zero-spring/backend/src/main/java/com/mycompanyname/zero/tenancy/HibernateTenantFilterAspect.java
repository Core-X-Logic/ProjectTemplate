package com.mycompanyname.zero.tenancy;

import com.mycompanyname.zero.shared.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.hibernate.Session;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Second line of defense for tenant isolation (explicit tenant-scoped repository queries are the
 * primary defense): enables the Hibernate tenant/host filter on the current transactional Session
 * before every @Service method body runs, and publishes the same decision to the database as
 * transaction-local settings so PostgreSQL row-level policies can read it.
 *
 * <p>Ordering is what makes this reliable: the transaction advisor is pinned at
 * {@code LOWEST_PRECEDENCE - 100} (see {@code TransactionOrderConfig}) and this aspect at
 * {@code LOWEST_PRECEDENCE - 10}, so the aspect is guaranteed to execute INSIDE an already-open
 * transaction. The {@code @PersistenceContext} EntityManager proxy then unwraps to the actual
 * transaction-bound Session, so the enabled filter applies to the queries the service issues.
 *
 * <p><b>The database side (RLS baseline step 2; ADR-0018).</b> The same branch also writes
 * {@code app.current_tenant} / {@code app.is_host}, the two GUCs the row-level policies of
 * {@code V12}/{@code V13} read. Both live here rather than in a sibling aspect on purpose: the
 * filter decision and the policy decision must be the SAME decision. A second aspect would need its
 * own ordering guarantee against the transaction advisor and its own copy of the
 * {@code tenantId != null} branch — two places to forget when host semantics change, and a
 * divergence between them would be invisible until it produced either a leak or an empty screen.
 *
 * <p><b>The caller's context is restored on the way out.</b> A nested {@code @Service} call under a
 * switched {@link TenantContext} used to leave ITS decision on the connection for the rest of the
 * transaction: a host flow that called into a tenant-scoped service came back with
 * {@code app.current_tenant} still naming that tenant and {@code app.is_host} empty. For
 * {@code users}/{@code roles}/{@code organization_units} and
 * {@code audit_logs}/{@code entity_changes}/{@code user_notifications} host cross-tenant visibility
 * is PRODUCT behaviour (ADR-0018; {@code TenantFilterCoverageIT} asserts it in both directions), so
 * a stale tenant setting there produces false reds and empty screens rather than leaks.
 * {@code TenantAdminBootstrapper} already does this by hand in a {@code finally} for the Hibernate
 * filter; the GUCs get the same discipline here, once, for every switch rather than per call site.
 *
 * <p>No GUC is written when no transaction is active, and that is the point: there would be no
 * transaction to scope it to. Nothing is silently degraded either — a path that reaches the
 * database without passing here reads a NULL setting, and {@code tenant_id = NULL} is never true,
 * so the result is 0 rows (fail-closed), not a leak.
 */
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class HibernateTenantFilterAspect {

    public static final String TENANT_FILTER = "tenantFilter";
    public static final String HOST_FILTER = "hostFilter";

    /** GUC names the V12/V13 policies read; {@code tenant_id} is bigint here. */
    public static final String CURRENT_TENANT_SETTING = "app.current_tenant";
    public static final String IS_HOST_SETTING = "app.is_host";

    /** The only value that means "host" — the policy compares this literal, not truthiness. */
    public static final String IS_HOST_ON = "on";

    /**
     * There is no way back to "never set": {@code set_config(name, NULL, true)} resets a custom
     * setting to its default, which for a placeholder GUC is the EMPTY STRING, not unset (measured on
     * postgres:16). So emptying is the only clearing mechanism available, and the policies must treat
     * empty as absent — {@code nullif(current_setting('app.current_tenant', true), '')::bigint},
     * because {@code ''::bigint} raises instead of yielding NULL and would turn every policy check on
     * an already-used pooled connection into an error rather than a decision.
     *
     * <p>The same measurement is why {@link DatabaseTenantContext} folds NULL into this value: a
     * connection that was never used reads NULL and one whose transaction ended reads {@code ''}, and
     * those two states must compare equal or the restore below would fire on every first call.
     */
    static final String CLEARED = "";

    /**
     * One round trip, two settings. {@code is_local = true} (the third argument) is the whole safety
     * property: it makes the value revert when the transaction ends, so the connection HikariCP hands
     * to the next request no longer carries this tenant. With {@code false} the setting would outlive
     * the transaction on the pooled connection and the next request — a different tenant, or host —
     * would silently inherit it: RLS would then be a leak amplifier rather than a floor, because the
     * policy would faithfully authorize the wrong tenant.
     */
    private static final String BIND_TENANT_CONTEXT_SQL =
            "select set_config('" + CURRENT_TENANT_SETTING + "', ?, true),"
                    + " set_config('" + IS_HOST_SETTING + "', ?, true)";

    /**
     * Reads the caller's two settings and writes this call's two settings in the SAME round trip, so
     * restoring costs one extra statement per context SWITCH rather than one extra statement per
     * {@code @Service} entry (see {@link #applyTenantFilter}).
     *
     * <p><b>Why the CTE and not the obvious
     * {@code select current_setting(...), set_config(...)}.</b> That form measures correct on
     * postgres:16 — it returns the pre-write value — but only because the target list happens to be
     * evaluated left to right; nothing in SQL or in the PostgreSQL documentation promises the order of
     * expressions within one target list, and a planner change would silently turn "the caller's
     * value" into "the value we just wrote", i.e. a restore that restores nothing. {@code AS
     * MATERIALIZED} (PostgreSQL 12+) makes the read its own execution node, which the outer target
     * list can only consume after it has produced its row, so the ordering is a property of the plan
     * instead of a coincidence.
     */
    private static final String SWAP_TENANT_CONTEXT_SQL = """
            with caller as materialized (
                select current_setting('%1$s', true) as current_tenant,
                       current_setting('%2$s', true) as is_host)
            select caller.current_tenant,
                   caller.is_host,
                   set_config('%1$s', ?, true),
                   set_config('%2$s', ?, true)
            from caller"""
            .formatted(CURRENT_TENANT_SETTING, IS_HOST_SETTING);

    private static final RowMapper<DatabaseTenantContext> CALLER_CONTEXT =
            (resultSet, rowNumber) -> new DatabaseTenantContext(
                    resultSet.getString("current_tenant"), resultSet.getString("is_host"));

    /** {@code set_config} returns the value it set; nothing here needs it. */
    private static final ResultSetExtractor<Void> DISCARD_RESULT = resultSet -> null;

    private final JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    public HibernateTenantFilterAspect(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * <b>Why the restore is skipped when the caller had no context of its own.</b> The outermost
     * {@code @Service} frame of a transaction finds the settings cleared (nothing to go back to) and
     * its value must stay on the connection until the transaction ENDS, not until this method returns:
     * the transaction advisor sits outside this aspect, so Hibernate's commit-time flush — every
     * deferred UPDATE, and any INSERT not already forced out by identity generation — runs AFTER this
     * method returns. Clearing there would hand the policy an empty {@code app.current_tenant} exactly
     * when it is evaluated. <b>Measured (in the derived project this stack was first proven in):</b>
     * with the "no context to go back to" test removed, the entity UPDATE flushed at commit matched
     * 0 rows (the policy's {@code USING} hides the row from the writer), Hibernate reported
     * {@code ObjectOptimisticLockingFailureException: Row was updated or deleted by another
     * transaction} and the endpoint answered 500. {@code is_local = true} already reverts the value at
     * transaction end, which is the only boundary that matters for the pool.
     *
     * <p>The same test also skips the restore when the caller's context equals this one, which is the
     * common shape of nesting (same tenant, several services deep): those frames then cost exactly one
     * round trip — and, just as important, touch nothing on the connection while an exception unwinds
     * through them.
     *
     * <p><b>What restoring does NOT fix.</b> That same flush timing cuts the other way: a nested frame
     * that writes rows for a DIFFERENT tenant and leaves the DML for Hibernate to defer returns to the
     * caller's context, and the deferred statement is then judged under it. The cross-tenant writers
     * ({@code TenantAdminBootstrapper}, the account flows) must therefore either flush inside the
     * frame that owns the write or run it under host context; each table group's isolation IT is
     * where that is measured.
     */
    @Around("within(@org.springframework.stereotype.Service *)")
    public Object applyTenantFilter(ProceedingJoinPoint joinPoint) throws Throwable {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return joinPoint.proceed();
        }
        Session session = entityManager.unwrap(Session.class);
        Long tenantId = TenantContext.getTenantId();
        if (tenantId != null) {
            session.enableFilter(TENANT_FILTER).setParameter("tenantId", tenantId);
        } else {
            session.enableFilter(HOST_FILTER);
        }
        DatabaseTenantContext entering = DatabaseTenantContext.of(tenantId);
        DatabaseTenantContext caller = bindDatabaseTenantContext(entering);
        if (caller.isAbsent() || caller.equals(entering)) {
            return joinPoint.proceed();
        }
        try {
            Object result = joinPoint.proceed();
            restoreDatabaseTenantContext(caller);
            return result;
        } catch (Throwable failure) {
            restoreDatabaseTenantContextQuietly(caller, failure);
            throw failure;
        }
    }

    /**
     * Writes the current decision to the transaction's own connection and returns what the calling
     * frame had there.
     *
     * <p>The connection comes from {@link JdbcTemplate}, which resolves it through
     * {@code DataSourceUtils} and therefore returns the connection already bound to this transaction —
     * the very connection Hibernate is using. Anything that opens its OWN connection instead (a fresh
     * {@code DataSource.getConnection()}, a separate pool) writes the setting to a session that will
     * never run the query it was meant to scope; that exact bug has been measured elsewhere, where it
     * surfaced as "policy rejects the write" rather than as a mistake about connections. For the same
     * reason the read must come from here too: a value read on another connection would be a different
     * connection's context, and restoring it would be worse than not restoring at all.
     *
     * <p>Every invocation writes BOTH settings, including the one being turned off. That is what makes
     * a nested call under a switched {@link TenantContext} safe: if a host call left
     * {@code app.is_host = 'on'} behind while a nested tenant call set {@code app.current_tenant},
     * the policy — {@code tenant_id = <tenant> OR is_host = 'on'} — would read as true for EVERY row
     * and the isolation would be gone. The two settings are mutually exclusive by construction here,
     * so the policy can never see both.
     *
     * <p>Written on every call rather than once per transaction: the alternative (remember in Java
     * what was set, skip the statement if unchanged) has to track transaction and context together,
     * and getting that bookkeeping wrong fails silently in the direction of a stale tenant. Nothing is
     * remembered here — the connection itself is asked, in the same round trip as the write, so there
     * is no cache to go stale. Idempotent by construction, so a repeat is harmless.
     */
    private DatabaseTenantContext bindDatabaseTenantContext(DatabaseTenantContext entering) {
        return jdbcTemplate.queryForObject(SWAP_TENANT_CONTEXT_SQL, CALLER_CONTEXT,
                entering.currentTenant(), entering.isHost());
    }

    /** One round trip back to the caller's decision; {@code is_local = true} is kept, of course. */
    private void restoreDatabaseTenantContext(DatabaseTenantContext caller) {
        jdbcTemplate.query(BIND_TENANT_CONTEXT_SQL, DISCARD_RESULT,
                caller.currentTenant(), caller.isHost());
    }

    /**
     * The exception path. A failed SQL statement puts the PostgreSQL transaction in ABORTED state, so
     * the restore is then refused as well ({@code 25P02: current transaction is aborted} — measured
     * through this datasource, not assumed); letting that escape a {@code finally} would replace the
     * real failure — the constraint violation, the domain exception — with a message about the
     * transaction being aborted, turning a 409 into a 500 and hiding the cause from the caller and
     * from the test asserting on it. It is attached as suppressed instead, which keeps it in the stack
     * trace without letting it win. Nothing is lost by giving up here: a transaction that cannot
     * execute a statement cannot commit either, and {@code is_local = true} discards the setting when
     * it rolls back.
     *
     * <p>The failure this guards against is unusually well hidden: pgjdbc hangs the error that aborted
     * the transaction underneath the {@code 25P02} one, so the ROOT cause stays the same either way and
     * only the exception the caller actually catches changes. {@code GucTenantContextIT} therefore
     * asserts on the whole cause chain — its first version, which asserted on the root cause, passed
     * against an unguarded {@code finally}.
     */
    private void restoreDatabaseTenantContextQuietly(DatabaseTenantContext caller, Throwable failure) {
        try {
            restoreDatabaseTenantContext(caller);
        } catch (RuntimeException restoreFailure) {
            failure.addSuppressed(restoreFailure);
        }
    }

    /**
     * The pair of settings as the database holds them, so "what the caller had" and "what this call
     * needs" are the same kind of thing and can simply be compared.
     *
     * @param currentTenant {@code app.current_tenant}: the tenant id as text, or {@link #CLEARED}
     * @param isHost {@code app.is_host}: {@link #IS_HOST_ON}, or {@link #CLEARED}
     */
    private record DatabaseTenantContext(String currentTenant, String isHost) {

        private DatabaseTenantContext {
            // current_setting(name, true) answers NULL on a connection that never carried the
            // setting and '' on one whose transaction reverted it; both mean "no context".
            currentTenant = currentTenant == null ? CLEARED : currentTenant;
            isHost = isHost == null ? CLEARED : isHost;
        }

        static DatabaseTenantContext of(Long tenantId) {
            return tenantId == null
                    ? new DatabaseTenantContext(CLEARED, IS_HOST_ON)
                    : new DatabaseTenantContext(tenantId.toString(), CLEARED);
        }

        boolean isAbsent() {
            return CLEARED.equals(currentTenant) && CLEARED.equals(isHost);
        }
    }
}
