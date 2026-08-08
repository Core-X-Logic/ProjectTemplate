package com.mycompanyname.zero.tenancy;

import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.AbstractIntegrationIT;
import com.mycompanyname.zero.audit.domain.AuditLog;
import com.mycompanyname.zero.audit.domain.AuditLogRepository;
import com.mycompanyname.zero.audit.domain.EntityChange;
import com.mycompanyname.zero.audit.domain.EntityChangeRepository;
import com.mycompanyname.zero.audit.domain.EntityChangeType;
import com.mycompanyname.zero.notification.NotificationLevel;
import com.mycompanyname.zero.notification.NotificationService;
import com.mycompanyname.zero.notification.domain.UserNotification;
import com.mycompanyname.zero.shared.tenant.TenantContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the Hibernate tenant {@code @Filter} on {@code AuditLog}, {@code EntityChange} and
 * {@code UserNotification} in BOTH directions, because closing only one of them is a regression.
 *
 * <p><b>Why this test does not go through {@code /api/audit-logs}.</b> {@code AuditLogService}
 * already adds an explicit {@code tenantId = :current} predicate to both of its specifications, so an
 * HTTP-level isolation test passes identically with and without the {@code @Filter} — it would be
 * green for the wrong reason and would certify nothing. The filter is the SECOND line of defence:
 * the one that covers a query which forgot the predicate. To measure it, the reads below deliberately
 * use a specification that carries NO tenant predicate at all — only a per-run marker. If the
 * annotation is removed from the entity, these reads return the other tenant's rows.
 *
 * <p>The filter is switched on by invoking the production {@link HibernateTenantFilterAspect}
 * itself (through a minimal {@link ProceedingJoinPoint}) rather than by re-implementing its
 * decision here, so the test cannot drift from the aspect it is meant to exercise.
 *
 * <p>{@code UserNotification} needs none of that machinery: {@code NotificationService.list} keys on
 * the global {@code userId} alone and carries no tenant predicate, so it is already a read path
 * where the filter alone decides. That one is exercised through the real service.
 *
 * <p><b>The host direction.</b> None of the three entities declares {@code hostFilter}
 * ({@code tenant_id is null}), and each for its own reason (see the individual tests). Adding it
 * would silently break cross-tenant audit review, which no positive test would notice — hence the
 * explicit "host still sees tenant rows" assertions.
 */
class TenantFilterCoverageIT extends AbstractIntegrationIT {

    private static final String DEFAULT_TENANT = "default";

    /**
     * Tenant ids that deliberately match no row in {@code tenants}. Neither {@code audit_logs} nor
     * {@code entity_changes} has a foreign key on {@code tenant_id}, and using synthetic ids keeps
     * the seeded rows invisible to every other IT sharing this Spring context.
     */
    private static final long TENANT_A = 900_001L;
    private static final long TENANT_B = 900_002L;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private EntityChangeRepository entityChangeRepository;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private HibernateTenantFilterAspect tenantFilterAspect;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    private TransactionTemplate transactions() {
        if (transactionTemplate == null) {
            transactionTemplate = new TransactionTemplate(transactionManager);
        }
        return transactionTemplate;
    }

    /** The context is a ThreadLocal shared with every later test on this thread. */
    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    // ---------------------------------------------------------------------------------------
    // AuditLog
    // ---------------------------------------------------------------------------------------

    /**
     * Host must keep seeing every tenant's audit rows: cross-tenant audit review is the feature.
     * That is why {@code AuditLog} declares {@code tenantFilter} and NOT {@code hostFilter}.
     */
    @Test
    void auditLogsOfAnotherTenantAreInvisibleToATenantAndStillVisibleToHost() {
        String marker = unique("tfcov_audit");
        seedAuditLog(TENANT_A, marker);
        seedAuditLog(TENANT_B, marker);

        List<Long> seenByTenantA = inTenantContext(TENANT_A,
                () -> tenantIdsOf(auditLogRepository.findAll(auditLogsMarked(marker))));
        assertThat(seenByTenantA)
                .as("tenant A read an audit_logs query that carries NO tenant predicate; only the "
                        + "Hibernate @Filter can keep tenant B's row out of this result")
                .containsExactly(TENANT_A);

        List<Long> seenByHost = inTenantContext(null,
                () -> tenantIdsOf(auditLogRepository.findAll(auditLogsMarked(marker))));
        assertThat(seenByHost)
                .as("host must still see every tenant's audit rows — declaring hostFilter on AuditLog "
                        + "would break cross-tenant audit review and no positive test would notice")
                .containsExactlyInAnyOrder(TENANT_A, TENANT_B);
    }

    /**
     * The same host guarantee, end to end through the real HTTP surface and the real service, so a
     * regression is caught even if the repository-level test above is ever rewritten.
     */
    @Test
    void hostAuditReviewStillReturnsTenantScopedRowsOverHttp() {
        String marker = unique("tfcov_http");
        seedAuditLog(TENANT_A, marker);
        seedAuditLog(TENANT_B, marker);

        HttpHeaders hostHeaders = bearerHeaders(accessToken(null, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), null);
        ResponseEntity<JsonNode> hostView = restTemplate.exchange(
                "/api/audit-logs?userName=" + marker + "&page=0&size=100",
                HttpMethod.GET, new HttpEntity<>(hostHeaders), JsonNode.class);
        assertThat(hostView.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(tenantIdsInPage(hostView.getBody()))
                .as("a host auditor must be able to review both tenants' rows")
                .containsExactlyInAnyOrder(TENANT_A, TENANT_B);

        HttpHeaders tenantHeaders = bearerHeaders(
                accessToken(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), DEFAULT_TENANT);
        ResponseEntity<JsonNode> tenantView = restTemplate.exchange(
                "/api/audit-logs?userName=" + marker + "&page=0&size=100",
                HttpMethod.GET, new HttpEntity<>(tenantHeaders), JsonNode.class);
        assertThat(tenantView.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tenantIdsInPage(tenantView.getBody()))
                .as("the default tenant owns none of the marked rows")
                .isEmpty();
    }

    // ---------------------------------------------------------------------------------------
    // EntityChange
    // ---------------------------------------------------------------------------------------

    /**
     * {@code EntityChange} answers "who changed this record?". Host support answers it across
     * tenants, so the decision matches {@code AuditLog}: {@code tenantFilter} only.
     */
    @Test
    void entityChangesOfAnotherTenantAreInvisibleToATenantAndStillVisibleToHost() {
        String marker = unique("tfcov_change");
        seedEntityChange(TENANT_A, marker);
        seedEntityChange(TENANT_B, marker);

        List<Long> seenByTenantA = inTenantContext(TENANT_A,
                () -> tenantIdsOfChanges(entityChangeRepository.findAll(entityChangesMarked(marker))));
        assertThat(seenByTenantA)
                .as("tenant A read an entity_changes query that carries NO tenant predicate; only the "
                        + "Hibernate @Filter can keep tenant B's history out of this result")
                .containsExactly(TENANT_A);

        List<Long> seenByHost = inTenantContext(null,
                () -> tenantIdsOfChanges(entityChangeRepository.findAll(entityChangesMarked(marker))));
        assertThat(seenByHost)
                .as("host must still see every tenant's change history")
                .containsExactlyInAnyOrder(TENANT_A, TENANT_B);
    }

    // ---------------------------------------------------------------------------------------
    // UserNotification
    // ---------------------------------------------------------------------------------------

    /**
     * The inbox is keyed on the GLOBAL {@code users.id}, so {@code NotificationService.list} has no
     * tenant predicate to fall back on — the filter is the only thing standing between a tenant and
     * a row tagged for another tenant.
     *
     * <p>No {@code hostFilter} here either, for a different reason than the audit entities:
     * {@code publish(userId, tenantId, ...)} takes the two independently, so a host recipient may
     * legitimately hold a row tagged with the tenant the alert is ABOUT. {@code tenant_id is null}
     * would hide such a notification from its own recipient — a silent delivery failure — while
     * buying nothing, since {@code userId} already isolates completely on every read path.
     */
    @Test
    void notificationsTaggedForAnotherTenantAreInvisibleAndHostSeesTheWholeInbox() {
        long userId = seedAdminUserId();
        String marker = unique("tfcov_notif");

        notificationService.publish(userId, TENANT_A, marker + "_a", NotificationLevel.INFO,
                "Tenant A alert", null, null);
        notificationService.publish(userId, TENANT_B, marker + "_b", NotificationLevel.INFO,
                "Tenant B alert", null, null);

        List<String> seenByTenantA = inTenantContext(TENANT_A, () -> markedNames(userId, marker));
        assertThat(seenByTenantA)
                .as("NotificationService.list filters on userId alone; without the Hibernate @Filter "
                        + "tenant A's inbox also returns the row tagged for tenant B")
                .containsExactly(marker + "_a");

        List<String> seenByHost = inTenantContext(null, () -> markedNames(userId, marker));
        assertThat(seenByHost)
                .as("host reads its own inbox whole — declaring hostFilter would silently drop any "
                        + "notification tagged with the tenant it is about")
                .containsExactlyInAnyOrder(marker + "_a", marker + "_b");
    }

    // --- fixtures ---

    private String unique(String prefix) {
        return prefix + "_" + System.nanoTime() + "_" + SEQ.incrementAndGet();
    }

    /**
     * {@code inTenantDatabase}: since V13 these tables are policed, and a test thread crosses no
     * {@code @Service} boundary — an unannounced insert would hit {@code WITH CHECK}, not the filter
     * under test. Announcing the SAME tenant the row is tagged with keeps this fixture a claim about
     * the {@code @Filter} and doubles as the tenant-branch positive control for the policy.
     */
    private void seedAuditLog(long tenantId, String marker) {
        inTenantDatabase(tenantId, () -> {
            AuditLog log = new AuditLog();
            log.setTenantId(tenantId);
            log.setUsername(marker);
            log.setServiceName(TenantFilterCoverageIT.class.getSimpleName());
            log.setMethodName("seed");
            log.setHttpMethod("GET");
            log.setUrl("/api/tenant-filter-coverage");
            log.setHttpStatusCode(200);
            log.setExecutionTime(Instant.now());
            log.setExecutionDurationMs(1);
            auditLogRepository.saveAndFlush(log);
        });
    }

    /** See {@link #seedAuditLog}. */
    private void seedEntityChange(long tenantId, String marker) {
        inTenantDatabase(tenantId, () -> {
            EntityChange change = new EntityChange();
            change.setTenantId(tenantId);
            change.setEntityTypeName(marker);
            change.setEntityId(String.valueOf(tenantId));
            change.setChangeType(EntityChangeType.UPDATED);
            change.setChangeTime(Instant.now());
            entityChangeRepository.saveAndFlush(change);
        });
    }

    /** The seeded host admin; reusing it avoids adding users to a tenant other tests count. */
    private long seedAdminUserId() {
        HttpHeaders hostHeaders = bearerHeaders(accessToken(null, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), null);
        ResponseEntity<JsonNode> me = restTemplate.exchange(
                "/api/auth/me", HttpMethod.GET, new HttpEntity<>(hostHeaders), JsonNode.class);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody()).isNotNull();
        long id = me.getBody().path("id").asLong();
        assertThat(id).isPositive();
        return id;
    }

    // --- reads under a tenant context ---

    private static Specification<AuditLog> auditLogsMarked(String marker) {
        return (root, query, cb) -> cb.equal(root.get("username"), marker);
    }

    private static Specification<EntityChange> entityChangesMarked(String marker) {
        return (root, query, cb) -> cb.equal(root.get("entityTypeName"), marker);
    }

    private static List<Long> tenantIdsOf(List<AuditLog> logs) {
        return logs.stream().map(AuditLog::getTenantId).sorted().toList();
    }

    private static List<Long> tenantIdsOfChanges(List<EntityChange> changes) {
        return changes.stream().map(EntityChange::getTenantId).sorted().toList();
    }

    private List<Long> tenantIdsInPage(JsonNode body) {
        return java.util.stream.StreamSupport.stream(pageContent(body).spliterator(), false)
                .map(entry -> entry.path("tenantId").asLong())
                .sorted()
                .toList();
    }

    /** Goes through the real {@code NotificationService}, so the production aspect wires itself. */
    private List<String> markedNames(long userId, String marker) {
        return notificationService.list(userId, PageRequest.of(0, 100)).getContent().stream()
                .map(UserNotification::getNotificationName)
                .filter(name -> name.startsWith(marker))
                .sorted()
                .toList();
    }

    /**
     * Runs {@code read} inside a transaction with the tenant context set, driving the production
     * {@link HibernateTenantFilterAspect} so the same filter decision the application makes at
     * runtime is the one under test. A {@code null} tenant id means host.
     */
    private <T> T inTenantContext(Long tenantId, Supplier<T> read) {
        Long previous = TenantContext.getTenantId();
        if (tenantId == null) {
            TenantContext.clear();
        } else {
            TenantContext.setTenantId(tenantId);
        }
        try {
            return transactions().execute(status -> throughAspect(read));
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.setTenantId(previous);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T throughAspect(Supplier<T> read) {
        ProceedingJoinPoint joinPoint = (ProceedingJoinPoint) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{ProceedingJoinPoint.class},
                (proxy, method, args) -> "proceed".equals(method.getName()) ? read.get() : null);
        try {
            return (T) tenantFilterAspect.applyTenantFilter(joinPoint);
        } catch (Throwable throwable) {
            throw new IllegalStateException("tenant filter aspect failed", throwable);
        }
    }
}
