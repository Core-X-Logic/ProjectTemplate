package com.mycompanyname.zero.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Old/new value pair for a single property of an {@link EntityChange}. Secret-bearing properties are
 * masked as {@code ***} by the listener before reaching this entity.
 */
@Entity
@Table(name = "entity_property_changes")
@Getter
@Setter
public class EntityPropertyChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entity_change_id", nullable = false)
    private EntityChange entityChange;

    @Column(name = "property_name", nullable = false, length = 128)
    private String propertyName;

    @Column(name = "original_value", length = 2000)
    private String originalValue;

    @Column(name = "new_value", length = 2000)
    private String newValue;
}
