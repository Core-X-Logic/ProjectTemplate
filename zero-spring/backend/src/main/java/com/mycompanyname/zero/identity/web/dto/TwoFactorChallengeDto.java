package com.mycompanyname.zero.identity.web.dto;

/**
 * Returned by {@code POST /api/auth/login} when the account has 2FA enabled: it carries NO session
 * token, only the opaque challenge the caller must redeem at {@code POST /api/auth/two-factor/verify}
 * together with a TOTP or recovery code. The token is not a JWT and cannot authenticate any endpoint.
 */
public record TwoFactorChallengeDto(
        String challengeToken,
        long expiresInSeconds) {
}
