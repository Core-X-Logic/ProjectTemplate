package com.mycompanyname.zero.saas.subscription.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Subscription projection. {@code priceAmount}/{@code priceCurrency}/{@code billingPeriod} are the
 * snapshot taken at assignment time, not the edition's current price.
 */
public record SubscriptionDto(
        Long id,
        Long tenantId,
        String tenantName,
        Long editionId,
        String editionName,
        String editionDisplayName,
        String status,
        String billingPeriod,
        BigDecimal priceAmount,
        String priceCurrency,
        Instant trialEndAt,
        Instant currentPeriodEndAt,
        Instant graceEndAt,
        Instant cancelledAt) {
}
