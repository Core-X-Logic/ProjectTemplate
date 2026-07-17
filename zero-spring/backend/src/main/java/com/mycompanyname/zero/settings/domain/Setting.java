package com.mycompanyname.zero.settings.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A single stored setting value at a given scope. Absence of a row means "not overridden at this
 * scope"; resolution then falls back to the next scope and finally to the {@link SettingDefinition}
 * default. APPLICATION scope always uses a {@code null} scopeId.
 */
@Entity
@Table(name = "settings")
@Getter
@Setter
public class Setting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 16)
    private Scope scope;

    @Column(name = "scope_id")
    private Long scopeId;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "value", length = 2000)
    private String value;
}
