package com.mycompanyname.zero.audit;

import com.mycompanyname.zero.shared.tenant.TenantContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Reads the acting principal (user id, username, tenant) for audit records straight from the Spring
 * Security context and the shared {@link TenantContext}. Deliberately independent of the identity
 * module so the audit module keeps a single allowed dependency on {@code shared}.
 */
public final class AuditPrincipal {

    private AuditPrincipal() {
    }

    public static Long userId() {
        Jwt jwt = jwt();
        if (jwt == null || jwt.getSubject() == null) {
            return null;
        }
        try {
            return Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public static String username() {
        Jwt jwt = jwt();
        if (jwt != null) {
            Object username = jwt.getClaim("username");
            if (username != null) {
                return username.toString();
            }
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null ? authentication.getName() : null;
    }

    public static Long tenantId() {
        Long fromContext = TenantContext.getTenantId();
        if (fromContext != null) {
            return fromContext;
        }
        Jwt jwt = jwt();
        if (jwt != null) {
            Object tenant = jwt.getClaim("tenant");
            if (tenant instanceof Number number) {
                return number.longValue();
            }
        }
        return null;
    }

    private static Jwt jwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        return principal instanceof Jwt jwt ? jwt : null;
    }
}
