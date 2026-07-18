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
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the current tenant from the configured request header (zero.multitenancy.header,
 * default X-Tenant). Not a component on purpose: registered as a bean by the security
 * configuration and placed before the bearer token authentication filter.
 *
 * <p>Three gates run here, in order: the tenant must exist (400 {@code TENANT_UNKNOWN}), it must be
 * active (403 {@code FORBIDDEN}), and — when a {@link TenantAccessCheck} is wired — its subscription
 * must permit the requested path (403 {@code SUBSCRIPTION_INVALID}, F5-ARCHITECTURE §7.1). Host
 * requests carry no tenant header and are never subject to any of them.
 */
public class TenantResolverFilter extends OncePerRequestFilter {

    public static final String DEFAULT_TENANT_HEADER = "X-Tenant";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final TenantRepository tenantRepository;
    private final String headerName;
    private final TenantAccessCheck accessCheck;

    public TenantResolverFilter(TenantRepository tenantRepository) {
        this(tenantRepository, DEFAULT_TENANT_HEADER, null);
    }

    public TenantResolverFilter(TenantRepository tenantRepository, String headerName) {
        this(tenantRepository, headerName, null);
    }

    /**
     * @param accessCheck optional veto applied to tenant-scoped requests; {@code null} disables the
     *                    gate entirely, which keeps the filter usable on its own in unit tests
     */
    public TenantResolverFilter(TenantRepository tenantRepository, String headerName,
                                TenantAccessCheck accessCheck) {
        this.tenantRepository = tenantRepository;
        this.headerName = (headerName == null || headerName.isBlank()) ? DEFAULT_TENANT_HEADER : headerName;
        this.accessCheck = accessCheck;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String tenantName = request.getHeader(headerName);
        try {
            if (tenantName == null || tenantName.isBlank()) {
                TenantContext.clear();
                filterChain.doFilter(request, response);
                return;
            }
            Optional<Tenant> tenant = tenantRepository.findByNameIgnoreCase(tenantName.trim());
            if (tenant.isEmpty()) {
                writeProblem(response, HttpServletResponse.SC_BAD_REQUEST, ErrorCode.TENANT_UNKNOWN,
                        "Unknown tenant: " + tenantName);
                return;
            }
            if (!tenant.get().isActive()) {
                writeProblem(response, HttpServletResponse.SC_FORBIDDEN, ErrorCode.FORBIDDEN,
                        "Tenant is not active: " + tenantName);
                return;
            }
            if (accessCheck != null) {
                Optional<String> denial = accessCheck.denyReason(tenant.get().getId(), pathOf(request));
                if (denial.isPresent()) {
                    writeProblem(response, HttpServletResponse.SC_FORBIDDEN,
                            ErrorCode.SUBSCRIPTION_INVALID, denial.get());
                    return;
                }
            }
            TenantContext.setTenantId(tenant.get().getId());
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    /** The request URI with the context path removed, so exemption patterns stay deployment-agnostic. */
    private String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (uri == null) {
            return "/";
        }
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            String stripped = uri.substring(contextPath.length());
            return stripped.isEmpty() ? "/" : stripped;
        }
        return uri;
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
