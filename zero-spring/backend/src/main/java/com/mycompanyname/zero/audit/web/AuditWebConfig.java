package com.mycompanyname.zero.audit.web;

import com.mycompanyname.zero.audit.http.AuditLogInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the {@link AuditLogInterceptor} for all API routes. Exclusions are claimed by the
 * handlers themselves ({@code EndpointPolicy.Exposure.AUDIT_EXEMPT}) and read off the resolved
 * {@code HandlerMethod} inside the interceptor — no path list lives here or there.
 */
@Configuration
@RequiredArgsConstructor
public class AuditWebConfig implements WebMvcConfigurer {

    private final AuditLogInterceptor auditLogInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(auditLogInterceptor).addPathPatterns("/api/**");
    }
}
