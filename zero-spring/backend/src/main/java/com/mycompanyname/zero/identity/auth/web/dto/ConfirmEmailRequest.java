package com.mycompanyname.zero.identity.auth.web.dto;

import jakarta.validation.constraints.NotBlank;

public record ConfirmEmailRequest(
        @NotBlank String code) {
}
