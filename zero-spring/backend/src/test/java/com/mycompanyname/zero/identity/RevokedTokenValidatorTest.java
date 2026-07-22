package com.mycompanyname.zero.identity;

import com.mycompanyname.zero.identity.auth.RevokedTokenValidator;
import com.mycompanyname.zero.identity.auth.TokenRevocationService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The validator's decision table (PROD-R16), including the load-bearing one: when the revocation
 * store cannot be reached the request is REJECTED (fail-closed), not waved through.
 *
 * <p>The last test records the negative evidence directly: a fail-OPEN variant of the same check —
 * one that swallows the store error and returns success — would let a possibly-revoked token through.
 * The two verdicts are asserted side by side so the contrast is unmissable.
 */
class RevokedTokenValidatorTest {

    @Test
    void aTokenThatIsNotRevokedPasses() {
        RevokedTokenValidator validator = new RevokedTokenValidator(serviceReturning(false));
        assertThat(validator.validate(token()).hasErrors()).isFalse();
    }

    @Test
    void aRevokedTokenIsRejected() {
        RevokedTokenValidator validator = new RevokedTokenValidator(serviceReturning(true));
        OAuth2TokenValidatorResult result = validator.validate(token());
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anyMatch(e -> e.getDescription().contains("revoked"));
    }

    @Test
    void anUnreachableStoreRejectsTheToken_failClosed() {
        RevokedTokenValidator validator = new RevokedTokenValidator(serviceThrowing());
        OAuth2TokenValidatorResult result = validator.validate(token());
        assertThat(result.hasErrors())
                .as("a store we cannot reach must NOT be treated as 'not revoked'")
                .isTrue();
    }

    /**
     * Negative evidence: the danger the fail-closed choice removes. A validator that catches the store
     * error and returns success — the fail-OPEN shape — accepts the token, so during a Redis outage a
     * logged-out or credential-changed token would keep working. The real validator (above) refuses it.
     */
    @Test
    void aFailOpenVariantWouldLetThePossiblyRevokedTokenThrough() {
        TokenRevocationService throwing = serviceThrowing();

        OAuth2TokenValidator<Jwt> failOpen = jwt -> {
            try {
                return throwing.isRevoked(jwt.getId(), 1L, jwt.getIssuedAt())
                        ? OAuth2TokenValidatorResult.failure()
                        : OAuth2TokenValidatorResult.success();
            } catch (RuntimeException ex) {
                return OAuth2TokenValidatorResult.success(); // the mistake we are guarding against
            }
        };

        assertThat(failOpen.validate(token()).hasErrors())
                .as("fail-open accepts the token on a store outage — this is exactly what we do NOT do")
                .isFalse();
        assertThat(new RevokedTokenValidator(throwing).validate(token()).hasErrors())
                .as("fail-closed refuses it")
                .isTrue();
    }

    // --- helpers ---------------------------------------------------------------------------

    private static Jwt token() {
        Instant now = Instant.now();
        return Jwt.withTokenValue("t")
                .header("alg", "HS512")
                .jti("jti-123")
                .subject("1")
                .issuedAt(now.minusSeconds(60))
                .expiresAt(now.plusSeconds(600))
                .build();
    }

    private static TokenRevocationService serviceReturning(boolean revoked) {
        return new TokenRevocationService(null, null, null, null) {
            @Override
            public boolean isRevoked(String jti, Long userId, Instant issuedAt) {
                return revoked;
            }
        };
    }

    private static TokenRevocationService serviceThrowing() {
        return new TokenRevocationService(null, null, null, null) {
            @Override
            public boolean isRevoked(String jti, Long userId, Instant issuedAt) {
                throw new IllegalStateException("Redis is unreachable");
            }
        };
    }
}
