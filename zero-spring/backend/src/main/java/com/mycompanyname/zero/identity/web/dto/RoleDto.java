package com.mycompanyname.zero.identity.web.dto;

public record RoleDto(
        Long id,
        String name,
        String displayName,
        boolean isStatic,
        boolean isDefault,
        long memberCount) {
}
