package com.mycompanyname.zero.saas.feature.web.dto;

/**
 * One entry of a batch feature write. A {@code null} or blank {@code value} clears the override at
 * that level, so resolution falls through to the next one.
 */
public record FeatureValueDto(String name, String value) {
}
