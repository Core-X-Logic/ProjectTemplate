package com.mycompanyname.zero.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Evidence for PROD-R1.
 *
 * <p>The finding was not "the secret is weak" — it was that a <em>known</em> secret applied whenever
 * the {@code prod} profile failed to activate, silently, with no signal at boot. These tests pin the
 * two properties that make that impossible: the leaked value is refused regardless of profile, and
 * no repository-known value survives the {@code prod} profile.
 */
class JwtSecretValidatorTest {

    /** 64+ bytes of base64, standing in for a real `openssl rand -base64 64` value. */
    private static final String STRONG_SECRET =
            "Ni9wSGpLM2FRd1p4WTJ0YkxtRnZScTdEc0UxVWlPY05nWGhKMHlWdEE0ZUJ6UTZLcldsUG5mTTgzY0RoU3VJ";

    @Test
    void refusesTheLeakedDevDefaultOnEveryProfile() {
        // The point of the finding: a profile mishap must not be able to fall back to a key that is
        // published in this repository's history. "No profile" is the failure mode that shipped.
        for (List<String> profiles : List.of(List.<String>of(), List.of("dev"), List.of("test"), List.of("prod"))) {
            assertThatThrownBy(() -> JwtSecretValidator.validate(
                    JwtSecretValidator.LEAKED_DEFAULT_SECRET, profiles))
                    .as("leaked default must be refused with active profiles %s", profiles)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("compromised on every profile");
        }
    }

    @Test
    void refusesEveryCommittedDevelopmentKeyUnderTheProdProfile() {
        assertThat(JwtSecretValidator.NON_PRODUCTION_SECRETS)
                .as("the dev and test keys must be registered, otherwise this test proves nothing")
                .hasSizeGreaterThanOrEqualTo(3);

        for (String secret : JwtSecretValidator.NON_PRODUCTION_SECRETS) {
            // The leaked key and the current dev/test keys fail for different stated reasons; what
            // matters here is that none of them survives 'prod'.
            assertThatThrownBy(() -> JwtSecretValidator.validate(secret, List.of("prod")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("zero.jwt.secret");
        }
    }

    @Test
    void acceptsTheCommittedDevelopmentKeysOnTheirOwnProfiles() {
        // They are public, so they must still be refused in prod (above) — but a laptop has to boot.
        assertThatCode(() -> JwtSecretValidator.validate(
                "emVyby1wbGF0Zm9ybS1MT0NBTC1ERVYtT05MWS1zaWduaW5nLWtleS1ub3QtdmFsaWQtaW4tcHJvZHVjdGlvbi0wMTIzNDU2Nzg5YWJjZGVm",
                List.of("dev")))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesAMissingSecret() {
        assertThatThrownBy(() -> JwtSecretValidator.validate(null, List.of("prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
        assertThatThrownBy(() -> JwtSecretValidator.validate("   ", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    void acceptsAnOperatorSuppliedSecretInProd() {
        assertThatCode(() -> JwtSecretValidator.validate(STRONG_SECRET, List.of("prod")))
                .doesNotThrowAnyException();
    }
}
