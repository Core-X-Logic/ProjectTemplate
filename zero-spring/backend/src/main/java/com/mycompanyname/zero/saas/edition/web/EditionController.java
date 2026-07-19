package com.mycompanyname.zero.saas.edition.web;

import com.mycompanyname.zero.saas.SaasPermissions;
import com.mycompanyname.zero.saas.edition.EditionService;
import com.mycompanyname.zero.saas.edition.web.dto.CreateEditionRequest;
import com.mycompanyname.zero.saas.edition.web.dto.EditionDetailDto;
import com.mycompanyname.zero.saas.edition.web.dto.EditionDto;
import com.mycompanyname.zero.saas.edition.web.dto.UpdateEditionRequest;
import com.mycompanyname.zero.saas.feature.web.dto.FeatureValueDto;
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

import java.util.List;

/**
 * Edition catalogue API. Every route is guarded by a {@code Side.HOST} permission, so a tenant admin
 * can neither read nor reshape the catalogue. Editions carry no tenant {@code @Filter}, so the
 * permission is the only thing standing between a tenant and the whole catalogue; the negative case
 * is proven by {@code SaasAuthorizationIT}. See ARCHITECTURE-RULES.md — "Tenant kendi limitini
 * yükseltemez".
 */
@RestController
@RequestMapping("/api/editions")
@RequiredArgsConstructor
public class EditionController {

    private final EditionService editionService;

    @GetMapping
    @PreAuthorize("hasAuthority('" + SaasPermissions.EDITIONS_READ + "')")
    public Page<EditionDto> list(Pageable pageable) {
        return editionService.list(pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + SaasPermissions.EDITIONS_READ + "')")
    public EditionDetailDto getById(@PathVariable Long id) {
        return editionService.getById(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + SaasPermissions.EDITIONS_MANAGE + "')")
    @ResponseStatus(HttpStatus.CREATED)
    public EditionDetailDto create(@Valid @RequestBody CreateEditionRequest request) {
        return editionService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + SaasPermissions.EDITIONS_MANAGE + "')")
    public EditionDetailDto update(@PathVariable Long id, @Valid @RequestBody UpdateEditionRequest request) {
        return editionService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + SaasPermissions.EDITIONS_MANAGE + "')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        editionService.delete(id);
    }

    /** Batch write of the edition's feature values (a blank value clears the override). */
    @PutMapping("/{id}/features")
    @PreAuthorize("hasAuthority('" + SaasPermissions.EDITIONS_MANAGE + "')")
    public EditionDetailDto setFeatures(@PathVariable Long id, @RequestBody List<FeatureValueDto> values) {
        return editionService.setFeatures(id, values);
    }
}
