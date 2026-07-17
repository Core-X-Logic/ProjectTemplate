package com.mycompanyname.zero.identity.web.dto;

import java.time.Instant;
import java.util.Set;

public record UserDto(
        Long id,
        String username,
        String email,
        String name,
        String surname,
        String phoneNumber,
        boolean active,
        boolean emailConfirmed,
        Instant lockoutEndAt,
        Long tenantId,
        Set<String> roles) {
}
