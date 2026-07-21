package com.mycompanyname.zero.identity.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

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
            if (revocationService.isRevoked(token.getId(), userId, token.getIssuedAt())) {
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
}
