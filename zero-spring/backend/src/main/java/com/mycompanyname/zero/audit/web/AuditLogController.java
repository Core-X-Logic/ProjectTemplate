package com.mycompanyname.zero.audit.web;

import com.mycompanyname.zero.audit.AuditLogService;
import com.mycompanyname.zero.audit.web.dto.AuditLogDto;
import com.mycompanyname.zero.audit.web.dto.EntityChangeDto;
import com.mycompanyname.zero.shared.domain.DomainException;
import com.mycompanyname.zero.shared.domain.ErrorCode;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService auditLogService;

    @GetMapping("/api/audit-logs")
    @PreAuthorize("hasAuthority('auditlogs.read')")
    public Page<AuditLogDto> list(@RequestParam(required = false) String userName,
                                  @RequestParam(required = false) String startDate,
                                  @RequestParam(required = false) String endDate,
                                  @RequestParam(required = false) Integer minDuration,
                                  @RequestParam(required = false) Integer httpStatus,
                                  Pageable pageable) {
        return auditLogService.search(userName, parseInstant(startDate), parseInstant(endDate),
                minDuration, httpStatus, pageable);
    }

    @GetMapping("/api/audit-logs/export")
    @PreAuthorize("hasAuthority('auditlogs.read')")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String userName,
                                         @RequestParam(required = false) String startDate,
                                         @RequestParam(required = false) String endDate,
                                         @RequestParam(required = false) Integer minDuration,
                                         @RequestParam(required = false) Integer httpStatus) {
        byte[] body = auditLogService.export(userName, parseInstant(startDate), parseInstant(endDate),
                minDuration, httpStatus);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit-logs.xlsx\"")
                .contentType(MediaType.parseMediaType(AuditLogService.xlsxContentType()))
                .body(body);
    }

    @GetMapping("/api/entity-changes")
    @PreAuthorize("hasAuthority('auditlogs.read')")
    public Page<EntityChangeDto> entityChanges(@RequestParam(required = false) String entityTypeName,
                                               @RequestParam(required = false) String entityId,
                                               Pageable pageable) {
        return auditLogService.searchChanges(entityTypeName, entityId, pageable);
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            return Instant.parse(trimmed);
        } catch (DateTimeException ignored) {
            // try next format
        }
        try {
            return OffsetDateTime.parse(trimmed).toInstant();
        } catch (DateTimeException ignored) {
            // try next format
        }
        try {
            return LocalDateTime.parse(trimmed).toInstant(ZoneOffset.UTC);
        } catch (DateTimeException ignored) {
            // try next format
        }
        try {
            return LocalDate.parse(trimmed).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeException ignored) {
            throw new DomainException(ErrorCode.VALIDATION, "Invalid date format: " + value);
        }
    }
}
