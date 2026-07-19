package com.mycompanyname.zero.saas.subscription.web.dto;

import java.math.BigDecimal;

/**
 * Result of an edition change (S13).
 *
 * <p>The pro-rated amount is computed and reported but nothing is collected: no billing provider is
 * integrated. {@code paymentRequired} tells the caller whether a checkout has to be opened for this
 * change, or whether the amount fell below the configured minimum
 * ({@code zero.saas.proration.minimum-amount}) and the change was therefore applied for free.
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
