package com.mycompanyname.zero.identity.ou.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateOuRequest(
        @NotBlank @Size(max = 128) String displayName) {
}
