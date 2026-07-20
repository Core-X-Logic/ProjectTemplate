package com.mycompanyname.zero.tenancy.web.dto;

import java.time.Instant;

/**
 * Response of {@code POST /api/tenants}.
 *
 * <p><b>{@code generatedAdminPassword} is a one-time disclosure.</b> It is non-null only when the
 * request omitted {@code adminPassword}: the server then generated a strong random initial
 * credential for the tenant's bootstrap admin and this field is the only place it ever appears.
 * It is never logged, and only its hash is persisted (on the tenant's {@code admin} user), so it
 * cannot be retrieved again — the caller must hand it to the tenant admin over a secure channel.
 * The admin is additionally created with {@code shouldChangePassword = true}, so the credential is
 * rotated on first login either way.
 */
public record CreateTenantResponse(
        Long id,
        String name,
        String displayName,
        boolean active,
        Instant createdAt,
        String generatedAdminPassword) {
}
