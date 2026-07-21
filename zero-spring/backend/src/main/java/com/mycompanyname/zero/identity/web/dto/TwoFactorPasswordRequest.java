package com.mycompanyname.zero.identity.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body of the sensitive 2FA management operations that re-verify the caller's current password before
 * proceeding: {@code POST /api/profile/two-factor/disable} and
 * {@code POST /api/profile/two-factor/recovery-codes/regenerate}. Mirrors the re-authentication step
 * {@code ProfileService.changePassword} performs.
 */
public record TwoFactorPasswordRequest(
        @NotBlank String password) {
}
