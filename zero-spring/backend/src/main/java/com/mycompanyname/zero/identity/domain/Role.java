package com.mycompanyname.zero.identity.domain;

import com.mycompanyname.zero.shared.domain.AbstractAuditedEntity;
import com.mycompanyname.zero.shared.domain.TrackChanges;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Filter;

import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "roles")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Filter(name = "hostFilter", condition = "tenant_id is null")
@TrackChanges
@Getter
@Setter
public class Role extends AbstractAuditedEntity {

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(name = "is_static", nullable = false)
    private boolean isStatic;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "role_permissions", joinColumns = @JoinColumn(name = "role_id"))
    @Column(name = "permission", nullable = false, length = 128)
    private Set<String> permissions = new HashSet<>();
}
