package com.mycompanyname.zero.audit;

import com.mycompanyname.zero.audit.domain.AuditLog;
import com.mycompanyname.zero.audit.domain.AuditLogRepository;
import com.mycompanyname.zero.audit.domain.EntityChange;
import com.mycompanyname.zero.audit.domain.EntityChangeRepository;
import com.mycompanyname.zero.audit.web.dto.AuditLogDto;
import com.mycompanyname.zero.audit.web.dto.EntityChangeDto;
import com.mycompanyname.zero.audit.web.dto.EntityPropertyChangeDto;
import com.mycompanyname.zero.shared.BoundedExport;
import com.mycompanyname.zero.shared.domain.DomainException;
import com.mycompanyname.zero.shared.domain.ErrorCode;
import com.mycompanyname.zero.shared.tenant.TenantContext;
import jakarta.persistence.criteria.Predicate;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Query and export surface for HTTP audit logs and entity-change history, plus the write entry point
 * used by the audit interceptor. Reads are scoped to the current tenant (host callers see every
 * tenant).
 */
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** Names this export in the refusal message; a constant, never anything the caller sent. */
    private static final String EXPORT_SUBJECT = "audit log";

    private final BoundedExport boundedExport;
    private final AuditLogRepository auditLogRepository;
    private final EntityChangeRepository entityChangeRepository;

    @Transactional
    public void save(AuditLog auditLog) {
        auditLogRepository.save(auditLog);
    }

    @Transactional(readOnly = true)
    public Page<AuditLogDto> search(String userName, Instant startDate, Instant endDate,
                                    Integer minDuration, Integer httpStatus, Pageable pageable) {
        return auditLogRepository
                .findAll(auditLogSpecification(userName, startDate, endDate, minDuration, httpStatus),
                        withDefaultSort(pageable))
                .map(AuditLogService::toDto);
    }

    /**
     * Newest-first is the meaningful default for an audit log: when the caller does not request an
     * explicit sort, order by {@code executionTime} descending so the most recent entries land on the
     * first page (deterministic paging, no dependency on insertion order).
     */
    private static Pageable withDefaultSort(Pageable pageable) {
        if (pageable == null) {
            return PageRequest.of(0, DEFAULT_PAGE_SIZE, Sort.by(Sort.Direction.DESC, "executionTime"));
        }
        if (pageable.getSort().isSorted()) {
            return pageable;
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "executionTime"));
    }

    /**
     * W5-3. Bounded by {@code BoundedExport} — this used to read every row matching the filter, and
     * an audit table is the one table in the product that only ever grows.
     *
     * <p>The fetch goes through the fluent {@code findBy} rather than {@code findAll(spec, Pageable)}
     * because the latter returns a {@code Page}, and a {@code Page} whose content fills the requested
     * size triggers a {@code count(*)} over the same predicate — precisely on the over-limit path
     * this bound exists to make cheap. {@code limit()} carries the probe size into SQL as
     * {@code fetch first N rows only} in one statement.
     */
    @Transactional(readOnly = true)
    public byte[] export(String userName, Instant startDate, Instant endDate,
                         Integer minDuration, Integer httpStatus) {
        Specification<AuditLog> specification =
                auditLogSpecification(userName, startDate, endDate, minDuration, httpStatus);
        List<AuditLog> logs = boundedExport.fetch(EXPORT_SUBJECT,
                Sort.by(Sort.Direction.DESC, "executionTime"),
                pageable -> auditLogRepository.findBy(specification,
                        query -> query.sortBy(pageable.getSort())
                                .limit(pageable.getPageSize())
                                .all()));
        return toWorkbookBytes(logs);
    }

    @Transactional(readOnly = true)
    public Page<EntityChangeDto> searchChanges(String entityTypeName, String entityId, Pageable pageable) {
        return entityChangeRepository
                .findAll(entityChangeSpecification(entityTypeName, entityId), withDefaultChangeSort(pageable))
                .map(AuditLogService::toChangeDto);
    }

    /** Newest-first default order for the entity-change history (by {@code changeTime} descending). */
    private static Pageable withDefaultChangeSort(Pageable pageable) {
        if (pageable == null) {
            return PageRequest.of(0, DEFAULT_PAGE_SIZE, Sort.by(Sort.Direction.DESC, "changeTime"));
        }
        if (pageable.getSort().isSorted()) {
            return pageable;
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "changeTime"));
    }

    public static String xlsxContentType() {
        return XLSX_CONTENT_TYPE;
    }

    private Specification<AuditLog> auditLogSpecification(String userName, Instant startDate, Instant endDate,
                                                          Integer minDuration, Integer httpStatus) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            Long tenantId = TenantContext.getTenantId();
            if (tenantId != null) {
                predicates.add(cb.equal(root.get("tenantId"), tenantId));
            }
            if (userName != null && !userName.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("username")), "%" + userName.toLowerCase() + "%"));
            }
            if (startDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.<Instant>get("executionTime"), startDate));
            }
            if (endDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.<Instant>get("executionTime"), endDate));
            }
            if (minDuration != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.<Integer>get("executionDurationMs"), minDuration));
            }
            if (httpStatus != null) {
                predicates.add(cb.equal(root.get("httpStatusCode"), httpStatus));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private Specification<EntityChange> entityChangeSpecification(String entityTypeName, String entityId) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            Long tenantId = TenantContext.getTenantId();
            if (tenantId != null) {
                predicates.add(cb.equal(root.get("tenantId"), tenantId));
            }
            if (entityTypeName != null && !entityTypeName.isBlank()) {
                predicates.add(cb.equal(root.get("entityTypeName"), entityTypeName));
            }
            if (entityId != null && !entityId.isBlank()) {
                predicates.add(cb.equal(root.get("entityId"), entityId));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private byte[] toWorkbookBytes(List<AuditLog> logs) {
        String[] headers = {"Id", "Execution Time", "Username", "Tenant Id", "HTTP Method", "URL",
                "Service", "Method", "Status", "Duration (ms)", "Client IP", "Exception"};
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Audit Logs");
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                headerRow.createCell(i).setCellValue(headers[i]);
            }
            int rowIndex = 1;
            for (AuditLog log : logs) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(log.getId() == null ? 0 : log.getId());
                row.createCell(1).setCellValue(log.getExecutionTime() == null ? "" : log.getExecutionTime().toString());
                row.createCell(2).setCellValue(nullToEmpty(log.getUsername()));
                row.createCell(3).setCellValue(log.getTenantId() == null ? "" : log.getTenantId().toString());
                row.createCell(4).setCellValue(nullToEmpty(log.getHttpMethod()));
                row.createCell(5).setCellValue(nullToEmpty(log.getUrl()));
                row.createCell(6).setCellValue(nullToEmpty(log.getServiceName()));
                row.createCell(7).setCellValue(nullToEmpty(log.getMethodName()));
                row.createCell(8).setCellValue(log.getHttpStatusCode() == null ? "" : log.getHttpStatusCode().toString());
                row.createCell(9).setCellValue(log.getExecutionDurationMs());
                row.createCell(10).setCellValue(nullToEmpty(log.getClientIp()));
                row.createCell(11).setCellValue(nullToEmpty(log.getException()));
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new DomainException(ErrorCode.INTERNAL, "Failed to generate audit log export");
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static AuditLogDto toDto(AuditLog log) {
        return new AuditLogDto(
                log.getId(),
                log.getTenantId(),
                log.getUserId(),
                log.getUsername(),
                log.getServiceName(),
                log.getMethodName(),
                log.getParameters(),
                log.getExecutionTime(),
                log.getExecutionDurationMs(),
                log.getClientIp(),
                log.getBrowserInfo(),
                log.getHttpMethod(),
                log.getUrl(),
                log.getHttpStatusCode(),
                log.getException());
    }

    private static EntityChangeDto toChangeDto(EntityChange change) {
        List<EntityPropertyChangeDto> properties = change.getPropertyChanges().stream()
                .map(property -> new EntityPropertyChangeDto(
                        property.getPropertyName(),
                        property.getOriginalValue(),
                        property.getNewValue()))
                .toList();
        return new EntityChangeDto(
                change.getId(),
                change.getTenantId(),
                change.getUserId(),
                change.getEntityTypeName(),
                change.getEntityId(),
                change.getChangeType() == null ? null : change.getChangeType().name(),
                change.getChangeTime(),
                properties);
    }
}
