package com.mycompanyname.zero.identity.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
        @Size(max = 64) String name,
        @Size(max = 64) String surname,
        @Size(max = 32) String phoneNumber,
        @Email @Size(max = 256) String email) {
}
