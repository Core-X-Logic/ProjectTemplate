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
 */
public class TenantResolverFilter extends OncePerRequestFilter {

    public static final String DEFAULT_TENANT_HEADER = "X-Tenant";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final TenantRepository tenantRepository;
    private final String headerName;

    public TenantResolverFilter(TenantRepository tenantRepository) {
        this(tenantRepository, DEFAULT_TENANT_HEADER);
    }

    public TenantResolverFilter(TenantRepository tenantRepository, String headerName) {
        this.tenantRepository = tenantRepository;
        this.headerName = (headerName == null || headerName.isBlank()) ? DEFAULT_TENANT_HEADER : headerName;
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
            TenantContext.setTenantId(tenant.get().getId());
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
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
