package com.mycompanyname.zero.identity.web.dto;

import java.util.Set;

public record MeDto(
        Long id,
        String username,
        String email,
        Long tenantId,
        boolean shouldChangePassword,
        Set<String> roles,
        Set<String> permissions,
        boolean twoFactorEnabled) {
}
