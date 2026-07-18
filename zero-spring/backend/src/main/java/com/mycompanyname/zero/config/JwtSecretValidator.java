package com.mycompanyname.zero.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Startup guard for the JWT signing key (PROD-R1).
 *
 * <p>The finding: {@code application.yml} shipped a base64 dev secret as the default for
 * {@code JWT_SECRET}. Any deployment that failed to set {@code SPRING_PROFILES_ACTIVE=prod} — or set
 * it to a typo — signed tokens with a key that is public in the repository history, which lets
 * anyone forge a host-admin token. The default is gone; this class makes sure it cannot come back
 * through a copy-pasted environment file either.
 *
 * <p>Two rules, deliberately different in strength:
 * <ul>
 *   <li>{@link #LEAKED_DEFAULT_SECRET} — the value that was actually committed — is refused on
 *       <em>every</em> profile. It is burned: no environment, not even a laptop, should mint tokens
 *       with it again, and refusing it unconditionally removes the profile dependency the finding
 *       was really about.</li>
 *   <li>The current dev/test keys are refused whenever the {@code prod} profile is active. They are
 *       committed too, so they are public, but they still need to work where they belong.</li>
 * </ul>
 *
 * <p>This runs as an {@link InitializingBean} rather than inside {@link JwtProperties} because the
 * verdict depends on the active profiles, which a {@code @ConfigurationProperties} bean has no
 * business knowing. {@link #validate} is static and profile-explicit so the rules are unit-testable
 * without a Spring context.
 */
@Component
@Slf4j
public class JwtSecretValidator implements InitializingBean {

    /**
     * The dev default that was committed as {@code ${JWT_SECRET:...}} in {@code application.yml}.
     * Treated as compromised for all time.
     */
    public static final String LEAKED_DEFAULT_SECRET =
            "ZGV2LW9ubHktc2VjcmV0LWtleS1jaGFuZ2UtaW4tcHJvZC1taW4tNjQtYnl0ZXMtbG9uZy1wbGVhc2UtY2hhbmdlLW1lLW5vdy0hIQ==";

    /** Keys that live in the repository and are therefore public: usable locally, never in prod. */
    public static final Set<String> NON_PRODUCTION_SECRETS = Set.of(
            LEAKED_DEFAULT_SECRET,
            // application-dev.yml
            "emVyby1wbGF0Zm9ybS1MT0NBTC1ERVYtT05MWS1zaWduaW5nLWtleS1ub3QtdmFsaWQtaW4tcHJvZHVjdGlvbi0wMTIzNDU2Nzg5YWJjZGVm",
            // application-test.yml
            "emVyby1wbGF0Zm9ybS1BVVRPTUFURUQtVEVTVC1PTkxZLXNpZ25pbmcta2V5LW5ldmVyLWRlcGxveWVkLTAxMjM0NTY3ODlhYmNkZWZnaA==");

    private static final String PROD_PROFILE = "prod";

    private final JwtProperties properties;
    private final Environment environment;

    public JwtSecretValidator(JwtProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        validate(properties.getSecret(), List.of(environment.getActiveProfiles()));
    }

    /**
     * @throws IllegalStateException when the secret is missing, or is a repository-known key that
     *                               the active profiles do not permit
     */
    public static void validate(String secret, List<String> activeProfiles) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "zero.jwt.secret is not configured. Set the JWT_SECRET environment variable to a "
                            + "base64-encoded value of at least 64 random bytes.");
        }
        if (LEAKED_DEFAULT_SECRET.equals(secret)) {
            throw new IllegalStateException(
                    "zero.jwt.secret is the dev default that was published in this repository's history "
                            + "(PROD-R1). It is compromised on every profile — generate a new key, e.g. "
                            + "`openssl rand -base64 64`.");
        }
        if (activeProfiles.contains(PROD_PROFILE) && NON_PRODUCTION_SECRETS.contains(secret)) {
            throw new IllegalStateException(
                    "zero.jwt.secret is a non-production key that is committed to this repository, but the "
                            + "'prod' profile is active. Supply a real JWT_SECRET (`openssl rand -base64 64`).");
        }
        if (!activeProfiles.contains(PROD_PROFILE) && NON_PRODUCTION_SECRETS.contains(secret)) {
            log.warn("zero.jwt.secret is a committed development key. This is fine locally and fatal in prod; "
                    + "active profiles: {}", Arrays.toString(activeProfiles.toArray()));
        }
    }
}
