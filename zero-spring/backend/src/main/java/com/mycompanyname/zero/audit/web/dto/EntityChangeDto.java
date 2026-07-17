package com.mycompanyname.zero.audit.web.dto;

import java.time.Instant;
import java.util.List;

public record EntityChangeDto(
        Long id,
        Long tenantId,
        Long userId,
        String entityTypeName,
        String entityId,
        String changeType,
        Instant changeTime,
        List<EntityPropertyChangeDto> propertyChanges) {
}
