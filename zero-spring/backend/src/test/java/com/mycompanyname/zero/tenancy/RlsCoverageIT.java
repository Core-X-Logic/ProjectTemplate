package com.mycompanyname.zero.tenancy;

import com.mycompanyname.zero.AbstractIntegrationIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The RLS coverage guard: every table carrying a {@code tenant_id} column must either be policed
 * ({@code ENABLE} + {@code FORCE} + at least one policy) or stand on the closed exemption set of
 * {@code ADR-0019}. Its sibling {@link TenantFilterCoverageIT} guards the application-layer
 * {@code @Filter}; this one guards the database floor.
 *
 * <p><b>The table list is DISCOVERED, never written down.</b> A hard-coded list would be renamed or
 * appended out of correctness together with the mistake it was meant to catch: the entire point is
 * that a NEW {@code tenant_id} table born without a policy turns this test red without anyone
 * remembering it exists. Discovery reads {@code information_schema}; the known floor assertion below
 * is what keeps discovery itself honest — a query pointed at the wrong schema discovers nothing and
 * would otherwise certify an empty set.
 *
 * <p><b>The exemption list's authority is ADR-0019, not this file.</b> The machine-readable line in
 * that ADR ({@code <!-- rls-exempt-tables: ... -->}) is parsed at test time and must equal the
 * constant below. On a drift the failure says which file rules: an exemption exists only once the
 * ADR records it (ADR-0019 rule 4 — a new exemption requires a NEW ADR), and the constant here
 * exists only so a broken path or parse cannot silently widen the set to "whatever the regex
 * matched".
 */
class RlsCoverageIT extends AbstractIntegrationIT {

    /**
     * ADR-0019's closed set, restated here so ADR and test reference each other. Changing one
     * without the other is a red, by design.
     */
    private static final Set<String> EXEMPT_TABLES = Set.of("payments", "subscriptions", "tenant_features");

    /**
     * The number of {@code tenant_id} tables that existed when this guard was written
     * (identity 3 + audit/notification 3 + saas 3). Discovery finding FEWER than this means
     * the discovery query is broken, not that tables were dropped — dropping a tenant table is a
     * schema event big enough to come update this constant consciously.
     */
    private static final int KNOWN_TENANT_TABLE_FLOOR = 9;

    private static final String ADR_RELATIVE_PATH = "docs/governance/ADR/ADR-0019-rls-exempt-tables.md";

    private static final Pattern EXEMPT_LINE = Pattern.compile("rls-exempt-tables:\\s*([a-z0-9_,\\s]+?)\\s*-->");

    /** Base tables only: a view over a policed table is constrained by the table's own policy. */
    private static final String DISCOVER_TENANT_TABLES = """
            select c.table_name
            from information_schema.columns c
            join information_schema.tables t
              on t.table_schema = c.table_schema and t.table_name = c.table_name
            where c.table_schema = 'public'
              and c.column_name = 'tenant_id'
              and t.table_type = 'BASE TABLE'
            order by c.table_name""";

    /**
     * {@code pg_policies} is joined per table rather than trusted alone because a table can have
     * RLS enabled with zero policies — which in PostgreSQL means DEFAULT DENY for everyone except
     * the owner, i.e. an outage rather than an isolation, and either way not the reviewed shape.
     */
    private static final String RLS_STATE = """
            select c.relname as table_name,
                   c.relrowsecurity as rls_enabled,
                   c.relforcerowsecurity as rls_forced,
                   (select count(*) from pg_policies p
                     where p.schemaname = 'public' and p.tablename = c.relname) as policy_count
            from pg_class c
            join pg_namespace n on n.oid = c.relnamespace
            where n.nspname = 'public' and c.relname = ?""";

    private record RlsState(boolean enabled, boolean forced, long policyCount) {
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ---------------------------------------------------------------------------------------
    // Anti-empty-green: discovery must find the world before it may judge it
    // ---------------------------------------------------------------------------------------

    @Test
    void discoveryFindsAtLeastTheKnownTenantTables() {
        List<String> discovered = discoverTenantTables();
        assertThat(discovered.size())
                .as("information_schema discovery found %s — fewer than the %d tenant_id tables known "
                        + "to exist. Every other assertion in this class quantifies over this set, so "
                        + "a broken discovery query would turn them all vacuously green; fix the query "
                        + "(or, if a tenant table was really dropped, update the floor consciously): %s",
                        discovered.size(), KNOWN_TENANT_TABLE_FLOOR, discovered)
                .isGreaterThanOrEqualTo(KNOWN_TENANT_TABLE_FLOOR);
    }

    // ---------------------------------------------------------------------------------------
    // The guard itself
    // ---------------------------------------------------------------------------------------

    @Test
    void everyTenantTableIsPolicedAndForcedUnlessAdr0019ExemptsIt() {
        List<String> violations = new ArrayList<>();
        for (String table : discoverTenantTables()) {
            if (EXEMPT_TABLES.contains(table)) {
                continue;
            }
            RlsState state = rlsStateOf(table);
            if (!state.enabled()) {
                violations.add(table + ": ROW LEVEL SECURITY is not enabled");
            }
            if (!state.forced()) {
                violations.add(table + ": FORCE ROW LEVEL SECURITY is off — the table owner "
                        + "(the migration identity) bypasses its own policy");
            }
            if (state.policyCount() == 0) {
                violations.add(table + ": RLS enabled but NO policy exists (default deny — an outage "
                        + "pretending to be isolation)");
            }
        }
        assertThat(violations)
                .as("every tenant_id table must carry ENABLE + FORCE + a policy, or be one of the "
                        + "three tables ADR-0019 exempts. A table on this list was born (or altered) "
                        + "outside the V12/V13 pattern; policing it is the fix — adding it to the "
                        + "exemption list requires a NEW ADR (ADR-0019 rule 4)")
                .isEmpty();
    }

    // ---------------------------------------------------------------------------------------
    // The exemption set: closed, mutually referenced, and itself falsifiable
    // ---------------------------------------------------------------------------------------

    @Test
    void exemptionListMatchesTheMachineReadableLineInAdr0019() {
        assertThat(new TreeSet<>(parseAdrExemptTables()))
                .as("the exemption set in this test and the machine-readable line in %s must be the "
                        + "same closed set. If you meant to change the set: ADR-0019 rule 4 says a new "
                        + "exemption needs a NEW ADR first; then update the ADR line and this constant "
                        + "in the same commit", ADR_RELATIVE_PATH)
                .isEqualTo(new TreeSet<>(EXEMPT_TABLES));
    }

    /**
     * Both rot directions of the list. A name that matches no discovered table is garbage that would
     * silently pre-exempt a future table; an exempt table that HAS gained RLS makes the exemption a
     * lie in the other direction — the ADR then claims a floor is absent where one exists, and the
     * saas negative-authorization discipline it mandates would quietly stop being the only guard.
     */
    @Test
    void everyExemptTableExistsAndIsActuallyUnpoliced() {
        List<String> discovered = discoverTenantTables();
        for (String table : EXEMPT_TABLES) {
            assertThat(discovered)
                    .as("exempt table '%s' is not a discovered tenant_id table — a stale or fabricated "
                            + "exemption entry would silently cover a future table of that name", table)
                    .contains(table);
            assertThat(rlsStateOf(table).enabled())
                    .as("exempt table '%s' has ROW LEVEL SECURITY enabled: either the exemption is "
                            + "obsolete (remove it from ADR-0019 and this test) or someone policed an "
                            + "exempt table without updating the ADR", table)
                    .isFalse();
        }
    }

    // --- discovery ---

    private List<String> discoverTenantTables() {
        return jdbcTemplate.queryForList(DISCOVER_TENANT_TABLES, String.class);
    }

    private RlsState rlsStateOf(String table) {
        List<RlsState> states = jdbcTemplate.query(RLS_STATE, (resultSet, rowNumber) -> new RlsState(
                resultSet.getBoolean("rls_enabled"),
                resultSet.getBoolean("rls_forced"),
                resultSet.getLong("policy_count")), table);
        assertThat(states).as("pg_class must know table '%s'", table).hasSize(1);
        return states.get(0);
    }

    // --- the ADR as data ---

    private Set<String> parseAdrExemptTables() {
        Path adr = locateAdr();
        String content;
        try {
            content = Files.readString(adr, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot read " + adr, ex);
        }
        Matcher matcher = EXEMPT_LINE.matcher(content);
        assertThat(matcher.find())
                .as("%s no longer contains the machine-readable '<!-- rls-exempt-tables: ... -->' "
                        + "line this guard parses", adr)
                .isTrue();
        Set<String> tables = new LinkedHashSet<>();
        for (String name : matcher.group(1).split(",")) {
            if (!name.isBlank()) {
                tables.add(name.trim());
            }
        }
        return tables;
    }

    /**
     * Maven runs this suite from {@code zero-spring/backend}, an IDE may run it from the module or
     * the repo root; walking a few parents up covers all three without hard-coding any of them.
     */
    private Path locateAdr() {
        Path dir = Path.of("").toAbsolutePath();
        for (int depth = 0; dir != null && depth < 8; depth++, dir = dir.getParent()) {
            for (Path candidate : List.of(
                    dir.resolve(ADR_RELATIVE_PATH),
                    dir.resolve("zero-spring").resolve(ADR_RELATIVE_PATH))) {
                if (Files.exists(candidate)) {
                    return candidate;
                }
            }
        }
        throw new AssertionError("ADR-0019 not found (looked for " + ADR_RELATIVE_PATH
                + " upwards from " + Path.of("").toAbsolutePath() + ") — the exemption list's "
                + "authority is that file, so this guard refuses to run without it");
    }

    /** Referenced so a rename of either sibling guard shows up here as a compile error, not drift. */
    @SuppressWarnings("unused")
    private static final Map<String, Class<?>> SIBLING_GUARDS = Map.of(
            "application-layer @Filter coverage", TenantFilterCoverageIT.class);
}
