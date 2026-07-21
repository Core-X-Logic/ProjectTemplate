package com.mycompanyname.zero.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * Startup guard for the at-rest field-encryption key ({@code zero.crypto.field-key}), the twin of
 * {@link JwtSecretValidator}.
 *
 * <p>Two failure modes it makes impossible at boot rather than at first use:
 * <ul>
 *   <li><b>An unresolved placeholder.</b> {@code zero.crypto.field-key} has no default, so a
 *       deployment that forgets {@code FIELD_ENCRYPTION_KEY} binds the literal string
 *       {@code "${FIELD_ENCRYPTION_KEY}"}. That is the placeholder trap this repository has been
 *       burned by; here it is not valid base64, so {@link #validate} throws. A blank value is refused
 *       for the same reason.</li>
 *   <li><b>A committed dev/test key reaching prod.</b> The dev and test keys live in the repository and
 *       are therefore public. They must keep working where they belong and be refused the moment the
 *       {@code prod} profile is active, so a key anyone can read can never encrypt a real tenant's
 *       secret.</li>
 * </ul>
 *
 * <p>There is no single "leaked default" burned on every profile the way {@code JwtSecretValidator}
 * has one: this property never shipped with a default, so the compromise that produced that rule never
 * happened here. The wrong-length rule is the extra one — an AES-256-GCM key must decode to exactly 32
 * bytes, and a shorter value silently weakening the cipher is the field-encryption analogue of the
 * HS512 under-length key {@code JwtService.buildSecretKey} refuses.
 *
 * <p>{@link #validate} is static and profile-explicit so it is unit-testable without a Spring context,
 * exactly like its JWT twin.
 */
@Component
@Slf4j
public class FieldEncryptionKeyValidator implements InitializingBean {

    /** An AES-256 key: 32 bytes after base64 decoding. */
    public static final int REQUIRED_KEY_BYTES = 32;

    /** Committed dev key (application-dev.yml): public, usable locally, never in prod. */
    public static final String DEV_KEY = "emVyby1GSUVMRC1FTkMtREVWLS1ub3QtaW4tcHJvZCE=";

    /** Committed test key (application-test.yml): public, usable in the suite, never in prod. */
    public static final String TEST_KEY = "emVyby1GSUVMRC1FTkMtVEVTVC1uZXZlci1kZXBsb3k=";

    /** Keys that live in the repository and are therefore public: usable locally, never in prod. */
    public static final Set<String> NON_PRODUCTION_KEYS = Set.of(DEV_KEY, TEST_KEY);

    private static final String PROD_PROFILE = "prod";

    private final FieldEncryptionProperties properties;
    private final Environment environment;

    public FieldEncryptionKeyValidator(FieldEncryptionProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        validate(properties.getFieldKey(), List.of(environment.getActiveProfiles()));
    }

    /**
     * @throws IllegalStateException when the key is missing, not base64, the wrong length, or a
     *                               repository-known key that the active profiles do not permit
     */
    public static void validate(String key, List<String> activeProfiles) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "zero.crypto.field-key is not configured. Set the FIELD_ENCRYPTION_KEY environment "
                            + "variable to a base64-encoded value of exactly 32 random bytes, e.g. "
                            + "`openssl rand -base64 32`.");
        }
        if (activeProfiles.contains(PROD_PROFILE) && NON_PRODUCTION_KEYS.contains(key)) {
            throw new IllegalStateException(
                    "zero.crypto.field-key is a non-production key that is committed to this repository, "
                            + "but the 'prod' profile is active. Supply a real FIELD_ENCRYPTION_KEY "
                            + "(`openssl rand -base64 32`).");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(key);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "zero.crypto.field-key must be valid base64; raw text keys are not accepted (and an "
                            + "unresolved ${FIELD_ENCRYPTION_KEY} placeholder lands here). Use "
                            + "`openssl rand -base64 32`.", ex);
        }
        if (decoded.length != REQUIRED_KEY_BYTES) {
            throw new IllegalStateException("zero.crypto.field-key must decode to exactly "
                    + REQUIRED_KEY_BYTES + " bytes for AES-256-GCM, but was " + decoded.length
                    + " bytes.");
        }
        if (!activeProfiles.contains(PROD_PROFILE) && NON_PRODUCTION_KEYS.contains(key)) {
            log.warn("zero.crypto.field-key is a committed development key. This is fine locally and "
                    + "fatal in prod; active profiles: {}", Arrays.toString(activeProfiles.toArray()));
        }
    }
}
