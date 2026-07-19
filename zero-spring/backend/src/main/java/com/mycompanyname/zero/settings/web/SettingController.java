package com.mycompanyname.zero.settings.web;

import com.mycompanyname.zero.settings.SettingManager;
import com.mycompanyname.zero.settings.SettingsPermissions;
import com.mycompanyname.zero.settings.domain.Scope;
import com.mycompanyname.zero.settings.domain.SettingDefinition;
import com.mycompanyname.zero.settings.domain.SettingDefinitions;
import com.mycompanyname.zero.settings.web.dto.SettingDto;
import com.mycompanyname.zero.shared.domain.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings API. The controller is the web adapter that keeps {@link SettingManager} pure: it derives
 * {@code tenantId}/{@code userId} from the authenticated JWT (Spring Security, not the identity
 * module) and feeds them to the manager. Batch updates are accepted as a list of {@code {name, value}}
 * pairs; reads return the same shape (tenant/host) or a flat name-&gt;value map (client).
 */
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingController {

    private final SettingManager settingManager;

    // --- Tenant scope ---

    @GetMapping("/tenant")
    @PreAuthorize("hasAuthority('" + SettingsPermissions.SETTINGS_TENANT + "')")
    public List<SettingDto> tenantSettings(@AuthenticationPrincipal Jwt jwt) {
        Long tenantId = requireTenantId(jwt);
        return resolve(SettingDefinitions.forScope(Scope.TENANT), tenantId, null);
    }

    @PutMapping("/tenant")
    @PreAuthorize("hasAuthority('" + SettingsPermissions.SETTINGS_TENANT + "')")
    public List<SettingDto> updateTenantSettings(@AuthenticationPrincipal Jwt jwt,
                                                 @RequestBody List<SettingDto> updates) {
        Long tenantId = requireTenantId(jwt);
        for (SettingDto update : updates) {
            settingManager.set(Scope.TENANT, tenantId, update.name(), update.value());
        }
        return resolve(SettingDefinitions.forScope(Scope.TENANT), tenantId, null);
    }

    // --- Host / application scope ---

    @GetMapping("/host")
    @PreAuthorize("hasAuthority('" + SettingsPermissions.SETTINGS_HOST + "')")
    public List<SettingDto> hostSettings() {
        return resolve(SettingDefinitions.ALL, null, null);
    }

    @PutMapping("/host")
    @PreAuthorize("hasAuthority('" + SettingsPermissions.SETTINGS_HOST + "')")
    public List<SettingDto> updateHostSettings(@RequestBody List<SettingDto> updates) {
        for (SettingDto update : updates) {
            settingManager.set(Scope.APPLICATION, null, update.name(), update.value());
        }
        return resolve(SettingDefinitions.ALL, null, null);
    }

    // --- Client bootstrap ---

    /**
     * Bootstrap settings for the SPA. Authentication only, no permission: this is an explicit
     * allowlist — {@link SettingDefinitions#clientVisible()} — of the values every signed-in user
     * needs before any screen can render, and scoping is principal-derived (tenant and user come
     * from the JWT, never from the request). A permission would have to be granted to every user to
     * keep the app usable, which is a permission that decides nothing; the real control is what the
     * allowlist contains, and that is enforced in the definitions, not here.
     */
    @GetMapping("/client")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.OK)
    public Map<String, String> clientSettings(@AuthenticationPrincipal Jwt jwt) {
        Long tenantId = tenantId(jwt);
        Long userId = userId(jwt);
        Map<String, String> result = new LinkedHashMap<>();
        for (SettingDefinition definition : SettingDefinitions.clientVisible()) {
            result.put(definition.name(), settingManager.getOrDefault(definition.name(), tenantId, userId));
        }
        return result;
    }

    private List<SettingDto> resolve(List<SettingDefinition> definitions, Long tenantId, Long userId) {
        return definitions.stream()
                .map(definition -> new SettingDto(definition.name(),
                        settingManager.getOrDefault(definition.name(), tenantId, userId),
                        definition.defaultValue()))
                .toList();
    }

    private Long requireTenantId(Jwt jwt) {
        Long tenantId = tenantId(jwt);
        if (tenantId == null) {
            throw DomainException.validation("A tenant context is required to manage tenant settings");
        }
        return tenantId;
    }

    private Long tenantId(Jwt jwt) {
        Object tenant = jwt == null ? null : jwt.getClaim("tenant");
        return tenant == null ? null : ((Number) tenant).longValue();
    }

    private Long userId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            return null;
        }
        return Long.valueOf(jwt.getSubject());
    }
}
