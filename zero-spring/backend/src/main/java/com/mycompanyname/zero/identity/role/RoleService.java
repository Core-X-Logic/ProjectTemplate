package com.mycompanyname.zero.identity.role;

import com.mycompanyname.zero.identity.domain.AppPermissions;
import com.mycompanyname.zero.identity.domain.PermissionDefinitions;
import com.mycompanyname.zero.identity.domain.Role;
import com.mycompanyname.zero.identity.repo.RoleRepository;
import com.mycompanyname.zero.identity.repo.UserRepository;
import com.mycompanyname.zero.identity.web.dto.CreateRoleRequest;
import com.mycompanyname.zero.identity.web.dto.RoleDetailDto;
import com.mycompanyname.zero.identity.web.dto.RoleDto;
import com.mycompanyname.zero.identity.web.dto.UpdateRoleRequest;
import com.mycompanyname.zero.shared.domain.DomainException;
import com.mycompanyname.zero.shared.domain.ErrorCode;
import com.mycompanyname.zero.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional
public class RoleService {

    private static final String CLONE_SUFFIX = "_copy";

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public Page<RoleDto> list(Pageable pageable) {
        Long tenantId = TenantContext.getTenantId();
        Page<Role> page = tenantId == null
                ? roleRepository.findAllByTenantIdIsNull(pageable)
                : roleRepository.findAllByTenantId(tenantId, pageable);
        return page.map(this::toDto);
    }

    @Transactional(readOnly = true)
    public RoleDetailDto getById(Long id) {
        return toDetailDto(getInCurrentTenantOrThrow(id));
    }

    public RoleDetailDto create(CreateRoleRequest request) {
        Long tenantId = TenantContext.getTenantId();
        Set<String> permissions = sanitizePermissions(request.permissions(), tenantId);
        ensureNameAvailable(tenantId, request.name());

        Role role = new Role();
        role.setTenantId(tenantId);
        role.setName(request.name());
        role.setDisplayName(request.displayName());
        role.setStatic(false);
        role.setDefault(request.isDefault());
        role.setPermissions(permissions);
        return toDetailDto(roleRepository.save(role));
    }

    public RoleDetailDto update(Long id, UpdateRoleRequest request) {
        Role role = getInCurrentTenantOrThrow(id);
        Set<String> permissions = sanitizePermissions(request.permissions(), role.getTenantId());

        // Note: the role name is intentionally immutable (UpdateRoleRequest carries no name),
        // which keeps static role names stable.
        role.setDisplayName(request.displayName());
        role.setDefault(request.isDefault());
        if (request.permissions() != null) {
            role.getPermissions().clear();
            role.getPermissions().addAll(permissions);
        }
        return toDetailDto(roleRepository.save(role));
    }

    public void delete(Long id) {
        Role role = getInCurrentTenantOrThrow(id);
        if (role.isStatic()) {
            throw new DomainException(ErrorCode.VALIDATION, "A static role cannot be deleted: " + role.getName());
        }
        long members = userRepository.countByRolesId(role.getId());
        if (members > 0) {
            throw new DomainException(ErrorCode.CONFLICT,
                    "Role is assigned to " + members + " user(s) and cannot be deleted");
        }
        roleRepository.delete(role);
    }

    public RoleDetailDto clone(Long id) {
        Role source = getInCurrentTenantOrThrow(id);
        String newName = source.getName() + CLONE_SUFFIX;
        ensureNameAvailable(source.getTenantId(), newName);

        Role copy = new Role();
        copy.setTenantId(source.getTenantId());
        copy.setName(newName);
        copy.setDisplayName(source.getDisplayName() + " (copy)");
        copy.setStatic(false);
        copy.setDefault(false);
        copy.setPermissions(new HashSet<>(source.getPermissions()));
        return toDetailDto(roleRepository.save(copy));
    }

    private Role getInCurrentTenantOrThrow(Long id) {
        Long tenantId = TenantContext.getTenantId();
        return roleRepository.findById(id)
                .filter(role -> Objects.equals(role.getTenantId(), tenantId))
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Role not found: " + id));
    }

    private void ensureNameAvailable(Long tenantId, String name) {
        boolean exists = (tenantId == null
                ? roleRepository.findByNameIgnoreCaseAndTenantIdIsNull(name)
                : roleRepository.findByTenantIdAndNameIgnoreCase(tenantId, name)).isPresent();
        if (exists) {
            throw new DomainException(ErrorCode.CONFLICT, "Role already exists: " + name);
        }
    }

    /**
     * Validates and normalizes the requested permissions. Unknown permissions are rejected, and
     * host-only permissions may never be attached to a tenant-scoped role.
     */
    private Set<String> sanitizePermissions(Set<String> requested, Long tenantId) {
        Set<String> result = new LinkedHashSet<>();
        if (requested == null) {
            return result;
        }
        Set<String> known = AppPermissions.all();
        Set<String> hostOnly = PermissionDefinitions.hostOnlyPermissionNames();
        for (String permission : requested) {
            if (permission == null || permission.isBlank()) {
                continue;
            }
            if (!known.contains(permission)) {
                throw new DomainException(ErrorCode.VALIDATION, "Unknown permission: " + permission);
            }
            if (tenantId != null && hostOnly.contains(permission)) {
                throw new DomainException(ErrorCode.VALIDATION,
                        "Host-only permission cannot be assigned to a tenant role: " + permission);
            }
            result.add(permission);
        }
        return result;
    }

    private RoleDto toDto(Role role) {
        long memberCount = userRepository.countByRolesId(role.getId());
        return new RoleDto(role.getId(), role.getName(), role.getDisplayName(),
                role.isStatic(), role.isDefault(), memberCount);
    }

    private RoleDetailDto toDetailDto(Role role) {
        return new RoleDetailDto(role.getId(), role.getName(), role.getDisplayName(),
                role.isStatic(), role.isDefault(), new LinkedHashSet<>(role.getPermissions()));
    }
}
