package com.mycompanyname.zero.identity.auth.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * @param usernameOrEmail the account identifier to send a reset code to
 * @param tenant          optional tenant name; when absent the host (tenant-less) scope is used
 */
public record ForgotPasswordRequest(
        @NotBlank String usernameOrEmail,
        String tenant) {
}
