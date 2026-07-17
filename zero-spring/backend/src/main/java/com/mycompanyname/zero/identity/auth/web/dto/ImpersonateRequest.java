package com.mycompanyname.zero.identity.auth.web.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Starts an impersonation. {@code targetTenantId} is optional; when supplied it must match the
 * target user's tenant (a {@code null} target user tenant means a host user).
 */
public record ImpersonateRequest(
        @NotNull Long targetUserId,
        Long targetTenantId) {
}
