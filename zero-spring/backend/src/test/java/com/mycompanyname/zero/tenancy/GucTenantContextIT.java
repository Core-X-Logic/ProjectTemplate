package com.mycompanyname.zero.tenancy;

import com.mycompanyname.zero.AbstractIntegrationIT;
import com.mycompanyname.zero.shared.tenant.TenantContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Proves that {@link HibernateTenantFilterAspect} puts the tenant decision where PostgreSQL can read
 * it, and — the claim that matters — that it does NOT leave it behind on the pooled connection
 * (RLS baseline step 2; ADR-0018).
 *
 * <p><b>Why the transaction-local assertion is the important one.</b> The third argument of
 * {@code set_config} is {@code is_local}. With {@code true} the value reverts when the transaction
 * ends; with {@code false} it would stay on the physical connection, and HikariCP would hand that
 * connection — still carrying the previous tenant — to the next request. Under the V12/V13 policies
 * that is worse than having no RLS at all: the policy would faithfully authorize the WRONG tenant, and
 * the read would look perfectly legitimate. So {@link #theTenantSettingDoesNotSurviveTheTransaction()}
 * deliberately keeps borrowing from the pool until it gets the SAME backend process back, and fails
 * loudly if it never does, rather than asserting on a fresh connection where any value would pass.
 *
 * <p><b>Empty-green counter.</b> Every probe also reads {@link #CONTROL_SETTING}, a setting name no
 * production code ever writes. It must come back NULL. Without that control, a probe that returned
 * some value for everything would make each assertion below pass for the wrong reason — the values
 * would be coming from the environment rather than from the aspect.
 *
 * <p><b>And that it gives the caller's context back.</b> The last four tests are about the way OUT
 * of a nested {@code @Service} call. Host cross-tenant reads on
 * {@code users}/{@code roles}/{@code organization_units} and
 * {@code audit_logs}/{@code entity_changes}/{@code user_notifications} are product behaviour
 * (ADR-0018), so a host flow that came back from a tenant-scoped call with a stale tenant setting
 * would show up as a false red and an empty screen — not a leak, but broken product behaviour.
 *
 * <p>The tenant context is armed by invoking the production aspect itself, through a minimal
 * {@link ProceedingJoinPoint}, the same way {@code TenantFilterCoverageIT} does it: a test that
 * re-implemented the aspect's decision could not detect the aspect drifting away from it.
 */
class GucTenantContextIT extends AbstractIntegrationIT {

    /**
     * Deliberately matches no row in {@code tenants}: this IT only ever compares strings coming back
     * from {@code current_setting}, so it needs an id that no other IT could also have written.
     */
    private static final long TENANT_A = 900_101L;
    private static final long TENANT_B = 900_102L;

    /** Written by nobody. See the class javadoc: this is the empty-green counter. */
    private static final String CONTROL_SETTING = "app.guc_probe_control";

    /** Message of the plain Java failure used to reach the aspect's exception path. */
    private static final String NESTED_FAILURE = "nested service call failed";

    /**
     * Puts the PostgreSQL transaction in ABORTED state, after which every further statement in it —
     * the aspect's restore included — is refused with {@code 25P02} (measured through this very
     * datasource: {@code select 1} right after this one comes back as
     * "current transaction is aborted, commands ignored until end of transaction block").
     */
    private static final String ABORT_TRANSACTION_SQL = "select 1 / 0";

    private static final String DIVISION_BY_ZERO = "division by zero";
    private static final String TRANSACTION_ABORTED = "current transaction is aborted";

    private static final String PROBE_SQL = """
            select current_setting('%s', true) as current_tenant,
                   current_setting('%s', true) as is_host,
                   current_setting('%s', true) as control,
                   pg_backend_pid()            as backend_pid
            """.formatted(
            HibernateTenantFilterAspect.CURRENT_TENANT_SETTING,
            HibernateTenantFilterAspect.IS_HOST_SETTING,
            CONTROL_SETTING);

    /**
     * How many pool borrows to spend looking for the connection the armed transaction used. Hikari
     * hands a thread its most recently used connection first, so one is normally enough; the budget
     * exists because a background task (scheduling, ShedLock) may take it in between.
     */
    private static final int POOL_BORROW_BUDGET = 30;

    @Autowired
    private HibernateTenantFilterAspect tenantFilterAspect;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    /** The context is a ThreadLocal shared with every later test on this thread. */
    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    // ---------------------------------------------------------------------------------------
    // What the database sees while a @Service method runs
    // ---------------------------------------------------------------------------------------

    @Test
    void tenantContextIsPublishedAsTheExactTenantId() {
        Settings underTenant = inTenantContext(TENANT_A, this::readSettings);

        assertThat(underTenant.currentTenant())
                .as("the policy casts this text to bigint, so it must be the id and nothing "
                        + "else — no padding, no prefix, no display formatting")
                .isEqualTo(Long.toString(TENANT_A));
        assertThat(underTenant.isHost())
                .as("a tenant transaction that ALSO carried app.is_host='on' would satisfy the host "
                        + "branch of the policy and see every tenant's rows")
                .isNullOrEmpty();
        assertThat(underTenant.control())
                .as("control: current_setting(name, true) reports absence as NULL, so the value "
                        + "asserted above is one the aspect really wrote")
                .isNull();
    }

    @Test
    void hostContextIsPublishedAsTheHostFlagWithNoTenant() {
        Settings underHost = inTenantContext(null, this::readSettings);

        assertThat(underHost.isHost())
                .as("ADR-0018: the host branch of the policy compares this literal")
                .isEqualTo(HibernateTenantFilterAspect.IS_HOST_ON);
        assertThat(underHost.currentTenant())
                .as("a host transaction must not also name a tenant")
                .isNullOrEmpty();
        assertThat(underHost.control()).isNull();
    }

    // ---------------------------------------------------------------------------------------
    // A context switch inside one transaction (nested @Service calls)
    // ---------------------------------------------------------------------------------------

    /**
     * The dangerous direction. {@code TenantAdminBootstrapper} and the account flows switch
     * {@link TenantContext} in the middle of a host transaction and then call tenant-scoped services;
     * if the host flag survived that switch, the policy's {@code OR is_host = 'on'} branch would be
     * true while a tenant is on the connection — every row of every tenant, authorized.
     */
    @Test
    void aTenantCallNestedInsideAHostTransactionClearsTheHostFlag() {
        Settings afterSwitch = transactions().execute(status ->
                inFrame(null, () -> inFrame(TENANT_B, this::readSettings)));

        assertThat(afterSwitch).isNotNull();
        assertThat(afterSwitch.isHost())
                .as("the host flag must not survive a switch into a tenant, or tenant isolation is "
                        + "gone for the rest of the transaction")
                .isNullOrEmpty();
        assertThat(afterSwitch.currentTenant()).isEqualTo(Long.toString(TENANT_B));
    }

    /** The mirror image: returning to host must not leave the tenant named on the connection. */
    @Test
    void aHostCallNestedInsideATenantTransactionClearsTheTenantSetting() {
        Settings afterSwitch = transactions().execute(status ->
                inFrame(TENANT_A, () -> inFrame(null, this::readSettings)));

        assertThat(afterSwitch).isNotNull();
        assertThat(afterSwitch.currentTenant())
                .as("a stale tenant here would silently narrow every later host read in this "
                        + "transaction to that one tenant")
                .isNullOrEmpty();
        assertThat(afterSwitch.isHost()).isEqualTo(HibernateTenantFilterAspect.IS_HOST_ON);
    }

    // ---------------------------------------------------------------------------------------
    // Coming BACK from a nested call
    // ---------------------------------------------------------------------------------------

    /**
     * Host cross-tenant reads are product behaviour ({@code TenantFilterCoverageIT} asserts both
     * directions), so a host flow that came back from a tenant-scoped call with
     * {@code app.current_tenant} still set would not be a leak — it would be a host auditor staring
     * at an empty screen for the rest of the transaction.
     */
    @Test
    void returningFromANestedTenantCallPutsTheHostContextBack() {
        Settings backInHost = transactions().execute(status -> inFrame(null, () -> {
            Settings insideNested = inFrame(TENANT_B, this::readSettings);
            assertThat(insideNested.currentTenant())
                    .as("control: the nested frame really did switch the connection, so the assertion "
                            + "below is about a value that was restored and not about one that never "
                            + "changed")
                    .isEqualTo(Long.toString(TENANT_B));
            return readSettings();
        }));

        assertThat(backInHost).isNotNull();
        assertThat(backInHost.isHost())
                .as("the caller is host and its next statement runs on this connection")
                .isEqualTo(HibernateTenantFilterAspect.IS_HOST_ON);
        assertThat(backInHost.currentTenant()).isNullOrEmpty();
        assertThat(backInHost.control()).isNull();
    }

    /** A → B → A: the tenant the caller named must be the tenant the connection names again. */
    @Test
    void returningFromANestedCallToAnotherTenantPutsTheCallersTenantBack() {
        Settings backInTenantA = transactions().execute(status -> inFrame(TENANT_A, () -> {
            Settings insideNested = inFrame(TENANT_B, this::readSettings);
            assertThat(insideNested.currentTenant()).isEqualTo(Long.toString(TENANT_B));
            return readSettings();
        }));

        assertThat(backInTenantA).isNotNull();
        assertThat(backInTenantA.currentTenant())
                .as("without the restore the caller would keep reading and writing as tenant %d for "
                        + "the rest of the transaction", TENANT_B)
                .isEqualTo(Long.toString(TENANT_A));
        assertThat(backInTenantA.isHost()).isNullOrEmpty();
        assertThat(backInTenantA.control()).isNull();
    }

    /** The restore has to be in a {@code finally}: the interesting nested calls are the failing ones. */
    @Test
    void aNestedCallThatThrowsStillPutsTheCallersContextBack() {
        Settings backInTenantA = transactions().execute(status -> inFrame(TENANT_A, () -> {
            assertThatThrownBy(() -> inFrame(TENANT_B, () -> {
                throw new IllegalStateException(NESTED_FAILURE);
            })).hasRootCauseMessage(NESTED_FAILURE);
            return readSettings();
        }));

        assertThat(backInTenantA).isNotNull();
        assertThat(backInTenantA.currentTenant()).isEqualTo(Long.toString(TENANT_A));
        assertThat(backInTenantA.isHost()).isNullOrEmpty();
    }

    /**
     * A restore is a statement, and a statement cannot run in a transaction PostgreSQL has already
     * aborted — every later command in it is refused with {@code 25P02}. Thrown out of the aspect's
     * {@code finally}, that refusal would become the exception the caller sees, so a constraint
     * violation would arrive as "current transaction is aborted" and every test asserting on the real
     * cause (409, 400, the policy's own {@code row-level security policy} text) would go red for a
     * reason that has nothing to do with what failed.
     */
    @Test
    void aRestoreThatCannotRunDoesNotReplaceTheFailureThatCausedIt() {
        Throwable thrown = catchThrowable(() -> transactions().execute(status ->
                inFrame(TENANT_A, () -> inFrame(TENANT_B, () ->
                        jdbcTemplate.queryForObject(ABORT_TRANSACTION_SQL, Integer.class)))));

        assertThat(causeChainMessages(thrown))
                .as("the refused restore may ride along as a suppressed exception, but it must not "
                        + "BECOME the failure: a caller that turns a constraint violation into 409 "
                        + "would turn this into 500, and every IT asserting on the real cause would go "
                        + "red for a reason unrelated to what failed")
                .noneMatch(message -> message.contains(TRANSACTION_ABORTED))
                .as("control: without this the assertion above would also pass on a transaction that "
                        + "never failed, and therefore never reached the aborted state at all")
                .anyMatch(message -> message.contains(DIVISION_BY_ZERO));
    }

    // ---------------------------------------------------------------------------------------
    // The pool boundary — the reason is_local=true is not optional
    // ---------------------------------------------------------------------------------------

    @Test
    void theTenantSettingDoesNotSurviveTheTransaction() {
        Settings armed = inTenantContext(TENANT_A, this::readSettings);
        assertThat(armed.currentTenant()).isEqualTo(Long.toString(TENANT_A));

        Settings sameConnectionLater = borrowUntilBackendIs(armed.backendPid());

        assertThat(sameConnectionLater)
                .as("no borrow in %d attempts came back on backend pid %d, so this test could only "
                        + "have asserted on a connection that never carried the setting — which would "
                        + "certify nothing. Check whether the pool or the test's threading changed.",
                        POOL_BORROW_BUDGET, armed.backendPid())
                .isNotNull();
        assertThat(sameConnectionLater.currentTenant())
                .as("SAME physical connection, later transaction: with is_local=false the tenant id "
                        + "would still be here and the next request would inherit it")
                .isNullOrEmpty();
        assertThat(sameConnectionLater.isHost())
                .as("and the host flag must not be inherited either — that one grants, not narrows")
                .isNullOrEmpty();
    }

    /**
     * A transaction that never passes through the aspect must see no tenant at all. This is what makes
     * the policies fail-closed: absent setting → {@code tenant_id = NULL} → 0 rows.
     */
    @Test
    void aTransactionThatSkipsTheAspectCarriesNoTenantDecision() {
        Settings plain = transactions().execute(status -> readSettings());

        assertThat(plain).isNotNull();
        assertThat(plain.currentTenant())
                .as("only the aspect may name a tenant; anything else is the environment leaking in")
                .isNullOrEmpty();
        assertThat(plain.isHost()).isNullOrEmpty();
        assertThat(plain.control()).isNull();
    }

    // --- probing ---

    /**
     * The two settings plus the backend process that answered. {@code currentTenant} and
     * {@code isHost} arrive as NULL on a connection that has never carried them and as the empty
     * string once a transaction has reverted them — Postgres resets a custom setting to its default
     * (empty), not to unset, which is why every assertion above accepts either.
     */
    private record Settings(String currentTenant, String isHost, String control, long backendPid) {
    }

    /**
     * Every message on the CAUSE chain, outermost first. The cause chain rather than the root cause:
     * pgjdbc hangs the failure that aborted the transaction underneath the {@code 25P02} refusal, so
     * the root cause is "division by zero" either way and would hide a restore that replaced the
     * failure the caller must see (measured — this is why the first version of the test below passed
     * against an unguarded {@code finally}).
     */
    private static List<String> causeChainMessages(Throwable thrown) {
        List<String> messages = new ArrayList<>();
        for (Throwable link = thrown; link != null; link = link.getCause()) {
            messages.add(String.valueOf(link.getMessage()));
        }
        return messages;
    }

    private Settings readSettings() {
        return jdbcTemplate.queryForObject(PROBE_SQL, (rs, rowNum) -> new Settings(
                rs.getString("current_tenant"),
                rs.getString("is_host"),
                rs.getString("control"),
                rs.getLong("backend_pid")));
    }

    /** Borrows fresh transactions until one lands on {@code backendPid}, or gives up. */
    private Settings borrowUntilBackendIs(long backendPid) {
        for (int attempt = 0; attempt < POOL_BORROW_BUDGET; attempt++) {
            Settings probe = transactions().execute(status -> readSettings());
            if (probe != null && probe.backendPid() == backendPid) {
                return probe;
            }
        }
        return null;
    }

    // --- driving the production aspect ---

    private TransactionTemplate transactions() {
        if (transactionTemplate == null) {
            transactionTemplate = new TransactionTemplate(transactionManager);
        }
        return transactionTemplate;
    }

    /**
     * Runs {@code read} inside a transaction with the tenant context set, driving the production
     * {@link HibernateTenantFilterAspect}. A {@code null} tenant id means host.
     */
    private <T> T inTenantContext(Long tenantId, Supplier<T> read) {
        return transactions().execute(status -> inFrame(tenantId, read));
    }

    /**
     * One {@code @Service} frame: sets the context and runs {@code body} INSIDE the aspect. Must be
     * called inside an active transaction — outside one the aspect writes nothing, on purpose.
     *
     * <p>The body runs inside rather than after the aspect because that is the only place the settings
     * describe this frame: the aspect puts the CALLER's context back on the way out, so a probe taken
     * once the frame returned would be measuring the caller. Nesting two of these is how this IT
     * reproduces a cross-tenant flow ({@code TenantAdminBootstrapper}, the account flows) — the
     * production shape is a {@code @Service} that switches {@link TenantContext} and calls another
     * {@code @Service}.
     */
    private <T> T inFrame(Long tenantId, Supplier<T> body) {
        Long caller = TenantContext.getTenantId();
        if (tenantId == null) {
            TenantContext.clear();
        } else {
            TenantContext.setTenantId(tenantId);
        }
        try {
            return throughAspect(body);
        } finally {
            // The Java-side half of the same discipline, done here the way the production
            // cross-tenant flows do it in their own finally.
            if (caller == null) {
                TenantContext.clear();
            } else {
                TenantContext.setTenantId(caller);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T throughAspect(Supplier<T> body) {
        ProceedingJoinPoint joinPoint = (ProceedingJoinPoint) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{ProceedingJoinPoint.class},
                (proxy, method, args) -> "proceed".equals(method.getName()) ? body.get() : null);
        try {
            return (T) tenantFilterAspect.applyTenantFilter(joinPoint);
        } catch (Throwable throwable) {
            throw new IllegalStateException("tenant filter aspect failed", throwable);
        }
    }
}
