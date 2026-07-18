package com.mycompanyname.zero.saas.feature;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Host-managed per-tenant override of a feature value; the top of the resolution chain. Absence of
 * a row means "not overridden for this tenant", so resolution falls through to the tenant's edition
 * and finally to the {@link FeatureDefinition} default.
 *
 * <p>No Hibernate tenant filter is declared: every write path is host-only and every read is scoped
 * by an explicit {@code tenantId} argument, so a filter would only hide rows from the host.
 */
@Entity
@Table(name = "tenant_features")
@Getter
@Setter
public class TenantFeature {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "feature_name", nullable = false, length = 128)
    private String featureName;

    @Column(name = "value", length = 2000)
    private String value;
}
