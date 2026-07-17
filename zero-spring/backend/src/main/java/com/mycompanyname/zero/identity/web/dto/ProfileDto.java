package com.mycompanyname.zero.identity.web.dto;

import java.util.Set;

public record ProfileDto(
        Long id,
        String username,
        String email,
        String name,
        String surname,
        String phoneNumber,
        boolean emailConfirmed,
        Long tenantId,
        Set<String> roles) {
}
