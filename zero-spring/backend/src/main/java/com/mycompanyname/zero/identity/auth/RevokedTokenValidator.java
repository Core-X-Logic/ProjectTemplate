package com.mycompanyname.zero.identity.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;

/**
 * Rejects a revoked access token (PROD-R16). Sits in the decoder's {@code DelegatingOAuth2TokenValidator}
 * chain, so it runs on <em>every</em> authenticated request after the signature, issuer, audience and
 * timestamp checks — no extra filter, revocation stays inside the standard resource-server validation.
 *
 * <p>Reads {@code jti}/{@code sub}/{@code iat} from the decoded token and asks {@link TokenRevocationService}.
 *
 * <p><b>Fail-closed.</b> If the store cannot be reached the token is REJECTED, not honoured: the store
 * is the only source of truth for what has been revoked, so a fail-open here would let a logged-out or
 * post-credential-change token keep working during a Redis outage. This is deliberately different from
 * the rate limiter's degrade-to-local — the limiter has a safe local fallback, revocation has none.
 * The trade (a Redis outage denies auth) is bounded by the short access-token TTL and is recorded in
 * the risk register. Never logs the token or its jti.
 */
@Slf4j
public class RevokedTokenValidator implements OAuth2TokenValidator<Jwt> {

    private final TokenRevocationService revocationService;

    public RevokedTokenValidator(TokenRevocationService revocationService) {
        this.revocationService = revocationService;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        try {
            Long userId = token.getSubject() == null ? null : Long.valueOf(token.getSubject());
            if (revocationService.isRevoked(token.getId(), userId, issueInstant(token))) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                        OAuth2ErrorCodes.INVALID_TOKEN,
                        "The token has been revoked",
                        "https://tools.ietf.org/html/rfc6750#section-3.1"));
            }
            return OAuth2TokenValidatorResult.success();
        } catch (RuntimeException ex) {
            // Fail-closed: a store we cannot reach means we cannot prove the token is still valid.
            log.warn("Token revocation check could not reach the store; failing closed (rejecting the "
                    + "request). Cause: {}", ex.getClass().getName());
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    OAuth2ErrorCodes.INVALID_TOKEN,
                    "The token could not be checked against the revocation store",
                    "https://tools.ietf.org/html/rfc6750#section-3.1"));
        }
    }

    /**
     * The token's issue time for the "not before" comparison (PROD-R16 F3).
     *
     * <p>An {@code ims}-bearing token yields full millisecond precision: the same-second window is
     * closed and there is no same-instant login loop.
     *
     * <p>A token minted before the {@code ims} upgrade (a pre-upgrade instance during a rolling deploy)
     * carries only the second-granular {@code iat}. If that floored {@code iat} were compared against a
     * sub-second {@code notBefore} written by an upgraded instance, a legitimate same-second re-login
     * would be revoked ({@code iat_sec*1000 < iat_sec*1000+frac}) — a deploy-window login loop that the
     * pre-F3 (seconds-vs-seconds, strict {@code <}) comparison never had. So for the fallback we add
     * ~1s, making the comparison effectively second-granular and restoring the pre-F3 rule where a
     * same-second re-login SURVIVES. Only legacy no-{@code ims} tokens take this path, and they age out
     * within the access-token TTL. New tokens keep the precise millisecond path above.
     */
    private static Instant issueInstant(Jwt token) {
        Object ims = token.getClaims().get("ims");
        if (ims instanceof Number millis) {
            return Instant.ofEpochMilli(millis.longValue());
        }
        Instant iat = token.getIssuedAt();
        return iat == null ? null : iat.plusMillis(999);
    }
}
