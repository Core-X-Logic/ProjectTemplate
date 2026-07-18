package com.mycompanyname.zero.config;

import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Evidence for B5 — the branch the guard exists for, which nothing exercised.
 *
 * <p>{@code MigrationGuardIT} asserted only that the callback was registered with Flyway. That is
 * necessary and nowhere near sufficient: registration says the guard runs, not that it ever says no.
 * Underneath, it never did. A {@link SQLException} or an unparseable version became a {@code WARN}
 * and the migration continued; so did an empty result set. Every failure of the probe produced
 * precisely the outcome the guard was written to prevent, and left a log line implying it had
 * checked.
 *
 * <p>These tests reach the rejection branches without a PG14 server by overriding the probe — which
 * is why the probe was extracted in the first place. Testing them any other way would have meant
 * standing up an obsolete database, and that cost is exactly why it was never done.
 */
class PostgresVersionGuardTest {

    private static final Event MIGRATE = Event.BEFORE_MIGRATE;

    @Test
    void anOlderServerIsRejected() {
        // PG14.10. The schema's UNIQUE ... NULLS NOT DISTINCT does not parse there, so V1 would fail
        // partway through with a syntax error instead of a statement of the requirement.
        PostgresVersionGuard guard = guardReporting(new PostgresVersionGuard.ServerVersion(140_010,
                "PostgreSQL 14.10 on x86_64-pc-linux-gnu"));

        assertThatThrownBy(() -> guard.handle(MIGRATE, context()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PostgreSQL 15 or newer is required")
                .as("the operator has to be told which server they actually have")
                .hasMessageContaining("14.10");
    }

    @Test
    void theVersionImmediatelyBelowTheThresholdIsRejected() {
        // Guards fail at their boundary or not at all.
        PostgresVersionGuard guard = guardReporting(new PostgresVersionGuard.ServerVersion(
                PostgresVersionGuard.MINIMUM_SERVER_VERSION_NUM - 1, "PostgreSQL 14.99"));

        assertThatThrownBy(() -> guard.handle(MIGRATE, context()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aFailingProbeStopsTheMigration() {
        // The original swallowed this into a WARN and returned, which let the migration run against a
        // server whose version was never established. "Could not check" is not "passed".
        PostgresVersionGuard guard = guardFailingWith(new SQLException("connection reset"));

        assertThatThrownBy(() -> guard.handle(MIGRATE, context()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not determine the PostgreSQL server version")
                .hasRootCauseInstanceOf(SQLException.class);
    }

    @Test
    void anUnparseableVersionStopsTheMigration() {
        // server_version_num is documented as an integer; a server that answers otherwise is not one
        // this guard can vouch for, and the old NumberFormatException catch waved it through.
        PostgresVersionGuard guard = new PostgresVersionGuard() {
            @Override
            protected ServerVersion readServerVersion(Connection connection) {
                throw new NumberFormatException("For input string: \"not-a-number\"");
            }
        };

        assertThatThrownBy(() -> guard.handle(MIGRATE, context()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not determine the PostgreSQL server version");
    }

    @Test
    void anEmptyResultSetStopsTheMigration() {
        PostgresVersionGuard guard = guardReporting(null);

        assertThatThrownBy(() -> guard.handle(MIGRATE, context()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not report a server version");
    }

    @Test
    void aSupportedServerPasses() {
        // The other half of a guard's contract: it must not block the deployments it is meant to
        // protect. PG16 is what the suite runs against.
        PostgresVersionGuard guard = guardReporting(
                new PostgresVersionGuard.ServerVersion(160_002, "PostgreSQL 16.2"));

        assertThatCode(() -> guard.handle(MIGRATE, context())).doesNotThrowAnyException();
    }

    @Test
    void theGuardOnlyRunsBeforeMigration() {
        PostgresVersionGuard guard = new PostgresVersionGuard();

        assertThat(guard.supports(Event.BEFORE_MIGRATE, context())).isTrue();
        assertThat(guard.supports(Event.AFTER_MIGRATE, context())).isFalse();
    }

    // --- helpers ---------------------------------------------------------

    private static PostgresVersionGuard guardReporting(PostgresVersionGuard.ServerVersion version) {
        return new PostgresVersionGuard() {
            @Override
            protected ServerVersion readServerVersion(Connection connection) {
                return version;
            }
        };
    }

    private static PostgresVersionGuard guardFailingWith(SQLException failure) {
        return new PostgresVersionGuard() {
            @Override
            protected ServerVersion readServerVersion(Connection connection) throws SQLException {
                throw failure;
            }
        };
    }

    /**
     * The guard asks the context for one thing — a connection — and every test here overrides the
     * probe that would use it. A mock keeps this class from having to track {@code Context}'s
     * unrelated members across Flyway upgrades.
     */
    private static Context context() {
        return Mockito.mock(Context.class);
    }
}
