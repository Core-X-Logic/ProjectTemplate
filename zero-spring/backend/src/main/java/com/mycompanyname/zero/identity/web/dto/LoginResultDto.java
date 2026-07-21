package com.mycompanyname.zero.identity.web.dto;

/**
 * The discriminated result of {@code POST /api/auth/login}. {@code twoFactorRequired} is the
 * discriminator the frontend switches on:
 * <ul>
 *   <li>{@code false} — the token fields ({@code accessToken}, {@code refreshToken},
 *       {@code expiresInSeconds}) are populated and {@code twoFactor} is null. This is BYTE-FOR-BYTE
 *       the shape login returned before 2FA existed, plus the additive discriminator, so existing
 *       clients and tests that read the token fields off the top level keep working unchanged.</li>
 *   <li>{@code true} — every token field is null and {@code twoFactor} carries the challenge. No
 *       session is minted; the caller must redeem the challenge at
 *       {@code POST /api/auth/two-factor/verify}.</li>
 * </ul>
 *
 * <p>Kept flat rather than nesting the tokens under a {@code tokens} object precisely so the non-2FA
 * wire shape does not change. A wrapper is never populated on both sides at once.
 */
public record LoginResultDto(
        boolean twoFactorRequired,
        String accessToken,
        String refreshToken,
        Long expiresInSeconds,
        TwoFactorChallengeDto twoFactor) {

    /** The unchanged, non-2FA outcome: real tokens at the top level. */
    public static LoginResultDto authenticated(TokenPairDto tokens) {
        return new LoginResultDto(false, tokens.accessToken(), tokens.refreshToken(),
                tokens.expiresInSeconds(), null);
    }

    /** 2FA is on: no tokens, only the challenge to redeem. */
    public static LoginResultDto challenge(TwoFactorChallengeDto twoFactor) {
        return new LoginResultDto(true, null, null, null, twoFactor);
    }
}
