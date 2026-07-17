package com.mycompanyname.zero.identity.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

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
