package com.mycompanyname.zero.settings.domain;

import java.util.EnumSet;

/**
 * Static metadata describing a known setting: its name, fallback default, the scopes at which it may
 * be stored, whether its value is sensitive (encrypted) and whether it may be exposed to untrusted
 * clients via {@code GET /api/settings/client}.
 */
public record SettingDefinition(
        String name,
        String defaultValue,
        EnumSet<Scope> scopes,
        boolean encrypted,
        boolean visibleToClient) {
}
