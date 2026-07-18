package com.mycompanyname.zero.saas.subscription;

import java.math.BigDecimal;

/**
 * Outcome of a proration calculation.
 *
 * @param amount          the amount to charge for the edition change; negative on a downgrade,
 *                        which in this slice simply means "nothing to collect"
 * @param remainingRatio  the unused fraction of the current billing period, in {@code [0, 1]}
 * @param paymentRequired {@code false} when {@code amount} is below the configured minimum, in
 *                        which case the edition changes immediately and no payment is requested
 *                        (source rule: {@code MinimumUpgradePaymentAmount})
 */
public record ProrationResult(BigDecimal amount, BigDecimal remainingRatio, boolean paymentRequired) {
}
