package com.mycompanyname.zero.identity.invitation.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Anonymous accept. The DTO floor mirrors {@code ResetPasswordRequest} ({@code @Size(6, 128)});
 * the tenant's {@code PasswordPolicy} is enforced on top in the service, exactly like the
 * password-reset flow (and sharing R-33's known 6-vs-8 floor inconsistency rather than adding a
 * third floor).
 */
public record AcceptInvitationRequest(
        @NotBlank String token,
        @NotBlank @Size(min = 6, max = 128) String password) {
}
