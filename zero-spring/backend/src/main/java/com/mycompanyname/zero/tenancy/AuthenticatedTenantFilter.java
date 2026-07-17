package com.mycompanyname.zero.tenancy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompanyname.zero.shared.domain.ErrorCode;
import com.mycompanyname.zero.shared.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Second stage of the two-stage tenant resolution.
 *
 * <p>{@link TenantResolverFilter} (registered BEFORE the bearer token filter) resolves the
 * X-Tenant header so that permitAll endpoints (login/refresh) get a tenant context. This filter
 * (registered AFTER {@code BearerTokenAuthenticationFilter}) makes the JWT {@code tenant} claim
 * the single source of truth for authenticated requests:
 *
 * <ul>
 *   <li>The effective requested tenant is the header-resolved tenant (or host when the header is
 *       absent). If it does not match the JWT {@code tenant} claim (claim absent = host user),
 *       the request is rejected with 403 {@code FORBIDDEN} ("Tenant mismatch"). A host user
 *       sending any X-Tenant header, and a tenant user omitting the header (which would imply
 *       host scope), are both mismatches.</li>
 *   <li>On a match, {@link TenantContext} is (re)set authoritatively from the JWT claim, so
 *       header manipulation can never widen access.</li>
 * </ul>
 *
 * <p>No {@code finally} cleanup here on purpose: the outermost {@link TenantResolverFilter}
 * owns the {@link TenantContext} lifecycle and clears it after the chain completes.
 *
 * <p>Not a servlet-container filter: registered only inside the Spring Security chain by the
 * security configuration.
 */
public class AuthenticatedTenantFilter extends OncePerRequestFilter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String headerName;

    public AuthenticatedTenantFilter(String headerName) {
        this.headerName = (headerName == null || headerName.isBlank())
                ? TenantResolverFilter.DEFAULT_TENANT_HEADER
                : headerName;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            Long jwtTenantId = tenantClaim(jwt);
            String headerValue = request.getHeader(headerName);
            boolean headerPresent = headerValue != null && !headerValue.isBlank();
            // When the header is present, TenantResolverFilter has already validated it and
            // stored the resolved tenant id in the TenantContext; absent header means host scope.
            Long requestedTenantId = headerPresent ? TenantContext.getTenantId() : null;
            if (!Objects.equals(requestedTenantId, jwtTenantId)) {
                writeProblem(response, HttpServletResponse.SC_FORBIDDEN, ErrorCode.FORBIDDEN, "Tenant mismatch");
                return;
            }
            // Authoritative: for authenticated requests the tenant always comes from the token.
            if (jwtTenantId != null) {
                TenantContext.setTenantId(jwtTenantId);
            } else {
                TenantContext.clear();
            }
        }
        filterChain.doFilter(request, response);
    }

    private Long tenantClaim(Jwt jwt) {
        Object tenant = jwt.getClaim("tenant");
        if (tenant == null) {
            return null;
        }
        if (tenant instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(tenant.toString());
    }

    private void writeProblem(HttpServletResponse response, int status, ErrorCode code, String detail)
            throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", code.name());
        body.put("status", status);
        body.put("detail", detail);
        body.put("code", code.name());
        OBJECT_MAPPER.writeValue(response.getWriter(), body);
    }
}
