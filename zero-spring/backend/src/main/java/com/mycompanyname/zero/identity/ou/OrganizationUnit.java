package com.mycompanyname.zero.identity.ou;

import com.mycompanyname.zero.shared.domain.AbstractAuditedEntity;
import com.mycompanyname.zero.shared.domain.TrackChanges;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Filter;

/**
 * ABP-style organization unit with a materialized-path {@code code}
 * (5-digit zero-padded segments joined by dots, e.g. {@code 00001.00003}).
 * Tenant/host isolation is provided by the shared {@code tenantFilter}/{@code hostFilter}
 * definitions declared once in {@code identity.domain} package-info.
 */
@Entity
@Table(name = "organization_units")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Filter(name = "hostFilter", condition = "tenant_id is null")
@TrackChanges
@Getter
@Setter
public class OrganizationUnit extends AbstractAuditedEntity {

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "code", nullable = false, length = 95)
    private String code;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;
}
