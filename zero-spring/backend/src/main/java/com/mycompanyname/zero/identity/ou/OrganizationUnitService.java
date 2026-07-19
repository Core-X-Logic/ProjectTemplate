package com.mycompanyname.zero.identity.ou;

import com.mycompanyname.zero.identity.ou.web.dto.CreateOuRequest;
import com.mycompanyname.zero.identity.ou.web.dto.MoveOuRequest;
import com.mycompanyname.zero.identity.ou.web.dto.OuDto;
import com.mycompanyname.zero.identity.ou.web.dto.UpdateOuRequest;
import com.mycompanyname.zero.identity.user.OuMembershipService;
import com.mycompanyname.zero.shared.domain.DomainException;
import com.mycompanyname.zero.shared.domain.ErrorCode;
import com.mycompanyname.zero.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Materialized-path organization-unit tree service.
 *
 * <p>{@code code} is a dot-separated chain of 5-digit zero-padded units: a root is {@code 00001},
 * its first child {@code 00001.00001}, and so on. All reads/writes run inside {@code @Transactional}
 * {@code @Service} methods, so the tenant/host Hibernate filter is active and every query is scoped
 * to the current tenant. Lookups by id use {@code findById} (filters do not apply there) and verify
 * tenant ownership explicitly.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class OrganizationUnitService {

    private static final int CODE_UNIT_LENGTH = 5;
    private static final long FIRST_UNIT = 1L;

    private final OrganizationUnitRepository repository;
    private final OuMembershipService ouMembershipService;

    @Transactional(readOnly = true)
    public List<OuDto> tree() {
        List<OrganizationUnit> all = repository.findAllByOrderByCodeAsc();
        List<Long> ids = all.stream().map(OrganizationUnit::getId).toList();
        Map<Long, Long> counts = ouMembershipService.memberCounts(ids);
        List<OuDto> result = new ArrayList<>(all.size());
        for (OrganizationUnit ou : all) {
            result.add(toDto(ou, counts.getOrDefault(ou.getId(), 0L)));
        }
        return result;
    }

    public OuDto create(CreateOuRequest request) {
        Long tenantId = TenantContext.getTenantId();
        String parentCode = null;
        if (request.parentId() != null) {
            parentCode = getInTenantOrThrow(request.parentId()).getCode();
        }
        OrganizationUnit ou = new OrganizationUnit();
        ou.setTenantId(tenantId);
        ou.setParentId(request.parentId());
        ou.setCode(nextChildCode(request.parentId(), parentCode));
        ou.setDisplayName(request.displayName());
        return toDto(repository.save(ou), 0L);
    }

    public OuDto update(Long id, UpdateOuRequest request) {
        OrganizationUnit ou = getInTenantOrThrow(id);
        ou.setDisplayName(request.displayName());
        return toDto(repository.save(ou), memberCount(id));
    }

    /**
     * Reparents a unit and rewrites the {@code code} of the unit and its entire subtree in a single
     * transaction. Moving a unit under itself or one of its descendants is rejected.
     */
    public OuDto move(Long id, MoveOuRequest request) {
        OrganizationUnit ou = getInTenantOrThrow(id);
        Long newParentId = request.newParentId();
        if (Objects.equals(ou.getParentId(), newParentId)) {
            return toDto(ou, memberCount(id));
        }
        String oldCode = ou.getCode();
        String newParentCode = null;
        if (newParentId != null) {
            if (Objects.equals(newParentId, id)) {
                throw new DomainException(ErrorCode.VALIDATION,
                        "An organization unit cannot be moved under itself");
            }
            OrganizationUnit newParent = getInTenantOrThrow(newParentId);
            if (newParent.getCode().equals(oldCode) || newParent.getCode().startsWith(oldCode + ".")) {
                throw new DomainException(ErrorCode.VALIDATION,
                        "An organization unit cannot be moved under one of its own descendants");
            }
            newParentCode = newParent.getCode();
        }
        String newCode = nextChildCode(newParentId, newParentCode);
        List<OrganizationUnit> descendants = repository.findByCodeStartingWithOrderByCodeAsc(oldCode + ".");
        for (OrganizationUnit descendant : descendants) {
            descendant.setCode(newCode + descendant.getCode().substring(oldCode.length()));
        }
        ou.setParentId(newParentId);
        ou.setCode(newCode);
        repository.save(ou);
        repository.saveAll(descendants);
        return toDto(ou, memberCount(id));
    }

    /**
     * Deletes a unit together with its whole subtree. User assignments to those units are removed
     * by the {@code user_organization_units} {@code ON DELETE CASCADE} foreign key. The subtree is
     * deleted in a single statement, which the self-referencing {@code parent_id} NO ACTION FK allows.
     */
    public void delete(Long id) {
        OrganizationUnit ou = getInTenantOrThrow(id);
        List<OrganizationUnit> subtree = new ArrayList<>();
        subtree.add(ou);
        subtree.addAll(repository.findByCodeStartingWithOrderByCodeAsc(ou.getCode() + "."));
        List<Long> ids = subtree.stream().map(OrganizationUnit::getId).toList();
        repository.deleteByIdIn(ids);
    }

    private long memberCount(Long id) {
        return ouMembershipService.memberCounts(List.of(id)).getOrDefault(id, 0L);
    }

    private OrganizationUnit getInTenantOrThrow(Long id) {
        Long tenantId = TenantContext.getTenantId();
        return repository.findById(id)
                .filter(ou -> Objects.equals(ou.getTenantId(), tenantId))
                .orElseThrow(() -> new DomainException(
                        ErrorCode.NOT_FOUND, "Organization unit not found: " + id));
    }

    private String nextChildCode(Long parentId, String parentCode) {
        Optional<OrganizationUnit> lastSibling = parentId == null
                ? repository.findFirstByParentIdIsNullOrderByCodeDesc()
                : repository.findFirstByParentIdOrderByCodeDesc(parentId);
        if (lastSibling.isEmpty()) {
            String firstUnit = unit(FIRST_UNIT);
            return parentCode == null ? firstUnit : parentCode + "." + firstUnit;
        }
        return incrementLastUnit(lastSibling.get().getCode());
    }

    static String unit(long value) {
        return String.format("%0" + CODE_UNIT_LENGTH + "d", value);
    }

    static String incrementLastUnit(String code) {
        int lastDot = code.lastIndexOf('.');
        String prefix = lastDot < 0 ? "" : code.substring(0, lastDot + 1);
        long lastValue = Long.parseLong(code.substring(lastDot + 1));
        return prefix + unit(lastValue + 1);
    }

    private OuDto toDto(OrganizationUnit ou, long memberCount) {
        return new OuDto(ou.getId(), ou.getParentId(), ou.getCode(), ou.getDisplayName(), memberCount);
    }
}
