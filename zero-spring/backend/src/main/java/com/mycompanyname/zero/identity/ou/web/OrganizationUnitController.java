package com.mycompanyname.zero.identity.ou.web;

import com.mycompanyname.zero.identity.domain.AppPermissions;
import com.mycompanyname.zero.identity.ou.OrganizationUnitService;
import com.mycompanyname.zero.identity.ou.web.dto.CreateOuRequest;
import com.mycompanyname.zero.identity.ou.web.dto.MoveOuRequest;
import com.mycompanyname.zero.identity.ou.web.dto.OuDto;
import com.mycompanyname.zero.identity.ou.web.dto.UpdateOuRequest;
import com.mycompanyname.zero.saas.api.RequiresFeature;
import com.mycompanyname.zero.saas.api.SaasFeatures;
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

/**
 * Organization-unit hierarchy.
 *
 * <p>The whole controller sits behind the {@code app.organizationUnits} feature: the permission
 * decides <em>who</em> in a tenant may manage the hierarchy, the feature decides whether the
 * tenant's package includes it at all. A tenant whose edition switches the feature off receives 403
 * on every route here, while a host administrator (no tenant context) always resolves the definition
 * default and is never locked out.
 */
@RestController
@RequestMapping("/api/organization-units")
@RequiredArgsConstructor
@RequiresFeature(SaasFeatures.ORGANIZATION_UNITS)
public class OrganizationUnitController {

    private final OrganizationUnitService organizationUnitService;

    @GetMapping
    @PreAuthorize("hasAuthority('" + AppPermissions.OU_MANAGE + "')")
    public List<OuDto> tree() {
        return organizationUnitService.tree();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + AppPermissions.OU_MANAGE + "')")
    @ResponseStatus(HttpStatus.CREATED)
    public OuDto create(@Valid @RequestBody CreateOuRequest request) {
        return organizationUnitService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + AppPermissions.OU_MANAGE + "')")
    public OuDto update(@PathVariable Long id, @Valid @RequestBody UpdateOuRequest request) {
        return organizationUnitService.update(id, request);
    }

    @PutMapping("/{id}/move")
    @PreAuthorize("hasAuthority('" + AppPermissions.OU_MANAGE + "')")
    public OuDto move(@PathVariable Long id, @Valid @RequestBody MoveOuRequest request) {
        return organizationUnitService.move(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + AppPermissions.OU_MANAGE + "')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        organizationUnitService.delete(id);
    }
}
