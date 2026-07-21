package com.mycompanyname.zero.identity.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body of {@code POST /api/auth/two-factor/verify}. {@code code} is either a 6-digit TOTP or a
 * recovery code; the server tries it as both and never tells the caller which interpretation failed.
 */
public record TwoFactorVerifyRequest(
        @NotBlank String challengeToken,
        @NotBlank String code) {
}
