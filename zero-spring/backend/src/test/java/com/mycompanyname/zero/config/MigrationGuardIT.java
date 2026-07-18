package com.mycompanyname.zero.config;

import com.mycompanyname.zero.AbstractIntegrationIT;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.callback.Callback;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Evidence for PROD-R11.
 *
 * <p>A guard that is written but never registered is worse than no guard, because it reads as
 * coverage. Spring Boot wires {@link Callback} beans into Flyway implicitly, so nothing in the code
 * states the connection — this test does.
 */
class MigrationGuardIT extends AbstractIntegrationIT {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void thePostgresVersionGuardIsRegisteredWithFlyway() {
        assertThat(Arrays.stream(flyway.getConfiguration().getCallbacks()))
                .as("the guard must run before V1, which is where an unsupported server first fails")
                .anyMatch(PostgresVersionGuard.class::isInstance);
    }

    @Test
    void theHardeningMigrationIsApplied() {
        Integer applied = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version = '6' and success",
                Integer.class);

        assertThat(applied)
                .as("V6 carries the PROD-R10/R11/R14/R15c changes the other tests assert on")
                .isEqualTo(1);
    }

    @Test
    void theTestDatabaseSatisfiesTheDeclaredMinimumVersion() {
        // Keeps the guard's threshold honest: if the suite ever ran against a server the guard would
        // reject, every other assertion here would be describing a database production cannot use.
        Integer serverVersion = jdbcTemplate.queryForObject(
                "select current_setting('server_version_num')::int", Integer.class);

        assertThat(serverVersion)
                .isNotNull()
                .isGreaterThanOrEqualTo(PostgresVersionGuard.MINIMUM_SERVER_VERSION_NUM);
    }
}
