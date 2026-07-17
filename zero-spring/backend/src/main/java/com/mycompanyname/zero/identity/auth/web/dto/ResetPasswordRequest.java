package com.mycompanyname.zero.identity.auth.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank String resetCode,
        @NotBlank @Size(min = 6, max = 128) String newPassword) {
}
