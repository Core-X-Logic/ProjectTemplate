package com.mycompanyname.zero.saas.feature;

import com.mycompanyname.zero.saas.api.SaasFeatures;
import com.mycompanyname.zero.shared.domain.DomainException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Central registry of every feature the platform understands — a deliberate mirror of
 * {@code SettingDefinitions} (static list, {@code byName} lookup, unknown name is a VALIDATION
 * error). New features MUST be registered here to be storable on an edition or a tenant.
 */
public final class FeatureDefinitions {

    public static final FeatureDefinition MAX_USER_COUNT = def(
            SaasFeatures.MAX_USER_COUNT, FeatureType.NUMBER, "0", true);

    public static final FeatureDefinition AUDIT_LOG = def(
            SaasFeatures.AUDIT_LOG, FeatureType.BOOLEAN, "true", true);

    public static final FeatureDefinition ORGANIZATION_UNITS = def(
            SaasFeatures.ORGANIZATION_UNITS, FeatureType.BOOLEAN, "true", true);

    public static final List<FeatureDefinition> ALL = List.of(
            MAX_USER_COUNT,
            AUDIT_LOG,
            ORGANIZATION_UNITS);

    private static final Map<String, FeatureDefinition> BY_NAME = index();

    private FeatureDefinitions() {
    }

    private static FeatureDefinition def(String name, FeatureType type, String defaultValue,
                                         boolean visibleOnPricingTable) {
        return new FeatureDefinition(name, "Feature." + name, type, defaultValue, visibleOnPricingTable);
    }

    private static Map<String, FeatureDefinition> index() {
        Map<String, FeatureDefinition> map = new LinkedHashMap<>();
        for (FeatureDefinition definition : ALL) {
            map.put(definition.name(), definition);
        }
        return Map.copyOf(map);
    }

    /** Returns the definition for {@code name} or throws VALIDATION when it is not a known feature. */
    public static FeatureDefinition require(String name) {
        FeatureDefinition definition = name == null ? null : BY_NAME.get(name);
        if (definition == null) {
            throw DomainException.validation("Unknown feature: " + name);
        }
        return definition;
    }

    public static List<FeatureDefinition> pricingTableVisible() {
        return ALL.stream().filter(FeatureDefinition::visibleOnPricingTable).toList();
    }

    /**
     * Validates {@code value} against the declared type of {@code name} and returns the normalized
     * form that is safe to persist. A {@code null} or blank value means "no override at this level"
     * and is returned as {@code null} so the caller can delete the row.
     */
    public static String normalize(String name, String value) {
        FeatureDefinition definition = require(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return switch (definition.type()) {
            case BOOLEAN -> normalizeBoolean(name, trimmed);
            case NUMBER -> normalizeNumber(name, trimmed);
            case STRING -> trimmed;
        };
    }

    private static String normalizeBoolean(String name, String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (!"true".equals(lower) && !"false".equals(lower)) {
            throw DomainException.validation(
                    "Feature '" + name + "' is BOOLEAN and only accepts 'true' or 'false', got: " + value);
        }
        return lower;
    }

    private static String normalizeNumber(String name, String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) {
                throw DomainException.validation("Feature '" + name + "' must not be negative, got: " + value);
            }
            return Long.toString(parsed);
        } catch (NumberFormatException ex) {
            throw DomainException.validation(
                    "Feature '" + name + "' is NUMBER and only accepts an integer value, got: " + value);
        }
    }
}
