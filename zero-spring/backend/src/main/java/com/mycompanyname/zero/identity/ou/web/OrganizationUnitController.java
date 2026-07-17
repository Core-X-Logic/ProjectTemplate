package com.mycompanyname.zero.identity.ou.web;

import com.mycompanyname.zero.identity.ou.OrganizationUnitService;
import com.mycompanyname.zero.identity.ou.web.dto.CreateOuRequest;
import com.mycompanyname.zero.identity.ou.web.dto.MoveOuRequest;
import com.mycompanyname.zero.identity.ou.web.dto.OuDto;
import com.mycompanyname.zero.identity.ou.web.dto.UpdateOuRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

import java.util.List;

@RestController
@RequestMapping("/api/organization-units")
@RequiredArgsConstructor
public class OrganizationUnitController {

    private final OrganizationUnitService organizationUnitService;

    @GetMapping
    @PreAuthorize("hasAuthority('organizationunits.manage')")
    public List<OuDto> tree() {
        return organizationUnitService.tree();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('organizationunits.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public OuDto create(@Valid @RequestBody CreateOuRequest request) {
        return organizationUnitService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('organizationunits.manage')")
    public OuDto update(@PathVariable Long id, @Valid @RequestBody UpdateOuRequest request) {
        return organizationUnitService.update(id, request);
    }

    @PutMapping("/{id}/move")
    @PreAuthorize("hasAuthority('organizationunits.manage')")
    public OuDto move(@PathVariable Long id, @Valid @RequestBody MoveOuRequest request) {
        return organizationUnitService.move(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('organizationunits.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        organizationUnitService.delete(id);
    }
}
