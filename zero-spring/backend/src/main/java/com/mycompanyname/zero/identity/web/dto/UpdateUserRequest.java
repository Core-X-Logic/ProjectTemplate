package com.mycompanyname.zero.identity.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record UpdateUserRequest(
        @Email @Size(max = 256) String email,
        @Size(min = 8) String password,
        Boolean active,
        Set<String> roleNames) {
}
