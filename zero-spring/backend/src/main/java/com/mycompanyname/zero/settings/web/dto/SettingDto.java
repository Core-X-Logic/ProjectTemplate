package com.mycompanyname.zero.settings.web.dto;

/**
 * A single setting as exposed over the settings API. {@code value} is the effective (resolved) value;
 * {@code defaultValue} is the definition's fallback default, provided as a UI hint for settings
 * screens (SETTINGS fallback parity) and may be {@code null}. On write requests only {@code name}
 * and {@code value} are consumed.
 */
public record SettingDto(String name, String value, String defaultValue) {
}
