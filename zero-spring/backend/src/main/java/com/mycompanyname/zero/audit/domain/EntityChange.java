package com.mycompanyname.zero.audit.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * A single create/update/delete of a tracked entity, with its property-level diff. Populated by the
 * Hibernate {@code EntityChangeListener}.
 */
@Entity
@Table(name = "entity_changes")
@Getter
@Setter
public class EntityChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "entity_type_name", nullable = false, length = 256)
    private String entityTypeName;

    @Column(name = "entity_id", nullable = false, length = 64)
    private String entityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 16)
    private EntityChangeType changeType;

    @Column(name = "change_time", nullable = false)
    private Instant changeTime;

    @OneToMany(mappedBy = "entityChange", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<EntityPropertyChange> propertyChanges = new ArrayList<>();

    public void addPropertyChange(EntityPropertyChange propertyChange) {
        propertyChange.setEntityChange(this);
        propertyChanges.add(propertyChange);
    }
}
