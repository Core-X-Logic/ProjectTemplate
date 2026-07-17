package com.mycompanyname.zero.identity.ou.web.dto;

public record OuDto(
        Long id,
        Long parentId,
        String code,
        String displayName,
        long memberCount) {
}
