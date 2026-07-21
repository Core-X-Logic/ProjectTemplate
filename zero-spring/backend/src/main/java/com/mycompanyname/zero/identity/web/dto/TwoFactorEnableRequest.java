package com.mycompanyname.zero.identity.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body of {@code POST /api/profile/two-factor/enable}: the TOTP code the user reads from their
 * authenticator, proving the pending secret was provisioned correctly before 2FA is switched on.
 */
public record TwoFactorEnableRequest(
        @NotBlank String code) {
}
