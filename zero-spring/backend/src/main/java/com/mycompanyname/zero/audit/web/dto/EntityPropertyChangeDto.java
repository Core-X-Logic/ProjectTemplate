package com.mycompanyname.zero.audit.web.dto;

public record EntityPropertyChangeDto(
        String propertyName,
        String originalValue,
        String newValue) {
}
