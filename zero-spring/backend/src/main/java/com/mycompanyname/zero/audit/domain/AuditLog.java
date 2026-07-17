package com.mycompanyname.zero.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * One row per audited HTTP request (see {@code AuditLogInterceptor}). Not an
 * {@code AbstractAuditedEntity}: it carries its own execution metadata and has no created/updated
 * bookkeeping columns.
 */
@Entity
@Table(name = "audit_logs")
@Getter
@Setter
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "username", length = 64)
    private String username;

    @Column(name = "service_name", length = 256)
    private String serviceName;

    @Column(name = "method_name", length = 256)
    private String methodName;

    @Column(name = "parameters", length = 2000)
    private String parameters;

    @Column(name = "execution_time", nullable = false)
    private Instant executionTime;

    @Column(name = "execution_duration_ms", nullable = false)
    private int executionDurationMs;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(name = "browser_info", length = 512)
    private String browserInfo;

    @Column(name = "http_method", length = 16)
    private String httpMethod;

    @Column(name = "url", length = 512)
    private String url;

    @Column(name = "http_status_code")
    private Integer httpStatusCode;

    @Column(name = "exception", length = 2000)
    private String exception;
}
