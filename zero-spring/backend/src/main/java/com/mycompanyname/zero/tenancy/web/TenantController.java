package com.mycompanyname.zero.tenancy.web;

import com.mycompanyname.zero.tenancy.TenantPermissions;
import com.mycompanyname.zero.tenancy.TenantService;
import com.mycompanyname.zero.tenancy.web.dto.CreateTenantRequest;
import com.mycompanyname.zero.tenancy.web.dto.CreateTenantResponse;
import com.mycompanyname.zero.tenancy.web.dto.TenantDto;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tenants")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('" + TenantPermissions.TENANTS_MANAGE + "')")
public class TenantController {

    private final TenantService tenantService;

    @GetMapping
    public List<TenantDto> list() {
        return tenantService.list();
    }

    @PostMapping
    public ResponseEntity<CreateTenantResponse> create(@Valid @RequestBody CreateTenantRequest request) {
        CreateTenantResponse created = tenantService.createTenant(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}/activate")
    public TenantDto activate(@PathVariable Long id) {
        return tenantService.setActive(id, true);
    }

    @PutMapping("/{id}/deactivate")
    public TenantDto deactivate(@PathVariable Long id) {
        return tenantService.setActive(id, false);
    }
}
