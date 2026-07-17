package com.mycompanyname.zero.identity.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record UpdateRoleRequest(
        @NotBlank @Size(max = 128) String displayName,
        Set<String> permissions,
        boolean isDefault) {
}
