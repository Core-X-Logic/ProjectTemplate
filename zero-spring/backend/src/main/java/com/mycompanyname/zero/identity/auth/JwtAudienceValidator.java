package com.mycompanyname.zero.identity.auth;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

/**
 * Rejects an access token whose {@code aud} claim does not contain this API's audience (PROD-R16).
 *
 * <p>Spring Security's default validator set checks timestamps only, and {@code createDefaultWithIssuer}
 * adds the issuer — neither looks at {@code aud}. Any token signed with the same HMAC secret was
 * therefore accepted, so a second service sharing the key (or a staging deployment pointed at the
 * same secret) could hand out tokens that work here.
 */
public class JwtAudienceValidator implements OAuth2TokenValidator<Jwt> {

    private final String audience;

    public JwtAudienceValidator(String audience) {
        if (audience == null || audience.isBlank()) {
            throw new IllegalStateException("zero.jwt.audience is not configured");
        }
        this.audience = audience;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        List<String> tokenAudience = token.getAudience();
        if (tokenAudience != null && tokenAudience.contains(audience)) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                OAuth2ErrorCodes.INVALID_TOKEN,
                "The required audience '" + audience + "' is missing",
                "https://tools.ietf.org/html/rfc6750#section-3.1"));
    }
}
