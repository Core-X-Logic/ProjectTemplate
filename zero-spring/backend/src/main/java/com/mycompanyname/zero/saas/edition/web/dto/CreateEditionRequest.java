package com.mycompanyname.zero.saas.edition.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Creation payload. Leaving both prices {@code null} makes the edition <em>free</em>, which in turn
 * forbids a trial ({@code trialDayCount} must stay 0) and makes it eligible as another edition's
 * {@code expiringEditionId} target.
 */
public record CreateEditionRequest(
        @NotBlank @Size(max = 64) String name,
        @NotBlank @Size(max = 128) String displayName,
        @Size(max = 512) String description,
        @DecimalMin(value = "0.0") BigDecimal monthlyPrice,
        @DecimalMin(value = "0.0") BigDecimal annualPrice,
        @Size(min = 3, max = 3) String currency,
        @PositiveOrZero Integer trialDayCount,
        @PositiveOrZero Integer graceDayCount,
        Long expiringEditionId,
        Boolean active,
        Integer sortOrder) {
}
