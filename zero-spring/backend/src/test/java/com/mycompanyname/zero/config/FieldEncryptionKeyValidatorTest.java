package com.mycompanyname.zero.config;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The at-rest field-encryption key guard, the twin of {@code JwtSecretValidatorTest}. Pins the four
 * things that must fail at boot: an unresolved/blank value, a non-base64 value (which is what an unset
 * {@code ${FIELD_ENCRYPTION_KEY}} placeholder binds to), a wrong-length key, and any committed dev/test
 * key under the {@code prod} profile.
 */
class FieldEncryptionKeyValidatorTest {

    /** A real 32-byte key (base64), standing in for `openssl rand -base64 32`. Not a committed key. */
    private static final String STRONG_KEY = "nvzUTrJtrfDxOpjj14eU3lGZQmd9DKfP2GGTf9Gx/xg=";

    @Test
    void refusesAMissingKey() {
        assertThatThrownBy(() -> FieldEncryptionKeyValidator.validate(null, List.of("prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
        assertThatThrownBy(() -> FieldEncryptionKeyValidator.validate("   ", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    void refusesAnUnresolvedPlaceholderBecauseItIsNotBase64() {
        // The placeholder trap: an unset FIELD_ENCRYPTION_KEY binds this literal string.
        assertThatThrownBy(() -> FieldEncryptionKeyValidator.validate("${FIELD_ENCRYPTION_KEY}", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("valid base64");
    }

    @Test
    void refusesAWrongLengthKey() {
        String sixteenBytes = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> FieldEncryptionKeyValidator.validate(sixteenBytes, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly 32 bytes");
    }

    @Test
    void refusesEveryCommittedKeyUnderTheProdProfile() {
        for (String key : FieldEncryptionKeyValidator.NON_PRODUCTION_KEYS) {
            assertThatThrownBy(() -> FieldEncryptionKeyValidator.validate(key, List.of("prod")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("zero.crypto.field-key");
        }
    }

    @Test
    void acceptsTheCommittedKeysOnTheirOwnProfiles() {
        assertThatCode(() -> FieldEncryptionKeyValidator.validate(
                FieldEncryptionKeyValidator.DEV_KEY, List.of("dev"))).doesNotThrowAnyException();
        assertThatCode(() -> FieldEncryptionKeyValidator.validate(
                FieldEncryptionKeyValidator.TEST_KEY, List.of("test"))).doesNotThrowAnyException();
    }

    @Test
    void acceptsAnOperatorSuppliedKeyInProd() {
        assertThatCode(() -> FieldEncryptionKeyValidator.validate(STRONG_KEY, List.of("prod")))
                .doesNotThrowAnyException();
    }
}
