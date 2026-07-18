package com.mycompanyname.zero.saas.edition.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Update payload. Prices are deliberately editable (ADR-0012): existing subscribers keep the price
 * snapshotted on their subscription, so an edition never has to be frozen and re-created.
 *
 * <p>{@code name} is intentionally absent — it is the stable technical identifier, exactly as with
 * roles; rename the {@code displayName} instead.
 */
public record UpdateEditionRequest(
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
