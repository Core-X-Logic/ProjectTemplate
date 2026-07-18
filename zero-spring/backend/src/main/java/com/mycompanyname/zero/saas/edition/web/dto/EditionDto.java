package com.mycompanyname.zero.saas.edition.web.dto;

import java.math.BigDecimal;

/** List/summary projection of an edition. {@code free} is computed from the price columns. */
public record EditionDto(
        Long id,
        String name,
        String displayName,
        String description,
        BigDecimal monthlyPrice,
        BigDecimal annualPrice,
        String currency,
        int trialDayCount,
        int graceDayCount,
        Long expiringEditionId,
        boolean active,
        int sortOrder,
        boolean free) {
}
