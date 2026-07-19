package com.mycompanyname.zero.identity.web;

import com.mycompanyname.zero.identity.domain.AppPermissions;
import com.mycompanyname.zero.identity.role.RoleService;
import com.mycompanyname.zero.identity.web.dto.CreateRoleRequest;
import com.mycompanyname.zero.identity.web.dto.RoleDetailDto;
import com.mycompanyname.zero.identity.web.dto.RoleDto;
import com.mycompanyname.zero.identity.web.dto.UpdateRoleRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    @GetMapping
    @PreAuthorize("hasAuthority('" + AppPermissions.ROLES_READ + "')")
    public Page<RoleDto> list(Pageable pageable) {
        return roleService.list(pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + AppPermissions.ROLES_READ + "')")
    public RoleDetailDto getById(@PathVariable Long id) {
        return roleService.getById(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + AppPermissions.ROLES_CREATE + "')")
    @ResponseStatus(HttpStatus.CREATED)
    public RoleDetailDto create(@Valid @RequestBody CreateRoleRequest request) {
        return roleService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + AppPermissions.ROLES_UPDATE + "')")
    public RoleDetailDto update(@PathVariable Long id, @Valid @RequestBody UpdateRoleRequest request) {
        return roleService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + AppPermissions.ROLES_DELETE + "')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        roleService.delete(id);
    }

    @PostMapping("/{id}/clone")
    @PreAuthorize("hasAuthority('" + AppPermissions.ROLES_CREATE + "')")
    @ResponseStatus(HttpStatus.CREATED)
    public RoleDetailDto clone(@PathVariable Long id) {
        return roleService.clone(id);
    }
}
