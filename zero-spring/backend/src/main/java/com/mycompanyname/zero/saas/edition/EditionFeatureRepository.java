package com.mycompanyname.zero.saas.edition;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EditionFeatureRepository extends JpaRepository<EditionFeature, Long> {

    List<EditionFeature> findByEditionId(Long editionId);

    Optional<EditionFeature> findByEditionIdAndFeatureName(Long editionId, String featureName);
}
