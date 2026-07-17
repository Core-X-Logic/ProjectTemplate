package com.mycompanyname.zero.tenancy;

import com.mycompanyname.zero.shared.domain.AbstractAuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "tenants")
@Getter
@Setter
public class Tenant extends AbstractAuditedEntity {

    @Column(name = "name", nullable = false, unique = true, length = 30)
    private String name;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(name = "active", nullable = false)
    private boolean active = true;
}
