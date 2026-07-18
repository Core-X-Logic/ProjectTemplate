package com.mycompanyname.zero.saas.edition;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A feature value attached to an edition — the middle link of the resolution chain
 * (tenant override &rarr; <em>edition</em> &rarr; definition default). Rows are removed with their
 * edition via {@code on delete cascade}.
 */
@Entity
@Table(name = "edition_features")
@Getter
@Setter
public class EditionFeature {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "edition_id", nullable = false)
    private Long editionId;

    @Column(name = "feature_name", nullable = false, length = 128)
    private String featureName;

    @Column(name = "value", length = 2000)
    private String value;
}
