package com.mycompanyname.zero.identity.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record CreateUserRequest(
        @NotBlank @Size(max = 64) String username,
        @NotBlank @Email @Size(max = 256) String email,
        @NotBlank @Size(min = 8) String password,
        @Size(max = 64) String name,
        @Size(max = 64) String surname,
        @Size(max = 32) String phoneNumber,
        Set<String> roleNames,
        Set<Long> organizationUnitIds) {
}
