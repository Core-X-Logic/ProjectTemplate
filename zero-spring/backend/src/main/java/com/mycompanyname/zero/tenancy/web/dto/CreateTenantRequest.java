package com.mycompanyname.zero.tenancy.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateTenantRequest(
        @NotBlank @Pattern(regexp = "[a-z0-9-]{2,30}") String name,
        @NotBlank String displayName) {
}
