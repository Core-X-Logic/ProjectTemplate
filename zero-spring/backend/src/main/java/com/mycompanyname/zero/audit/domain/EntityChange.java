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
import org.hibernate.annotations.Filter;

/**
 * A single create/update/delete of a tracked entity, with its property-level diff. Populated by the
 * Hibernate {@code EntityChangeListener}.
 *
 * <p><b>{@code tenantFilter} but deliberately NOT {@code hostFilter}</b>, for the same reason as
 * {@code AuditLog}: this table answers "who changed this record?", and answering it ACROSS tenants
 * is the host support feature. {@code tenant_id is null} would silently reduce host to its own
 * rows. Note the write path is unaffected either way — Hibernate applies filters to queries, not to
 * inserts, so {@code EntityChangeWriter} keeps recording every tenant's history. Both directions
 * are pinned by {@code TenantFilterCoverageIT}.
 *
 * <p>The filter is not repeated on {@link EntityPropertyChange}: that table has no
 * {@code tenant_id} and is only ever reached by navigating from a change row that has already been
 * filtered.
 */
@Entity
@Table(name = "entity_changes")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
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
