package com.mycompanyname.zero.tenancy.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Creates a tenant together with its bootstrap admin (Issue #1: a tenant without an admin can
 * never be logged into, and nothing later repairs that).
 *
 * <p>{@code adminEmail} is required: the admin user cannot exist without one, and the previous
 * contract — which accepted a tenant with no admin at all — is exactly the defect being closed.
 *
 * <p>{@code adminPassword} is optional. When present it is the admin's initial password and must
 * satisfy the password policy (checked server-side against the settings hierarchy, not by a bean
 * annotation, so policy overrides apply). When absent, a strong random password is generated and
 * returned exactly once in {@link CreateTenantResponse#generatedAdminPassword()}.
 */
public record CreateTenantRequest(
        @NotBlank @Pattern(regexp = "[a-z0-9-]{2,30}") String name,
        @NotBlank String displayName,
        @NotBlank @Email @Size(max = 256) String adminEmail,
        @Size(max = 128) String adminPassword) {
}
