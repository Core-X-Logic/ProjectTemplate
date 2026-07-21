package com.mycompanyname.zero.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The at-rest field-encryption key ({@code zero.crypto.field-key}), used to encrypt sensitive column
 * values that must be RECOVERABLE (unlike password/recovery-code hashes, which are one-way). Today the
 * one consumer is the TOTP secret on {@code users.two_factor_secret}.
 *
 * <p>Modelled on {@link JwtProperties}: a bare {@code @ConfigurationProperties} holder with no
 * validation of its own. The verdict on whether the configured value is usable — present, base64,
 * exactly 32 bytes, and not a committed dev/test key under {@code prod} — belongs to
 * {@link FieldEncryptionKeyValidator}, which runs at boot and needs the active profiles a properties
 * bean has no business knowing.
 */
@Component
@ConfigurationProperties(prefix = "zero.crypto")
@Getter
@Setter
public class FieldEncryptionProperties {

    /**
     * Base64 of exactly 32 random bytes (an AES-256 key). No default on purpose, exactly like
     * {@code zero.jwt.secret}: a committed default is a key that is public in the repository history,
     * and a profile mishap would then silently encrypt every TOTP secret with it. Dev/test values live
     * in {@code application-dev.yml} / {@code application-test.yml}; every other environment must supply
     * {@code FIELD_ENCRYPTION_KEY}.
     */
    private String fieldKey;
}
