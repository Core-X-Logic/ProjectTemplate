package com.mycompanyname.zero.saas.feature.web.dto;

/**
 * A known feature as advertised to the admin UI. {@code type} drives which editor is rendered
 * (BOOLEAN switch / NUMBER input / STRING input).
 */
public record FeatureDefinitionDto(
        String name,
        String displayNameKey,
        String type,
        String defaultValue,
        boolean visibleOnPricingTable) {
}
