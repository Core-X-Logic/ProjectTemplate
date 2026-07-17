package com.mycompanyname.zero.tenancy.web.dto;

import java.time.Instant;

public record TenantDto(
        Long id,
        String name,
        String displayName,
        boolean active,
        Instant createdAt) {
}
