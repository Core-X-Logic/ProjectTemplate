package com.mycompanyname.zero.identity.web.dto;

import java.util.Set;

public record RoleDetailDto(
        Long id,
        String name,
        String displayName,
        boolean isStatic,
        boolean isDefault,
        Set<String> permissions) {
}
