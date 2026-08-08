package com.mycompanyname.zero.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Evidence for RLS baseline step 1 — the database identity split cannot be undone by an environment
 * file without the application refusing to start.
 *
 * <p>The failure this guards against has no symptom of its own: with one identity for both halves,
 * every policy added in V12/V13 is bypassed (owner without {@code FORCE}, superuser always) and the
 * isolation tests pass anyway. A false green is worse than a red, so the misconfiguration has to be
 * fatal at boot rather than discovered by an audit.
 *
 * <p>The last test is the one that keeps the rest honest: it pins the validator's copy of the
 * committed password against {@code V11__app_role.sql}. A drifted constant would leave a guard that
 * compares against a value no database ever had — green, and checking nothing.
 */
class AppDbCredentialsValidatorTest {

    private static final String MIGRATION_USER = "platform";
    private static final String OPERATOR_PASSWORD = "not-the-committed-one";

    @Test
    void refusesOneIdentityForTheApplicationAndTheMigrationsInProd() {
        assertThatThrownBy(() -> AppDbCredentialsValidator.validate(
                "zero", OPERATOR_PASSWORD, "zero", List.of("prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("same database identity")
                .hasMessageContaining("DB_MIGRATION_USER");
    }

    @Test
    void refusesTheSharedIdentityEvenWhenItIsNamedLikeTheApplicationRole() {
        // The stronger form of the rule: 'zero_app' on both sides is still no split — Flyway would
        // run as the runtime role, so nothing owns the tables separately and FORCE guarantees nothing.
        assertThatThrownBy(() -> AppDbCredentialsValidator.validate(
                AppDbCredentialsValidator.APPLICATION_ROLE, OPERATOR_PASSWORD,
                AppDbCredentialsValidator.APPLICATION_ROLE, List.of("prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("same database identity");
    }

    @Test
    void refusesTheSharedIdentityInItsCaseFoldedDisguise() {
        // An unquoted Postgres identifier folds to lower case, so these two connect as ONE role.
        assertThatThrownBy(() -> AppDbCredentialsValidator.validate(
                "Zero", OPERATOR_PASSWORD, "zero", List.of("prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("same database identity");
    }

    @Test
    void refusesTheCommittedMigrationPasswordInProd() {
        assertThatThrownBy(() -> AppDbCredentialsValidator.validate(
                AppDbCredentialsValidator.APPLICATION_ROLE,
                AppDbCredentialsValidator.MIGRATION_COMMITTED_PASSWORD,
                MIGRATION_USER, List.of("prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ALTER ROLE");
    }

    @Test
    void refusesMissingAndUnresolvedValuesInProd() {
        // application-prod.yml carries no defaults on purpose, and an unset ${DB_USER} binds as
        // literal text rather than failing — the trap this repository has already been burned by.
        assertThatThrownBy(() -> AppDbCredentialsValidator.validate(
                "${DB_USER}", OPERATOR_PASSWORD, MIGRATION_USER, List.of("prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.username");
        assertThatThrownBy(() -> AppDbCredentialsValidator.validate(
                AppDbCredentialsValidator.APPLICATION_ROLE, "  ", MIGRATION_USER, List.of("prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.password");
        assertThatThrownBy(() -> AppDbCredentialsValidator.validate(
                AppDbCredentialsValidator.APPLICATION_ROLE, OPERATOR_PASSWORD, null, List.of("prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.flyway.user");
    }

    @Test
    void acceptsASeparatedPairInProd() {
        assertThatCode(() -> AppDbCredentialsValidator.validate(
                AppDbCredentialsValidator.APPLICATION_ROLE, OPERATOR_PASSWORD,
                MIGRATION_USER, List.of("prod")))
                .doesNotThrowAnyException();
    }

    @Test
    void toleratesTheSingleIdentitySetupOutsideProd() {
        // Load-bearing negative control: the CI Postgres service and a laptop legitimately run both
        // halves as one superuser, and DefaultProfileApiDocsExposureIT boots with no profile at all.
        // If this ever throws, the guard has stopped being a prod rule and started breaking builds.
        for (List<String> profiles : List.of(List.<String>of(), List.of("dev"), List.of("test"))) {
            assertThatCode(() -> AppDbCredentialsValidator.validate(
                    "zero", AppDbCredentialsValidator.MIGRATION_COMMITTED_PASSWORD, "zero", profiles))
                    .as("active profiles %s must still boot on a single identity", profiles)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void theCommittedPasswordAndTheRoleShapeMatchTheMigration() throws IOException {
        String executableSql = executableSqlOf("db/migration/V11__app_role.sql");

        assertThat(executableSql)
                .as("the constant this validator refuses in prod must be the password the migration "
                        + "actually creates the role with — otherwise the guard checks nothing")
                .contains("PASSWORD '" + AppDbCredentialsValidator.MIGRATION_COMMITTED_PASSWORD + "'");
        assertThat(executableSql)
                .as("the whole point of the split: the runtime role must be unable to bypass RLS")
                .contains("CREATE ROLE " + AppDbCredentialsValidator.APPLICATION_ROLE)
                .contains("NOSUPERUSER")
                .contains("NOBYPASSRLS");
        assertThat(executableSql)
                .as("step 1 separates identities only — enabling RLS and creating policies belong to "
                        + "the table-group migrations (V12/V13), one group per migration")
                .doesNotContain("ROW LEVEL SECURITY")
                .doesNotContain("CREATE POLICY");
    }

    /**
     * The migration's own comments discuss {@code ROW LEVEL SECURITY} at length — that is where the
     * reasoning for the split lives. Only the executable statements may be asserted on, so
     * {@code --} lines are dropped; asserting over the raw file would fail on the explanation
     * instead of on a policy.
     */
    private static String executableSqlOf(String classpathLocation) throws IOException {
        String file = new ClassPathResource(classpathLocation).getContentAsString(StandardCharsets.UTF_8);
        StringBuilder statements = new StringBuilder();
        for (String line : file.split("\\R")) {
            if (!line.stripLeading().startsWith("--")) {
                statements.append(line).append('\n');
            }
        }
        return statements.toString();
    }
}
