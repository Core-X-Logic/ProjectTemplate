package com.mycompanyname.zero.identity.web.dto;

import java.util.List;

public record PermissionNodeDto(
        String name,
        String displayName,
        String parent,
        List<PermissionNodeDto> children) {
}
