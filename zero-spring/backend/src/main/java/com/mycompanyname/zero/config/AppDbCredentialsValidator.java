package com.mycompanyname.zero.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Startup guard for the database identity split (RLS baseline step 1), the third sibling of
 * {@link JwtSecretValidator} and {@link FieldEncryptionKeyValidator}.
 *
 * <p>What it protects. Row Level Security can only constrain the application if the application
 * connects as a role that is neither the tables' owner nor a superuser: an owner bypasses its own
 * policies unless {@code FORCE ROW LEVEL SECURITY} is set, and a superuser bypasses them always
 * (ADR-0018). {@code V11__app_role.sql} creates {@link #APPLICATION_ROLE}
 * {@code NOSUPERUSER NOBYPASSRLS} for that purpose and {@code spring.flyway.user} keeps the
 * owner/superuser identity for migrations. Both halves are plain configuration, so both can be
 * undone by an environment file — silently, and in a way whose only symptom is that the isolation
 * tests keep passing while nothing constrains anybody.
 *
 * <p>Two refusals under the {@code prod} profile:
 * <ul>
 *   <li><b>One identity for both roles.</b> If {@code spring.datasource.username} equals
 *       {@code spring.flyway.user} the split does not exist, whatever the roles are called. The
 *       requirement is phrased as "the runtime user must not be the migration user"; this check is
 *       deliberately the stronger form — it also refuses {@code zero_app} on <em>both</em> sides,
 *       which would mean Flyway runs as the runtime role (no DDL rights, no ownership separation,
 *       and the {@code FORCE} guarantee of the policies reduced to decoration).</li>
 *   <li><b>The committed migration password.</b> {@code V11__app_role.sql} has to create the role
 *       with a deterministic password so dev and CI work, which makes that password public in this
 *       repository. Accepting it in prod would mean a production role anyone can read the password
 *       of.</li>
 * </ul>
 *
 * <p>Plus the unresolved-placeholder trap: {@code application-prod.yml} deliberately leaves these
 * four values without defaults, and an unset {@code ${DB_USER}} binds as the literal text rather
 * than failing. Blank and literal-placeholder values are therefore refused too — fail at boot, not
 * at the first query.
 *
 * <p>Outside {@code prod} nothing is fatal: a laptop and the CI Postgres service legitimately run
 * both halves as one superuser, and the ITs get their split from
 * {@code AbstractIntegrationIT}. A single WARN records it so the state is visible in the log.
 *
 * <p>Note on what is deliberately <em>not</em> refused: the test-only password
 * {@code AbstractIntegrationIT} gives {@code zero_app} inside its Testcontainers instance. It is
 * committed too, but it is never the password of any real role — configuring it in prod produces
 * {@code FATAL: password authentication failed}, which is loud. The migration's password is the
 * dangerous one precisely because it <em>works</em>.
 *
 * <p>{@link #validate} is static and profile-explicit so the rules are unit-testable without a
 * Spring context, exactly like its two siblings.
 */
@Component
@Slf4j
public class AppDbCredentialsValidator implements InitializingBean {

    /** The runtime role {@code V11__app_role.sql} creates: {@code NOSUPERUSER NOBYPASSRLS}. */
    public static final String APPLICATION_ROLE = "zero_app";

    /**
     * The password {@code V11__app_role.sql} commits for {@link #APPLICATION_ROLE} — public, by
     * definition. {@code AppDbCredentialsValidatorTest} pins this constant against the migration
     * file: a drifted copy would mean this guard checks a value no database actually has.
     */
    public static final String MIGRATION_COMMITTED_PASSWORD = "zero_app_dev_password";

    private static final String PROD_PROFILE = "prod";

    private final Environment environment;

    public AppDbCredentialsValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        validate(
                environment.getProperty("spring.datasource.username"),
                environment.getProperty("spring.datasource.password"),
                environment.getProperty("spring.flyway.user"),
                List.of(environment.getActiveProfiles()));
    }

    /**
     * @throws IllegalStateException when the {@code prod} profile is active and the runtime identity
     *                               is missing, unresolved, shared with the migration identity, or
     *                               still on the password this repository publishes
     */
    public static void validate(String datasourceUser,
                                String datasourcePassword,
                                String migrationUser,
                                List<String> activeProfiles) {
        boolean prod = activeProfiles.contains(PROD_PROFILE);

        if (!prod) {
            if (isSameIdentity(datasourceUser, migrationUser)) {
                log.warn("The application and Flyway share one database identity ('{}'). Fine on a "
                        + "laptop or in CI, refused under the 'prod' profile — Row Level Security "
                        + "cannot constrain a role that owns the tables. Active profiles: {}",
                        datasourceUser, Arrays.toString(activeProfiles.toArray()));
            }
            return;
        }

        requireConfigured(datasourceUser, "spring.datasource.username", "DB_USER");
        requireConfigured(datasourcePassword, "spring.datasource.password", "DB_PASSWORD");
        requireConfigured(migrationUser, "spring.flyway.user", "DB_MIGRATION_USER");

        if (isSameIdentity(datasourceUser, migrationUser)) {
            throw new IllegalStateException(
                    "spring.datasource.username and spring.flyway.user are the same database identity "
                            + "('" + datasourceUser + "'), so the application runs as the owner of its own "
                            + "tables. Row Level Security cannot constrain that role (an owner bypasses "
                            + "policies without FORCE, a superuser always does), which makes tenant "
                            + "isolation unenforceable AND unprovable. Set DB_USER=" + APPLICATION_ROLE
                            + " (the NOSUPERUSER NOBYPASSRLS role V11__app_role.sql creates) and point "
                            + "DB_MIGRATION_USER at the owner/superuser that runs migrations.");
        }

        if (MIGRATION_COMMITTED_PASSWORD.equals(datasourcePassword)) {
            throw new IllegalStateException(
                    "spring.datasource.password is the password V11__app_role.sql commits for the '"
                            + APPLICATION_ROLE + "' role, which makes it public in this repository's "
                            + "history. Rotate it before serving traffic: `ALTER ROLE " + APPLICATION_ROLE
                            + " PASSWORD '<new>'` as the migration user, then set DB_PASSWORD to the new "
                            + "value.");
        }

        if (!APPLICATION_ROLE.equals(datasourceUser)) {
            // Not fatal: a deployment may legitimately name the role differently (one database per
            // customer, a managed-service naming rule). Logged because the far likelier cause is a
            // typo in DB_USER, and a typo that still connects is the kind that is never noticed.
            log.warn("spring.datasource.username is '{}', not the conventional '{}'. Make sure that role "
                    + "is NOSUPERUSER and NOBYPASSRLS, otherwise it bypasses every tenant policy.",
                    datasourceUser, APPLICATION_ROLE);
        }
    }

    private static boolean isSameIdentity(String datasourceUser, String migrationUser) {
        // Postgres role names are case-sensitive when quoted, but an unquoted identifier is folded to
        // lower case — so 'Zero' and 'zero' are the same login. Comparing case-insensitively refuses
        // the disguise as well as the plain form.
        return datasourceUser != null && migrationUser != null
                && datasourceUser.equalsIgnoreCase(migrationUser);
    }

    private static void requireConfigured(String value, String property, String environmentVariable) {
        if (value == null || value.isBlank() || value.contains("${")) {
            throw new IllegalStateException(
                    property + " is not configured (value: " + (value == null ? "null" : "'" + value + "'")
                            + "). The 'prod' profile carries no default for it on purpose; set the "
                            + environmentVariable + " environment variable. An unset placeholder binds as "
                            + "literal text instead of failing, which is why this is checked at boot.");
        }
    }
}
