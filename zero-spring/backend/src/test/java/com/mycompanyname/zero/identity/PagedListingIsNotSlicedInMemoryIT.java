package com.mycompanyname.zero.identity;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.AbstractIntegrationIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q-03 — the paged listings must be paginated by the DATABASE, not by the JVM.
 *
 * <p><b>The fault.</b> {@code @EntityGraph} (a collection fetch) on the same repository method as a
 * {@code Pageable} asks Hibernate for something SQL cannot express: a {@code LIMIT} over a join that
 * multiplies rows. Hibernate does not fail — it logs {@code HHH90003004: firstResult/maxResults
 * specified with collection fetch; applying in memory}, reads EVERY matching row, and slices the
 * list in Java. With the handful of rows an IT creates this is invisible; with fifty thousand users
 * every page request drags the whole table through the heap and {@code size=20} stops being a bound
 * on anything.
 *
 * <p><b>Why a log assertion.</b> The defect has no functional symptom: the same page comes back,
 * with the same rows, in the same order. Counting rows or comparing bodies cannot see it. The single
 * observable difference is the warning Hibernate emits on {@code org.hibernate.orm.query} — so that
 * is what this test reads, with the {@code ListAppender} technique {@code ClientErrorLogBudgetIT}
 * already uses on the exception handler.
 *
 * <p><b>Why the control test exists.</b> A test that asserts the ABSENCE of a log line passes just
 * as happily when it is listening to the wrong logger, or when the level filters the event out — the
 * exact "green but verifying nothing" failure this repo keeps finding. {@link
 * #theCaptureItselfWouldSeeTheWarningIfHibernateEmittedOne()} emits the real message on Hibernate's
 * real category and asserts it is caught, so the silence asserted above is measured silence.
 *
 * <p><b>Why ordering has a test of its own.</b> The fix pages the ids first and fetches the
 * associations for those ids second. {@code where id in (:ids)} carries NO order guarantee: the page
 * comes back with the right rows in an arbitrary order unless the id order is restored explicitly.
 * Nothing else in the suite would notice — the count is right, the contents are right, the totals
 * are right, and a sorted list silently stops being sorted. See {@link
 * #theRequestedSortOrderSurvivesTheSecondQuery()}.
 */
class PagedListingIsNotSlicedInMemoryIT extends AbstractIntegrationIT {

    /**
     * Hibernate 6.6's own category for query-subsystem messages:
     * {@code SubSystemLogging.BASE ("org.hibernate.orm") + ".query"}, declared on
     * {@code org.hibernate.query.QueryLogging}, which is where message 90003004 is defined at
     * {@code WARN}.
     */
    private static final String HIBERNATE_QUERY_LOGGER = "org.hibernate.orm.query";

    private static final String IN_MEMORY_PAGINATION_ID = "HHH90003004";
    private static final String IN_MEMORY_PAGINATION_TEXT = "applying in memory";

    private static final String DEFAULT_TENANT = "default";
    private static final AtomicInteger SEQ = new AtomicInteger();

    private Logger hibernateQueryLogger;
    private Level originalLevel;
    private ListAppender<ILoggingEvent> captured;

    @BeforeEach
    void captureHibernateQueryLog() {
        hibernateQueryLogger = (Logger) LoggerFactory.getLogger(HIBERNATE_QUERY_LOGGER);
        // Pinned rather than inherited: if a future logging change raised this category above WARN,
        // every assertion below would pass by seeing nothing at all.
        originalLevel = hibernateQueryLogger.getLevel();
        hibernateQueryLogger.setLevel(Level.WARN);
        captured = new ListAppender<>();
        captured.start();
        hibernateQueryLogger.addAppender(captured);
    }

    @AfterEach
    void releaseHibernateQueryLog() {
        hibernateQueryLogger.detachAppender(captured);
        captured.stop();
        hibernateQueryLogger.setLevel(originalLevel);
    }

    private static boolean isInMemoryPagination(ILoggingEvent event) {
        String message = event.getFormattedMessage();
        return message != null
                && (message.contains(IN_MEMORY_PAGINATION_ID)
                || message.contains(IN_MEMORY_PAGINATION_TEXT));
    }

    private void assertPaginatedInTheDatabase(String what) {
        assertThat(captured.list)
                .as("%s made Hibernate paginate in memory: it read every matching row and sliced the "
                        + "list in Java. The page size stops bounding anything the moment the table "
                        + "is large. Captured: %s",
                        what, captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList())
                .noneMatch(PagedListingIsNotSlicedInMemoryIT::isInMemoryPagination);
    }

    /**
     * Every paged listing owned by identity, in both scopes, with and without a search term, on the
     * first page and a later one. Enumerated rather than sampled: the four repository methods behind
     * these URLs were four separate copies of the same mistake.
     */
    @Test
    void noPagedListingAsksHibernateToPaginateInMemory() {
        HttpHeaders tenant = tenantAdmin();
        HttpHeaders host = hostAdmin();
        String prefix = unique("paged");
        createUser(tenant, prefix + "a");
        createUser(tenant, prefix + "b");
        createUser(tenant, prefix + "c");
        createUser(host, prefix + "h");
        createRole(tenant, prefix + "role1");
        createRole(tenant, prefix + "role2");

        for (String path : List.of(
                // tenant scope, no search term
                "/api/users?page=0&size=2",
                "/api/users?page=1&size=2",
                "/api/users?page=0&size=2&sort=username,asc",
                // tenant scope, with a search term
                "/api/users?page=0&size=2&search=" + prefix,
                "/api/users?page=1&size=2&search=" + prefix,
                // roles
                "/api/roles?page=0&size=2",
                "/api/roles?page=1&size=2&sort=name,asc")) {
            assertThat(get(tenant, path).getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
        }

        for (String path : List.of(
                // host scope (tenant_id is null) walks a different repository method
                "/api/users?page=0&size=2",
                "/api/users?page=1&size=2&sort=username,asc",
                "/api/users?page=0&size=2&search=" + prefix,
                "/api/roles?page=0&size=2")) {
            assertThat(get(host, path).getStatusCode()).as("host " + path).isEqualTo(HttpStatus.OK);
        }

        assertPaginatedInTheDatabase("a paged listing");
    }

    /**
     * CONTROL. Proves the assertion above is capable of failing: the same message Hibernate would
     * emit, on the same category, at the same level, must reach the appender and match the predicate.
     * Without this, attaching to a misspelled logger would produce a permanently green test that
     * checks nothing — the failure mode that made five gates in this repo worthless.
     */
    @Test
    void theCaptureItselfWouldSeeTheWarningIfHibernateEmittedOne() {
        LoggerFactory.getLogger(HIBERNATE_QUERY_LOGGER)
                .warn("HHH90003004: firstResult/maxResults specified with collection fetch; "
                        + "applying in memory");

        assertThat(captured.list)
                .as("the capture is wired to Hibernate's category and level; if this fails, the "
                        + "silence the other tests assert means nothing")
                .anyMatch(PagedListingIsNotSlicedInMemoryIT::isInMemoryPagination);
    }

    /**
     * THE SILENT BUG of the two-stage fix. Stage 1 pages the ids in the requested order; stage 2
     * fetches those ids with {@code where id in (:ids)}, which returns them in whatever order the
     * database finds convenient. Forget to restore the order and the page holds exactly the right
     * rows, exactly the right count, exactly the right totals — shuffled. No count assertion
     * anywhere can see it, and the user sees a "sorted" list that is not sorted.
     *
     * <p>The fixture is built so that creation order (and therefore id order, and therefore the
     * order an unordered {@code in} query tends to return) is NOT the sorted order: an
     * implementation that skips the reordering step returns {@code e, c, a, d, b}.
     */
    @Test
    void theRequestedSortOrderSurvivesTheSecondQuery() {
        HttpHeaders tenant = tenantAdmin();
        String prefix = unique("sortprobe");
        for (String suffix : List.of("e", "c", "a", "d", "b")) {
            createUser(tenant, prefix + suffix);
        }

        assertThat(usernames(tenant, "/api/users?search=" + prefix + "&sort=username,asc&page=0&size=2"))
                .as("page 0 ascending — a page assembled from an unordered second query returns the "
                        + "right rows in the wrong order, and only this assertion notices")
                .containsExactly(prefix + "a", prefix + "b");
        assertThat(usernames(tenant, "/api/users?search=" + prefix + "&sort=username,asc&page=1&size=2"))
                .as("page 1 ascending")
                .containsExactly(prefix + "c", prefix + "d");
        assertThat(usernames(tenant, "/api/users?search=" + prefix + "&sort=username,asc&page=2&size=2"))
                .as("last, partial page ascending")
                .containsExactly(prefix + "e");

        // Descending is not redundant: it is the direction that cannot be produced by accident from
        // insertion order, so it fails even where ascending happened to match the physical order.
        assertThat(usernames(tenant, "/api/users?search=" + prefix + "&sort=username,desc&page=0&size=3"))
                .as("descending page 0")
                .containsExactly(prefix + "e", prefix + "d", prefix + "c");
        assertThat(usernames(tenant, "/api/users?search=" + prefix + "&sort=username,desc&page=1&size=3"))
                .as("descending page 1")
                .containsExactly(prefix + "b", prefix + "a");
    }

    /** The same ordering guarantee on the roles listing, which pages a different repository. */
    @Test
    void theRoleListingComesBackInTheRequestedOrder() {
        HttpHeaders tenant = tenantAdmin();
        String prefix = unique("roleorder");
        for (String suffix : List.of("c", "a", "b")) {
            createRole(tenant, prefix + suffix);
        }

        // Sorted globally rather than within the fixture, so this holds regardless of what sibling
        // ITs have left in the table.
        List<String> names = fieldValues(tenant, "/api/roles?sort=name,asc&page=0&size=200", "name");

        assertThat(names).as("roles?sort=name,asc must be sorted").isSorted();
        assertThat(names).contains(prefix + "a", prefix + "b", prefix + "c");
        assertThat(names.indexOf(prefix + "a")).isLessThan(names.indexOf(prefix + "b"));
        assertThat(names.indexOf(prefix + "b")).isLessThan(names.indexOf(prefix + "c"));
    }

    /**
     * Page arithmetic: the totals reported to the caller, the size of each page, and the fact that
     * walking the pages visits every row exactly once. A two-stage implementation has two chances to
     * get {@code totalElements} wrong — it must come from the id query (the true count), not from the
     * hydrated rows (the page's own size).
     */
    @Test
    void pageSizeTotalsAndBoundariesAreExact() {
        HttpHeaders tenant = tenantAdmin();
        String prefix = unique("pagemath");
        for (int i = 0; i < 5; i++) {
            createUser(tenant, prefix + i);
        }

        JsonNode first = body(tenant, "/api/users?search=" + prefix + "&sort=username,asc&page=0&size=2");
        assertThat(first.path("totalElements").asLong())
                .as("totalElements must count every matching row, not the rows on this page")
                .isEqualTo(5);
        assertThat(first.path("totalPages").asInt()).isEqualTo(3);
        assertThat(pageContent(first).size()).as("a full page").isEqualTo(2);

        JsonNode last = body(tenant, "/api/users?search=" + prefix + "&sort=username,asc&page=2&size=2");
        assertThat(last.path("totalElements").asLong()).isEqualTo(5);
        assertThat(pageContent(last).size()).as("the remainder page").isEqualTo(1);

        JsonNode past = body(tenant, "/api/users?search=" + prefix + "&sort=username,asc&page=9&size=2");
        assertThat(pageContent(past).size()).as("a page past the end is empty, not an error").isZero();
        assertThat(past.path("totalElements").asLong())
                .as("an empty page still reports the true total")
                .isEqualTo(5);

        List<String> walked = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            walked.addAll(usernames(tenant,
                    "/api/users?search=" + prefix + "&sort=username,asc&page=" + page + "&size=2"));
        }
        assertThat(walked)
                .as("walking the pages must visit every row exactly once — no gap, no repeat")
                .hasSize(5)
                .doesNotHaveDuplicates()
                .containsExactly(prefix + "0", prefix + "1", prefix + "2", prefix + "3", prefix + "4");
    }

    /**
     * The collection is still fetched. This is the assertion that stops "we fixed it" from meaning
     * "we stopped loading the data": {@code UserDto.roles} is built from {@code user.getRoles()},
     * so a page whose rows lost their roles would come back with empty arrays (the service maps
     * inside its own read-only transaction, so a missing fetch degrades quietly to lazy loads or
     * empty sets rather than throwing).
     */
    @Test
    void everyRowOnAPageStillCarriesItsRoles() {
        HttpHeaders tenant = tenantAdmin();
        String prefix = unique("withroles");
        createUser(tenant, prefix + "a");
        createUser(tenant, prefix + "b");

        for (JsonNode user : pageContent(body(tenant,
                "/api/users?search=" + prefix + "&sort=username,asc&page=0&size=2"))) {
            List<String> roles = new ArrayList<>();
            user.path("roles").forEach(role -> roles.add(role.asText()));
            assertThat(roles)
                    .as("%s came back without its roles — the page is cheap because it is incomplete",
                            user.path("username").asText())
                    .containsExactly("Admin");
        }

        // The role detail endpoint is the counterpart on the roles side: the listing never exposed
        // permissions, but the detail view must still resolve the element collection.
        String roleName = unique("permcheck");
        long roleId = createRole(tenant, roleName).path("id").asLong();
        List<String> permissions = new ArrayList<>();
        body(tenant, "/api/roles/" + roleId).path("permissions").forEach(p -> permissions.add(p.asText()));
        assertThat(permissions)
                .as("role detail must still carry its permissions")
                .contains("users.read");
    }

    /**
     * Tenant isolation across the two-stage query. Stage 2 looks rows up BY ID; if that lookup were
     * not tenant-scoped as well, an id from anywhere would hydrate. The ids come from a tenant-scoped
     * stage 1 today, so this asserts the property rather than a specific mistake — a leak here
     * returns 200 with extra rows and no positive test would ever notice.
     */
    @Test
    void aPagedListingNeverHydratesARowFromAnotherScope() {
        HttpHeaders tenant = tenantAdmin();
        HttpHeaders host = hostAdmin();
        String prefix = unique("scoped");
        createUser(host, prefix + "hostonly");
        createUser(tenant, prefix + "tenantonly");

        long tenantId = body(tenant, "/api/auth/me").path("tenantId").asLong();
        assertThat(tenantId).isPositive();

        for (int page = 0; page < 3; page++) {
            for (JsonNode user : pageContent(body(tenant, "/api/users?page=" + page + "&size=50"))) {
                JsonNode scope = user.path("tenantId");
                assertThat(scope.isNull() || scope.isMissingNode())
                        .as("a host-scoped user surfaced on page %s of a tenant listing", page)
                        .isFalse();
                assertThat(scope.asLong())
                        .as("a foreign tenant's user surfaced on page %s of a tenant listing", page)
                        .isEqualTo(tenantId);
            }
        }

        assertThat(usernames(tenant, "/api/users?search=" + prefix + "&page=0&size=50"))
                .as("the host-scoped user must not be reachable through the tenant's search either")
                .containsExactly(prefix + "tenantonly");
    }

    // --- helpers ---

    private HttpHeaders tenantAdmin() {
        return bearerHeaders(
                accessToken(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), DEFAULT_TENANT);
    }

    private HttpHeaders hostAdmin() {
        return bearerHeaders(accessToken(null, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), null);
    }

    /** Short enough to leave room in the 64-char username column once a suffix is appended. */
    private String unique(String prefix) {
        return prefix + Long.toString(System.nanoTime(), 36) + SEQ.incrementAndGet() + "_";
    }

    private ResponseEntity<JsonNode> get(HttpHeaders headers, String path) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }

    private JsonNode body(HttpHeaders headers, String path) {
        ResponseEntity<JsonNode> response = get(headers, path);
        assertThat(response.getStatusCode()).as("GET %s -> %s", path, response.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private List<String> usernames(HttpHeaders headers, String path) {
        return fieldValues(headers, path, "username");
    }

    private List<String> fieldValues(HttpHeaders headers, String path, String field) {
        List<String> values = new ArrayList<>();
        pageContent(body(headers, path)).forEach(node -> values.add(node.path(field).asText()));
        return values;
    }

    private void createUser(HttpHeaders headers, String username) {
        ResponseEntity<JsonNode> created = restTemplate.exchange("/api/users", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "username", username,
                        "email", username + "@example.com",
                        "password", "Password123!",
                        "roleNames", Set.of("Admin")), headers), JsonNode.class);
        assertThat(created.getStatusCode())
                .as("fixture user %s must be created, got %s", username, created.getBody())
                .isEqualTo(HttpStatus.CREATED);
    }

    private JsonNode createRole(HttpHeaders headers, String name) {
        ResponseEntity<JsonNode> created = restTemplate.exchange("/api/roles", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "name", name,
                        "displayName", name,
                        "permissions", Set.of("users.read"),
                        "isDefault", false), headers), JsonNode.class);
        assertThat(created.getStatusCode())
                .as("fixture role %s must be created, got %s", name, created.getBody())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        return created.getBody();
    }
}
