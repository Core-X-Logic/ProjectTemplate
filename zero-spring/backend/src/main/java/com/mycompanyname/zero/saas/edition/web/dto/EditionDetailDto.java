package com.mycompanyname.zero.saas.edition.web.dto;

import com.mycompanyname.zero.saas.feature.web.dto.FeatureValueDto;

import java.util.List;

/**
 * Edition detail: the summary plus the feature values stored on it. {@code features} lists every
 * known feature, with {@code null} where the edition does not override the definition default.
 */
public record EditionDetailDto(
        EditionDto edition,
        List<FeatureValueDto> features) {
}
