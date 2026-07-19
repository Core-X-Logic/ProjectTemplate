package com.mycompanyname.zero.saas.subscription;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;

/**
 * Prices an edition change for the unused remainder of the current billing period.
 *
 * <h2>Why this is a single ratio</h2>
 * Proration is often written as a whole-periods term plus a leftover-hours term:
 * <pre>
 * unusedPeriodCount = (remainingHours / 24) / (int) period
 * unusedHours       = remainingHours % ((int) period * 24)
 * priceForUnused(P) = P * unusedPeriodCount + (P / (int) period) / 24 * unusedHours
 * upgradePrice      = priceForUnused(target) - priceForUnused(current)
 * </pre>
 * Both terms are parts of one and the same quantity, {@code P × remaining / periodLength}. Since a
 * subscription always sits inside exactly one period, the whole-periods term is always zero and the
 * expression collapses to {@code remainingRatio × (targetPrice − currentPrice)} — which is what this
 * class computes. Splitting it back into two terms adds rounding seams for no gain.
 *
 * <p>{@code periodLength} is the real calendar distance between the period's start and end
 * ({@link BillingPeriod} uses {@link java.time.Period}, which applies the end-of-month clamp), never
 * a hard-coded 30 or 365 days — otherwise "one month" would never be February and never a 31-day
 * month, and the ratio would silently misprice. See ADR-0013 and ARCHITECTURE-RULES.md —
 * "Tarih aritmetiği java.time, ay sonu clamp'lenir".
 *
 * <p>The class is pure: no repositories, no clock, everything passed in — so the arithmetic is unit
 * testable on its own ({@code ProrationCalculatorTest}).
 */
@Component
public class ProrationCalculator {

    /** Money scale, matching {@code numeric(19,4)}. */
    public static final int AMOUNT_SCALE = 4;

    /** Intermediate scale for the time ratio; wide enough that rounding shows up only in the cents. */
    private static final int RATIO_SCALE = 12;

    private final BigDecimal minimumAmount;

    public ProrationCalculator(
            @Value("${zero.saas.proration.minimum-amount:1.00}") BigDecimal minimumAmount) {
        this.minimumAmount = minimumAmount == null ? BigDecimal.ONE : minimumAmount;
    }

    /** Below this amount no payment is requested and the edition changes immediately. */
    public BigDecimal minimumAmount() {
        return minimumAmount;
    }

    /**
     * @param currentPrice the price snapshotted on the subscription, {@code null} for a free package
     * @param targetPrice  the price of the edition being moved to, {@code null} for a free package
     * @param periodStart  start of the current billing period, {@code null} when there is none
     * @param periodEnd    end of the current billing period, {@code null} when there is none
     * @param now          the evaluation instant (comes from the injected {@code Clock})
     */
    public ProrationResult calculate(BigDecimal currentPrice, BigDecimal targetPrice,
                                     Instant periodStart, Instant periodEnd, Instant now) {
        BigDecimal ratio = remainingRatio(periodStart, periodEnd, now);
        BigDecimal amount = zeroIfNull(targetPrice)
                .subtract(zeroIfNull(currentPrice))
                .multiply(ratio)
                .setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        return new ProrationResult(amount, ratio, amount.compareTo(minimumAmount) >= 0);
    }

    /**
     * The unused fraction of the period, clamped to {@code [0, 1]}.
     *
     * <p>A {@code null} period means the subscription is unlimited (free edition) or has not started
     * a billed period yet; there is nothing to pro-rate, so a full period is being sold and the ratio
     * is 1. An already-elapsed period yields 0 rather than a negative credit.
     */
    public BigDecimal remainingRatio(Instant periodStart, Instant periodEnd, Instant now) {
        if (periodStart == null || periodEnd == null || now == null) {
            return BigDecimal.ONE;
        }
        long totalSeconds = Duration.between(periodStart, periodEnd).getSeconds();
        if (totalSeconds <= 0) {
            return BigDecimal.ZERO;
        }
        long remainingSeconds = Duration.between(now, periodEnd).getSeconds();
        if (remainingSeconds <= 0) {
            return BigDecimal.ZERO;
        }
        if (remainingSeconds >= totalSeconds) {
            return BigDecimal.ONE;
        }
        return BigDecimal.valueOf(remainingSeconds)
                .divide(BigDecimal.valueOf(totalSeconds), RATIO_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
