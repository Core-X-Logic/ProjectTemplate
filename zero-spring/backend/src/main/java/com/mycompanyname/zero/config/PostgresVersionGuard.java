package com.mycompanyname.zero.config;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Refuses to migrate against a PostgreSQL older than 15 (PROD-R11).
 *
 * <p>The baseline schema uses {@code UNIQUE ... NULLS NOT DISTINCT}, which exists only from PG15.
 * On an older server V1 fails with a parser error at the offending token — technically accurate,
 * useless to whoever is holding the pager. Running as a {@code BEFORE_MIGRATE} callback puts the
 * check ahead of V1, so a fresh install on PG14 gets the requirement stated instead of a syntax
 * error, and the check applies even before any versioned migration has run.
 *
 * <p>V6 repeats the same guard inline so the migration set is self-describing when someone applies
 * it with the Flyway CLI, outside this application.
 *
 * <p>Spring Boot picks up {@link Callback} beans automatically and registers them with Flyway.
 *
 * <p><b>Fail-closed (B5).</b> This guard used to swallow {@link SQLException} and
 * {@link NumberFormatException} into a {@code WARN}, and to return quietly on an empty result set.
 * Every one of those paths let migration proceed — so any probe that did not work handed back
 * exactly the outcome the guard exists to prevent, while leaving a log line that reads like the
 * check ran. A version that cannot be read is not a version that passed. All three now abort the
 * migration.
 *
 * <p>The probe is isolated behind {@link #readServerVersion(Connection)} so the rejection branches
 * are reachable from a test without a PG14 server: the reason the original defect survived review is
 * that the only coverage asserted the callback was <em>registered</em>, never that it refused
 * anything.
 */
@Component
@Slf4j
public class PostgresVersionGuard implements Callback {

    /** PG15. {@code server_version_num} is encoded as major * 10000 + minor. */
    static final int MINIMUM_SERVER_VERSION_NUM = 150_000;

    /** What the server reports about itself: the numeric form to compare, the text form to print. */
    protected record ServerVersion(int versionNum, String display) {
    }

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.BEFORE_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        ServerVersion version;
        try {
            version = readServerVersion(context.getConnection());
            // RuntimeException covers NumberFormatException — a server_version_num that is not a
            // number is exactly as unreadable as a driver error, and must land in the same branch.
        } catch (SQLException | RuntimeException ex) {
            throw new IllegalStateException(
                    "Could not determine the PostgreSQL server version, so the minimum-version "
                            + "requirement could not be checked. Refusing to migrate: this schema "
                            + "requires PostgreSQL 15 or newer (it uses UNIQUE ... NULLS NOT DISTINCT), "
                            + "and applying it to an older server fails midway with a parser error.", ex);
        }
        if (version == null) {
            throw new IllegalStateException(
                    "The database did not report a server version. Refusing to migrate: this schema "
                            + "requires PostgreSQL 15 or newer, and it is not this callback's place to "
                            + "assume an unidentified server qualifies.");
        }
        if (version.versionNum() < MINIMUM_SERVER_VERSION_NUM) {
            throw new IllegalStateException(
                    "PostgreSQL 15 or newer is required (the schema uses UNIQUE ... NULLS NOT DISTINCT), "
                            + "but this server reports " + version.display() + ". Upgrade the database "
                            + "before starting the application.");
        }
        log.debug("PostgreSQL version guard passed: {}", version.display());
    }

    /**
     * Reads {@code server_version_num} / {@code server_version}, or null when the server returns no
     * row. Overridable so a test can present a PG14 server, or a failing probe, without one existing.
     */
    protected ServerVersion readServerVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "select current_setting('server_version_num'), current_setting('server_version')")) {
            if (!resultSet.next()) {
                return null;
            }
            return new ServerVersion(Integer.parseInt(resultSet.getString(1)), resultSet.getString(2));
        }
    }

    @Override
    public String getCallbackName() {
        return "postgresVersionGuard";
    }
}
