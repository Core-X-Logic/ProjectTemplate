package com.mycompanyname.zero.audit.web.dto;

import java.time.Instant;

public record AuditLogDto(
        Long id,
        Long tenantId,
        Long userId,
        String username,
        String serviceName,
        String methodName,
        String parameters,
        Instant executionTime,
        int executionDurationMs,
        String clientIp,
        String browserInfo,
        String httpMethod,
        String url,
        Integer httpStatusCode,
        String exception) {
}
