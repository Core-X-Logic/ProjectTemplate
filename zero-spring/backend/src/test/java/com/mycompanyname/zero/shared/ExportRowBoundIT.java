package com.mycompanyname.zero.shared;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.AbstractIntegrationIT;
import com.mycompanyname.zero.audit.AuditLogService;
import com.mycompanyname.zero.audit.AuditPermissions;
import com.mycompanyname.zero.audit.domain.AuditLog;
import com.mycompanyname.zero.audit.domain.AuditLogRepository;
import com.mycompanyname.zero.identity.domain.AppPermissions;
import com.mycompanyname.zero.identity.domain.Role;
import com.mycompanyname.zero.identity.domain.User;
import com.mycompanyname.zero.identity.repo.RoleRepository;
import com.mycompanyname.zero.identity.repo.UserRepository;
import com.mycompanyname.zero.identity.user.UserService;
import com.mycompanyname.zero.shared.tenant.TenantContext;
import com.mycompanyname.zero.tenancy.Tenant;
import com.mycompanyname.zero.tenancy.TenantRepository;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W5-3 — the shared row bound on both xlsx exports, measured at the boundary and through the
 * controller.
 *
 * <p><b>What was wrong.</b> {@code UserService.exportToExcel} read the caller's entire scope and
 * then resolved every user's roles; {@code AuditLogService.export} read every row matching the
 * filter. Neither had a limit, and both then built an Apache POI workbook over the result — so the
 * heap cost of a request was a property of the table, not of the request. Both now fetch through
 * {@code BoundedExport}, which asks the database for {@code max-rows + 1} rows and refuses if the
 * extra one comes back.
 *
 * <p><b>Why the boundary and not just "too many".</b> An off-by-one here is not cosmetic: a bound
 * that refuses at exactly {@code max-rows} silently makes the configured number unreachable, and a
 * bound that allows {@code max-rows + 1} means the probe row is being served rather than counted.
 * Each export is therefore exercised at N (must succeed, in full) and at N+1 (must refuse), against
 * the same fixture, one row apart.
 *
 * <p><b>Why refusal and not truncation.</b> Asserted explicitly below: the over-limit call must not
 * return a 200 with a shorter workbook. A truncated export is the worse failure — it looks complete.
 *
 * <p><b>Why the limit is lowered instead of the fixture raised.</b> Seeding 10 000 rows twice would
 * add minutes to the suite and prove nothing the sixth row does not. The property is the thing under
 * test, so it is the thing configured.
 *
 * <p><b>Why a throwaway tenant.</b> The container is shared by every IT class, so the seeded tenants
 * accumulate whatever the rest of the suite creates and "exactly N users" cannot be asserted about
 * them. This class creates its own tenant per run and puts exactly N users in it. The audit half
 * needs no such thing — the {@code userName} filter narrows the export to rows this test wrote,
 * which is also why the interceptor's own rows (written under the fixture admin's name for every
 * request this test makes) cannot perturb the count.
 *
 * <p><b>Why the SQL is asserted as well</b> ({@link #theUserExportCarriesItsBoundIntoSql()},
 * {@link #theAuditLogExportCarriesItsBoundIntoSql()}). The boundary tests above lock down the
 * REFUSAL, and a gate audit proved that is not the same thing as locking down the BOUND: replacing
 * the fetch lambda with one that ignores the {@code Pageable} entirely — {@code Pageable.unpaged()},
 * read every row, apply the limit in Java afterwards — kept all four of them green, because
 * externally the behaviour is identical (200 at the limit, 400 one over). The heap risk this whole
 * change exists to close was therefore measured by nothing.
 * {@code PagedListingIsNotSlicedInMemoryIT} cannot see it either: there is no collection fetch here,
 * so Hibernate emits no {@code HHH90003004}. The only observable difference between "bounded" and
 * "bounded after the allocation" is the statement that reaches the database, so that is what the
 * two SQL tests read.</p>
 */
@TestPropertySource(properties = "zero.export.max-rows=" + ExportRowBoundIT.MAX_ROWS)
class ExportRowBoundIT extends AbstractIntegrationIT {

    static final int MAX_ROWS = 5;

    private static final String FIXTURE_PASSWORD = "Password123!";

    /**
     * Hibernate's own category for the statements it sends. {@code org.hibernate.SQL} is what
     * {@code SqlStatementLogger} writes to, guarded by {@code isDebugEnabled()} evaluated per
     * statement — which is why raising the level from inside the test works at all, and why it is
     * raised there rather than in {@code application-test.yml}: SQL logging for the whole suite
     * would slow every IT and bury the output that matters.
     */
    private static final String SQL_LOGGER = "org.hibernate.SQL";

    /** Column indexes in the two workbooks, as written by the services under test. */
    private static final int USER_ID_COLUMN = 0;
    private static final int AUDIT_USERNAME_COLUMN = 2;
    private static final int AUDIT_TENANT_ID_COLUMN = 3;

    @Autowired
    private UserService userService;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // ------------------------------------------------------------------ users

    @Test
    void theUserExportIsServedAtTheLimitAndRefusedOneRowOver() {
        String suffix = unique();
        Fixture fixture = freshTenantWithAdmin(suffix);
        // The fixture reader AND the bootstrapped tenant admin (Issue #1: tenant creation now
        // provisions one) are both users of the tenant, so N-2 fillers reach exactly the limit.
        List<Long> expectedIds = new ArrayList<>(
                List.of(fixture.adminUserId(), fixture.bootstrappedAdminId()));
        while (expectedIds.size() < MAX_ROWS) {
            expectedIds.add(insertUser(fixture.tenantId(), "u" + expectedIds.size() + suffix, Set.of()));
        }
        // Fixture guard, read back through the API rather than the repository: the boundary below
        // means nothing unless the tenant holds EXACTLY the limit at this point, and this test is
        // also run against the pre-fix code as negative evidence, where the export-specific
        // repository methods do not exist yet.
        assertThat(getJson(fixture.headers(), "/api/users?page=0&size=1").getBody()
                .path("totalElements").asInt())
                .as("fixture guard: the tenant must hold exactly %s users before the boundary is probed",
                        MAX_ROWS)
                .isEqualTo(MAX_ROWS);

        ResponseEntity<byte[]> atLimit = getBytes(fixture.headers(), "/api/users/export");
        assertThat(atLimit.getStatusCode())
                .as("exactly max-rows is within the bound and must be served in full")
                .isEqualTo(HttpStatus.OK);

        List<List<String>> rows = dataRows(atLimit);
        assertThat(rows)
                .as("\"served in full\" means N data rows in the sheet, not merely a 200 with a "
                        + "well-formed zip: a bound applied one row early, or a second stage that "
                        + "silently dropped rows, produces a shorter workbook and no status code "
                        + "notices")
                .hasSize(MAX_ROWS);
        assertThat(rows.stream().map(row -> row.get(USER_ID_COLUMN)).toList())
                .as("every exported row must belong to the fixture tenant. The export query was "
                        + "rewritten for this change and is exactly where a tenant predicate gets "
                        + "dropped later — an export that leaked another tenant's users answers 200 "
                        + "with a valid spreadsheet, and TenantIsolationIT has no export case")
                .containsExactlyInAnyOrderElementsOf(
                        expectedIds.stream().map(String::valueOf).toList());

        insertUser(fixture.tenantId(), "over" + suffix, Set.of());

        ResponseEntity<byte[]> overLimit = getBytes(fixture.headers(), "/api/users/export");
        assertRefused(overLimit, "user");
    }

    // ------------------------------------------------------------------ audit logs

    @Test
    void theAuditLogExportIsServedAtTheLimitAndRefusedOneRowOver() {
        String suffix = unique();
        Fixture fixture = freshTenantWithAdmin(suffix);
        String marker = "auditprobe" + suffix;
        String url = "/api/audit-logs/export?userName=" + marker;

        insertAuditLogs(fixture.tenantId(), marker, MAX_ROWS);

        ResponseEntity<byte[]> atLimit = getBytes(fixture.headers(), url);
        assertThat(atLimit.getStatusCode())
                .as("exactly max-rows is within the bound and must be served in full")
                .isEqualTo(HttpStatus.OK);

        List<List<String>> rows = dataRows(atLimit);
        assertThat(rows)
                .as("\"served in full\" means N data rows in the sheet, not merely a 200 with a "
                        + "well-formed zip")
                .hasSize(MAX_ROWS);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get(AUDIT_TENANT_ID_COLUMN))
                    .as("an audit row from outside the fixture tenant reached the workbook — the "
                            + "specification's tenant predicate is the only thing holding this, and "
                            + "a 200 with a valid spreadsheet is what its absence looks like")
                    .isEqualTo(String.valueOf(fixture.tenantId()));
            assertThat(row.get(AUDIT_USERNAME_COLUMN))
                    .as("a row outside the userName filter reached the workbook")
                    .isEqualTo(marker);
        });

        insertAuditLogs(fixture.tenantId(), marker, 1);

        ResponseEntity<byte[]> overLimit = getBytes(fixture.headers(), url);
        assertRefused(overLimit, "audit log");
    }

    // ------------------------------------------------------------------ the bound reaches SQL

    /**
     * THE ASSERTION THE REFUSAL TESTS CANNOT MAKE. {@code BoundedExport} claims the bound is carried
     * into the database as {@code fetch first N rows only}; the mutation that moved it into Java —
     * fetch everything, trim afterwards — left every externally observable behaviour untouched. This
     * reads Hibernate's own statement log and requires the export's query against {@code users} to
     * carry a row limit, which is exactly the claim and nothing else.
     *
     * <p><b>Why the service is called directly rather than through the endpoint.</b> The capture
     * window has to contain the export's statements and nothing else. Driven through HTTP it would
     * also hold whatever authentication, tenant resolution, the subscription gate and the audit
     * interceptor issue, and any limited query among those against the same table would satisfy the
     * assertion without the export ever being bounded — green for the wrong reason, the failure
     * class this repository keeps rediscovering. The HTTP path is covered by the two boundary tests
     * above; what is under test here is the shape of one query.
     */
    @Test
    void theUserExportCarriesItsBoundIntoSql() {
        String suffix = unique();
        Fixture fixture = freshTenantWithAdmin(suffix);
        // Fill to the limit, not past it: the fixture already holds the reader and the
        // bootstrapped admin, and one row over would make the export refuse before emitting the
        // SQL under assertion.
        for (long existing = userRepository.countByTenantId(fixture.tenantId());
                existing < MAX_ROWS; existing++) {
            insertUser(fixture.tenantId(), "u" + existing + suffix, Set.of());
        }

        List<String> statements = captureSqlDuring(fixture.tenantId(), () -> userService.exportToExcel());

        assertTheBoundReachedTheDatabase(statements, "users", "/api/users/export");
    }

    /** The same claim for the other export, whose bound travels a completely different route. */
    @Test
    void theAuditLogExportCarriesItsBoundIntoSql() {
        String suffix = unique();
        Fixture fixture = freshTenantWithAdmin(suffix);
        String marker = "sqlprobe" + suffix;
        insertAuditLogs(fixture.tenantId(), marker, MAX_ROWS);

        List<String> statements = captureSqlDuring(fixture.tenantId(),
                () -> auditLogService.export(marker, null, null, null, null));

        assertTheBoundReachedTheDatabase(statements, "audit_logs", "/api/audit-logs/export");
    }

    /**
     * Two assertions, and the first is not decoration. A test that looks for a pattern in a captured
     * log passes just as happily when it captured nothing at all — wrong category, level never
     * raised, statement logging compiled out. The vacuity check turns "found no unbounded statement"
     * into "read the statements and none was unbounded".
     */
    private void assertTheBoundReachedTheDatabase(List<String> statements, String table, String endpoint) {
        List<String> againstTable = statements.stream()
                .filter(sql -> sql.toLowerCase(Locale.ROOT).contains("from " + table))
                .toList();

        assertThat(againstTable)
                .as("no statement against %s was captured at all, so the assertion below would "
                        + "certify nothing. Either org.hibernate.SQL is no longer the category "
                        + "Hibernate logs statements to, the DEBUG level did not take effect, or the "
                        + "export stopped querying this table. Captured %s statement(s) in total.",
                        table, statements.size())
                .isNotEmpty();

        assertThat(againstTable)
                .as("%s read %s with no row limit in the SQL: every matching row was pulled into the "
                        + "heap and the bound — if any — was applied in Java afterwards, which is the "
                        + "allocation this whole change exists to prevent. The fetch lambda must "
                        + "apply the Pageable BoundedExport hands it. Statements captured against "
                        + "%s: %s", endpoint, table, table, againstTable)
                .anyMatch(ExportRowBoundIT::carriesASqlRowLimit);
    }

    /**
     * Hibernate 6 renders a {@code Pageable} through the dialect's {@code LimitHandler}. PostgreSQL's
     * is {@code OffsetFetchLimitHandler}, i.e. {@code offset ? rows fetch first ? rows only}. The
     * other two spellings are accepted so that a dialect change shows up as a design review rather
     * than as a mystery red — what matters is that the LIMIT is in the statement, not which of the
     * three standard renderings the dialect chose.
     */
    private static boolean carriesASqlRowLimit(String sql) {
        String normalized = sql.toLowerCase(Locale.ROOT);
        return normalized.contains("fetch first")
                || normalized.contains("fetch next")
                || normalized.contains(" limit ");
    }

    /**
     * Runs {@code call} in the fixture tenant's scope with {@code org.hibernate.SQL} pinned to DEBUG
     * and an appender attached, and returns the statements it emitted.
     *
     * <p>The level is pinned rather than inherited and restored in a {@code finally}: leaving SQL
     * logging on would slow every subsequent IT in the JVM and flood the report, and a level that
     * silently stayed above DEBUG would make the capture empty — which the vacuity assertion in
     * {@link #assertTheBoundReachedTheDatabase} is there to catch.
     */
    private List<String> captureSqlDuring(long tenantId, Runnable call) {
        Logger sqlLogger = (Logger) LoggerFactory.getLogger(SQL_LOGGER);
        Level originalLevel = sqlLogger.getLevel();
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        sqlLogger.addAppender(captured);
        sqlLogger.setLevel(Level.DEBUG);
        TenantContext.setTenantId(tenantId);
        try {
            call.run();
        } finally {
            TenantContext.clear();
            sqlLogger.setLevel(originalLevel);
            sqlLogger.detachAppender(captured);
            captured.stop();
        }
        return captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    // ------------------------------------------------------------------ assertions

    /**
     * The refusal contract, asserted where the caller actually meets it. A service-level exception
     * proves nothing about the response: what reaches the client has to be an RFC 9457
     * {@code ProblemDetail} with a 4xx status, not a 500 and not a stack trace, and it has to name
     * the limit so the operator can act on it without reading the source.
     */
    private void assertRefused(ResponseEntity<byte[]> response, String subject) {
        MediaType contentType = response.getHeaders().getContentType();
        assertThat(response.getStatusCode())
                .as("one row past the limit must be REFUSED, not truncated into a 200 that looks "
                        + "like a complete export — got %s with content type %s",
                        response.getStatusCode(), contentType)
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(contentType).isNotNull();
        assertThat(contentType.toString())
                .as("errors are RFC 9457 ProblemDetail, including on an endpoint whose happy path "
                        + "produces a spreadsheet")
                .contains("problem+json");

        JsonNode body = readJson(response);
        assertThat(body).isNotNull();
        assertThat(body.path("status").asInt()).isEqualTo(400);
        assertThat(body.path("code").asText()).isEqualTo("VALIDATION");
        assertThat(body.path("detail").asText())
                .as("the refusal must name the export, the limit and the way out")
                .contains(subject)
                .contains(String.valueOf(MAX_ROWS))
                .contains("zero.export.max-rows")
                .containsIgnoringCase("narrow");
    }

    /**
     * The at-limit workbook, actually opened.
     *
     * <p>This used to be an {@code assertIsXlsx} that checked the two {@code PK} magic bytes of the
     * zip container and stopped there. That accepts any spreadsheet whatsoever: an export that
     * returned another tenant's rows, or half the rows, or ten times as many, produced identical
     * evidence. Reading the cells is what makes "served in full" and "served from this tenant"
     * assertable at all.
     *
     * <p>Header row excluded — the services write it at index 0 and the data from index 1.
     */
    private List<List<String>> dataRows(ResponseEntity<byte[]> response) {
        byte[] body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.length).as("an empty body is not a workbook").isGreaterThan(4);
        // xlsx is a ZIP container; "PK" is the local file header magic. Checked before POI is asked
        // to parse it, so a non-xlsx body fails as a readable assertion rather than as a POI stack.
        assertThat(new byte[] {body[0], body[1]})
                .as("the export body is not a zip container, so it is not an xlsx")
                .containsExactly((byte) 0x50, (byte) 0x4B);

        List<List<String>> rows = new ArrayList<>();
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(body))) {
            Sheet sheet = workbook.getSheetAt(0);
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                if (row == null) {
                    continue;
                }
                List<String> cells = new ArrayList<>();
                for (int column = 0; column < row.getLastCellNum(); column++) {
                    cells.add(cellText(row.getCell(column)));
                }
                rows.add(cells);
            }
        } catch (java.io.IOException e) {
            throw new AssertionError("the export body could not be opened as a workbook", e);
        }
        return rows;
    }

    /**
     * Cell value as text. Ids are written with {@code setCellValue(long)} and come back as doubles,
     * so they are rendered through {@link BigDecimal} rather than {@code String.valueOf(double)} —
     * the latter turns id 12345 into {@code "12345.0"} and every id comparison into a false red.
     */
    private static String cellText(Cell cell) {
        if (cell == null) {
            return "";
        }
        return switch (cell.getCellType()) {
            case NUMERIC -> BigDecimal.valueOf(cell.getNumericCellValue())
                    .stripTrailingZeros().toPlainString();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case STRING -> cell.getStringCellValue();
            default -> "";
        };
    }

    // ------------------------------------------------------------------ fixture

    private record Fixture(long tenantId, long adminUserId, long bootstrappedAdminId,
                           HttpHeaders headers) {
    }

    private static String unique() {
        return Long.toString(System.nanoTime(), 36);
    }

    /**
     * A tenant nobody else touches, plus one user in it holding the two read permissions the exports
     * require. Created through the API so the tenant gets its default subscription (the subscription
     * gate answers 403 on {@code /api/**} otherwise); the role and user are written through the
     * repositories because this fixture needs a NARROW role — the bootstrapped Admin that tenant
     * creation now provisions (Issue #1 is closed) holds far more than the two permissions under
     * test, and its password is random when the create request does not supply one.
     */
    private Fixture freshTenantWithAdmin(String suffix) {
        String tenantName = "expbound" + suffix;
        HttpHeaders host = bearerHeaders(accessToken(null, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), null);
        ResponseEntity<JsonNode> created = restTemplate.exchange("/api/tenants", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", tenantName, "displayName", "Export Bound " + suffix,
                        "adminEmail", "admin@" + tenantName + ".local"), host),
                JsonNode.class);
        assertThat(created.getStatusCode())
                .as("fixture tenant must be created, got %s", created.getBody())
                .isEqualTo(HttpStatus.CREATED);
        long tenantId = tenantRepository.findByNameIgnoreCase(tenantName).map(Tenant::getId).orElseThrow();

        Role role = new Role();
        role.setTenantId(tenantId);
        role.setName("ExportReader");
        role.setDisplayName("Export Reader");
        // Constants, not literals. A typo in a raw permission string here compiles, passes, and
        // surfaces as an unexplained 403 in a test that was supposed to be exercising the export;
        // ArchUnit rule 3 scans @PreAuthorize in production packages only, so nothing else is
        // watching this line. These two were the last raw setPermissions literals in the test tree.
        role.setPermissions(Set.of(AppPermissions.USERS_READ, AuditPermissions.AUDITLOGS_READ));
        roleRepository.save(role);

        String username = "expadmin" + suffix;
        long adminUserId = insertUser(tenantId, username, Set.of(role));
        long bootstrappedAdminId = userRepository
                .findByTenantIdAndUsernameIgnoreCase(tenantId, "admin")
                .orElseThrow(() -> new AssertionError(
                        "tenant creation must bootstrap an admin user (Issue #1)"))
                .getId();

        HttpHeaders headers = bearerHeaders(
                accessToken(tenantName, username, FIXTURE_PASSWORD), tenantName);
        return new Fixture(tenantId, adminUserId, bootstrappedAdminId, headers);
    }

    /** @return the generated id, so the exported rows can be matched against the fixture exactly. */
    private long insertUser(long tenantId, String username, Set<Role> roles) {
        User user = new User();
        user.setTenantId(tenantId);
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(FIXTURE_PASSWORD));
        user.setActive(true);
        user.getRoles().addAll(roles);
        return userRepository.save(user).getId();
    }

    private void insertAuditLogs(long tenantId, String marker, int count) {
        for (int i = 0; i < count; i++) {
            AuditLog log = new AuditLog();
            log.setTenantId(tenantId);
            log.setUsername(marker);
            log.setExecutionTime(Instant.now());
            log.setExecutionDurationMs(1);
            log.setHttpMethod("GET");
            log.setUrl("/probe/" + i);
            log.setHttpStatusCode(200);
            auditLogRepository.save(log);
        }
    }

    // ------------------------------------------------------------------ transport

    /**
     * {@code Accept: *&#47;*} on purpose. Letting the message converters pick the header would make
     * the success call ask for {@code application/json} (and get a 406 on a spreadsheet) or the
     * refusal ask for the xlsx type (and get a 406 instead of the ProblemDetail under test) — a
     * green-for-the-wrong-reason in either direction.
     */
    private HttpHeaders acceptAnything(HttpHeaders headers) {
        HttpHeaders copy = new HttpHeaders();
        copy.addAll(headers);
        copy.setAccept(List.of(MediaType.ALL));
        return copy;
    }

    /**
     * Every export call is read as raw bytes, including the one expected to be refused, and the JSON
     * is parsed afterwards. Binding the refusal directly to {@code JsonNode} makes an unbounded
     * implementation blow up inside RestTemplate ("no suitable HttpMessageConverter ... for content
     * type ...spreadsheetml.sheet") instead of failing the assertion — the right verdict for the
     * wrong reason, and unreadable as evidence. Measured: that is exactly what the pre-fix code
     * produced.
     */
    private ResponseEntity<byte[]> getBytes(HttpHeaders headers, String url) {
        return restTemplate.exchange(url, HttpMethod.GET,
                new HttpEntity<>(acceptAnything(headers)), byte[].class);
    }

    private ResponseEntity<JsonNode> getJson(HttpHeaders headers, String url) {
        return restTemplate.exchange(url, HttpMethod.GET,
                new HttpEntity<>(acceptAnything(headers)), JsonNode.class);
    }

    private JsonNode readJson(ResponseEntity<byte[]> response) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getBody());
        } catch (java.io.IOException e) {
            throw new AssertionError("the refusal body is not JSON: "
                    + new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8), e);
        }
    }
}
