package com.mycompanyname.zero.identity.ou.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateOuRequest(
        @NotBlank @Size(max = 128) String displayName,
        Long parentId) {
}
