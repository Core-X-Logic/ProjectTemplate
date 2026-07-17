package com.mycompanyname.zero.audit.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompanyname.zero.audit.AuditLogService;
import com.mycompanyname.zero.audit.AuditPrincipal;
import com.mycompanyname.zero.audit.AuditSupport;
import com.mycompanyname.zero.audit.domain.AuditLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

/**
 * Records one {@link AuditLog} per {@code /api/**} request (login/refresh excluded so their
 * credentials are never captured). Timing spans the whole request, surviving async re-dispatch.
 * Request parameters are serialised to JSON with sensitive keys masked.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditLogInterceptor implements AsyncHandlerInterceptor {

    static final String START_INSTANT_ATTR = AuditLogInterceptor.class.getName() + ".startInstant";
    static final String START_NANOS_ATTR = AuditLogInterceptor.class.getName() + ".startNanos";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AuditLogService auditLogService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (shouldAudit(request) && request.getAttribute(START_NANOS_ATTR) == null) {
            request.setAttribute(START_INSTANT_ATTR, Instant.now());
            request.setAttribute(START_NANOS_ATTR, System.nanoTime());
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        Object startNanos = request.getAttribute(START_NANOS_ATTR);
        if (startNanos == null) {
            return;
        }
        try {
            AuditLog entry = new AuditLog();
            Object startInstant = request.getAttribute(START_INSTANT_ATTR);
            entry.setExecutionTime(startInstant instanceof Instant instant ? instant : Instant.now());
            long durationMs = (System.nanoTime() - (long) startNanos) / 1_000_000L;
            entry.setExecutionDurationMs((int) Math.max(0L, Math.min(durationMs, Integer.MAX_VALUE)));
            entry.setUserId(AuditPrincipal.userId());
            entry.setUsername(AuditSupport.truncate(AuditPrincipal.username(), 64));
            entry.setTenantId(AuditPrincipal.tenantId());
            entry.setHttpMethod(AuditSupport.truncate(request.getMethod(), 16));
            entry.setUrl(AuditSupport.truncate(request.getRequestURI(), 512));
            entry.setHttpStatusCode(response.getStatus());
            entry.setClientIp(AuditSupport.truncate(clientIp(request), 64));
            entry.setBrowserInfo(AuditSupport.truncate(request.getHeader("User-Agent"), 512));
            entry.setParameters(AuditSupport.truncate(parameters(request), 2000));
            if (handler instanceof HandlerMethod handlerMethod) {
                entry.setServiceName(AuditSupport.truncate(handlerMethod.getBeanType().getSimpleName(), 256));
                entry.setMethodName(AuditSupport.truncate(handlerMethod.getMethod().getName(), 256));
            }
            if (ex != null) {
                entry.setException(AuditSupport.truncate(ex.getClass().getName() + ": " + ex.getMessage(), 2000));
            }
            auditLogService.save(entry);
        } catch (RuntimeException recordingError) {
            log.warn("Failed to record audit log for {}: {}", request.getRequestURI(), recordingError.getMessage());
        }
    }

    private boolean shouldAudit(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null || !uri.startsWith("/api/")) {
            return false;
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        return !(uri.startsWith("/api/auth/login") || uri.startsWith("/api/auth/refresh"));
    }

    private String parameters(HttpServletRequest request) {
        Map<String, String[]> parameterMap = request.getParameterMap();
        if (parameterMap.isEmpty()) {
            return null;
        }
        Map<String, Object> masked = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> entry : parameterMap.entrySet()) {
            if (AuditSupport.isSensitive(entry.getKey())) {
                masked.put(entry.getKey(), "***");
            } else {
                String[] values = entry.getValue();
                masked.put(entry.getKey(), values.length == 1 ? values[0] : values);
            }
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(masked);
        } catch (Exception ex) {
            return null;
        }
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return comma > 0 ? forwarded.substring(0, comma).trim() : forwarded.trim();
        }
        return request.getRemoteAddr();
    }
}
