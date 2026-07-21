package com.mycompanyname.zero.identity.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;

public final class CurrentUser {

    private CurrentUser() {
    }

    public static Long userId() {
        Jwt jwt = jwt();
        if (jwt == null || jwt.getSubject() == null) {
            return null;
        }
        return Long.valueOf(jwt.getSubject());
    }

    /** The current access token's {@code jti} (PROD-R16 revocation handle), or null if unauthenticated. */
    public static String jti() {
        Jwt jwt = jwt();
        return jwt == null ? null : jwt.getId();
    }

    /** The current access token's {@code exp}, or null if unauthenticated. */
    public static Instant expiresAt() {
        Jwt jwt = jwt();
        return jwt == null ? null : jwt.getExpiresAt();
    }

    public static Long tenantId() {
        Jwt jwt = jwt();
        if (jwt == null) {
            return null;
        }
        Object tenant = jwt.getClaim("tenant");
        if (tenant == null) {
            return null;
        }
        return ((Number) tenant).longValue();
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
