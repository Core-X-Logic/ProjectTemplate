package com.mycompanyname.zero.settings;

import com.mycompanyname.zero.settings.domain.Scope;
import com.mycompanyname.zero.settings.domain.Setting;
import com.mycompanyname.zero.settings.domain.SettingDefinition;
import com.mycompanyname.zero.settings.domain.SettingDefinitions;
import com.mycompanyname.zero.settings.domain.SettingRepository;
import com.mycompanyname.zero.shared.domain.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Resolves and mutates hierarchical settings. The manager is deliberately <em>pure</em>: callers pass
 * the {@code tenantId}/{@code userId} explicitly (a web adapter derives them from the security
 * context), so this class has no dependency on the identity module.
 *
 * <p>Reads fall back through USER -&gt; TENANT -&gt; APPLICATION -&gt; definition default and are cached in
 * the {@code settings} cache; any write evicts the whole cache to keep fallbacks consistent.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SettingManager {

    private final SettingRepository settingRepository;

    /**
     * Resolves the effective value of {@code name} for the given tenant/user, honouring the scopes the
     * setting is allowed to be stored at. {@code tenantId}/{@code userId} may be {@code null} (host /
     * anonymous), in which case those scopes are skipped.
     */
    @Cacheable(value = "settings",
            key = "#name + '|' + (#tenantId != null ? #tenantId : 'app') + '|' + (#userId != null ? #userId : 'none')")
    public String getOrDefault(String name, Long tenantId, Long userId) {
        SettingDefinition definition = SettingDefinitions.require(name);
        if (userId != null && definition.scopes().contains(Scope.USER)) {
            Optional<Setting> value = settingRepository.findByScopeAndScopeIdAndName(Scope.USER, userId, name);
            if (value.isPresent()) {
                return value.get().getValue();
            }
        }
        if (tenantId != null && definition.scopes().contains(Scope.TENANT)) {
            Optional<Setting> value = settingRepository.findByScopeAndScopeIdAndName(Scope.TENANT, tenantId, name);
            if (value.isPresent()) {
                return value.get().getValue();
            }
        }
        if (definition.scopes().contains(Scope.APPLICATION)) {
            Optional<Setting> value = settingRepository.findByScopeAndScopeIdIsNullAndName(Scope.APPLICATION, name);
            if (value.isPresent()) {
                return value.get().getValue();
            }
        }
        return definition.defaultValue();
    }

    /**
     * Creates or updates the stored value for {@code name} at {@code scope}. APPLICATION scope forces a
     * {@code null} scopeId; TENANT/USER scope require a non-null scopeId. Rejects unknown settings and
     * scopes not permitted by the definition.
     */
    @Transactional
    @CacheEvict(value = "settings", allEntries = true)
    public void set(Scope scope, Long scopeId, String name, String value) {
        SettingDefinition definition = SettingDefinitions.require(name);
        if (!definition.scopes().contains(scope)) {
            throw DomainException.validation("Setting '" + name + "' cannot be stored at scope " + scope);
        }
        Long normalizedScopeId = scope == Scope.APPLICATION ? null : scopeId;
        if (scope != Scope.APPLICATION && normalizedScopeId == null) {
            throw DomainException.validation("Scope " + scope + " requires a scope id");
        }
        Optional<Setting> existing = normalizedScopeId == null
                ? settingRepository.findByScopeAndScopeIdIsNullAndName(scope, name)
                : settingRepository.findByScopeAndScopeIdAndName(scope, normalizedScopeId, name);
        Setting setting = existing.orElseGet(Setting::new);
        setting.setScope(scope);
        setting.setScopeId(normalizedScopeId);
        setting.setName(name);
        setting.setValue(value);
        settingRepository.save(setting);
    }
}
