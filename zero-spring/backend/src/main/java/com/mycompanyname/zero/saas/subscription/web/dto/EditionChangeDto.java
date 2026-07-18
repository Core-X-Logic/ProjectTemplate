package com.mycompanyname.zero.saas.subscription.web.dto;

import java.math.BigDecimal;

/**
 * Result of an edition change (S13).
 *
 * <p>Slice B computes and reports the pro-rated amount but collects nothing: there is no billing
 * provider yet. {@code paymentRequired} tells Slice C whether a checkout has to be opened for this
 * change or whether it was below the configured minimum and therefore applied for free — the same
 * decision the source system made with {@code MinimumUpgradePaymentAmount}.
 *
 * @param subscription      the subscription after the change
 * @param prorationAmount   amount attributable to the unused remainder; negative on a downgrade
 * @param currency          currency of {@code prorationAmount}, {@code null} when the target is free
 * @param remainingRatio    unused fraction of the billing period the amount was computed from
 * @param paymentRequired   whether {@code prorationAmount} reaches {@code minimumAmount}
 * @param minimumAmount     the configured threshold ({@code zero.saas.proration.minimum-amount})
 */
public record EditionChangeDto(
        SubscriptionDto subscription,
        BigDecimal prorationAmount,
        String currency,
        BigDecimal remainingRatio,
        boolean paymentRequired,
        BigDecimal minimumAmount) {
}
