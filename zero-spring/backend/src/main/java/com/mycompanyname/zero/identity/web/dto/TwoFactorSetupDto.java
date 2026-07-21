package com.mycompanyname.zero.identity.web.dto;

/**
 * Returned ONCE by {@code POST /api/profile/two-factor/setup}: the raw base32 secret (for manual key
 * entry) and the {@code otpauth://} provisioning URI (for the authenticator QR the frontend renders).
 * 2FA is not yet active at this point — the user must confirm a code via {@code enable}. Never logged.
 */
public record TwoFactorSetupDto(
        String secret,
        String otpauthUri) {
}
