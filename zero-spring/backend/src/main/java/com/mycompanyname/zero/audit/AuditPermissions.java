package com.mycompanyname.zero.audit;

import java.util.Set;

/**
 * Audit permission constants, owned by the {@code audit} module for the same reason
 * {@code SaasPermissions} is owned by {@code saas}: the guard has to be written where the endpoint
 * is, and {@code audit} declares no dependency on {@code identity}.
 *
 * <p>Registration into the permission tree happens on the identity side, which repeats the same
 * string values; {@code PermissionRegistryAlignmentTest} fails the build if the two ever drift.
 */
public final class AuditPermissions {

    public static final String AUDITLOGS_READ = "auditlogs.read";

    private AuditPermissions() {
    }

    public static Set<String> all() {
        return Set.of(AUDITLOGS_READ);
    }
}
