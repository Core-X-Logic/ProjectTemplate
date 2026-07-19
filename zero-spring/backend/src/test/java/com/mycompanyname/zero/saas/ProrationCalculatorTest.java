package com.mycompanyname.zero.saas;

import com.mycompanyname.zero.saas.subscription.BillingPeriod;
import com.mycompanyname.zero.saas.subscription.ProrationCalculator;
import com.mycompanyname.zero.saas.subscription.ProrationResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage of proration.
 *
 * <p>Two things are being pinned down. First, the result: {@code priceForUnused(target) -
 * priceForUnused(current)} over the unused remainder of the period. Second, that no 30/365-day
 * constant creeps back in — a calendar month is whatever the calendar says, including the
 * end-of-month clamp (see ARCHITECTURE-RULES.md — "Tarih aritmetiği java.time, ay sonu clamp'lenir").
 */
class ProrationCalculatorTest {

    private static final BigDecimal MINIMUM = new BigDecimal("1.00");

    private final ProrationCalculator calculator = new ProrationCalculator(MINIMUM);

    // --- the source formula, restated ---

    @Test
    void halfThePeriodLeftChargesHalfTheDifference() {
        Instant start = Instant.parse("2026-03-01T00:00:00Z");
        Instant end = Instant.parse("2026-04-01T00:00:00Z");          // 31 days
        Instant now = start.plus(Duration.ofDays(15).plusHours(12));  // exactly half

        ProrationResult result = calculator.calculate(
                new BigDecimal("10.00"), new BigDecimal("30.00"), start, end, now);

        // (30 - 10) * 0.5
        assertThat(result.amount()).isEqualByComparingTo("10.0000");
        assertThat(result.remainingRatio()).isEqualByComparingTo("0.5");
        assertThat(result.paymentRequired()).isTrue();
    }

    @Test
    void aFullyUnusedPeriodChargesTheWholeDifference() {
        Instant start = Instant.parse("2026-03-01T00:00:00Z");
        Instant end = Instant.parse("2026-04-01T00:00:00Z");

        ProrationResult result = calculator.calculate(
                new BigDecimal("10.00"), new BigDecimal("30.00"), start, end, start);

        assertThat(result.amount()).isEqualByComparingTo("20.0000");
        assertThat(result.remainingRatio()).isEqualByComparingTo("1");
    }

    @Test
    void anElapsedPeriodChargesNothingRatherThanACredit() {
        Instant start = Instant.parse("2026-03-01T00:00:00Z");
        Instant end = Instant.parse("2026-04-01T00:00:00Z");

        ProrationResult result = calculator.calculate(
                new BigDecimal("10.00"), new BigDecimal("30.00"), start, end, end.plusSeconds(1));

        assertThat(result.remainingRatio()).isEqualByComparingTo("0");
        assertThat(result.amount()).isEqualByComparingTo("0.0000");
        assertThat(result.paymentRequired())
                .as("nothing to collect means nothing to charge for")
                .isFalse();
    }

    @Test
    void aDowngradeYieldsANegativeAmountAndNeverRequestsPayment() {
        Instant start = Instant.parse("2026-03-01T00:00:00Z");
        Instant end = Instant.parse("2026-04-01T00:00:00Z");
        Instant now = start.plus(Duration.ofDays(15).plusHours(12));

        ProrationResult result = calculator.calculate(
                new BigDecimal("30.00"), new BigDecimal("10.00"), start, end, now);

        assertThat(result.amount()).isEqualByComparingTo("-10.0000");
        assertThat(result.paymentRequired())
                .as("a downgrade is never a charge; whether it becomes a credit is up to the caller")
                .isFalse();
    }

    @Test
    void anAbsentPeriodMeansAFullPeriodIsBeingSold() {
        ProrationResult result = calculator.calculate(
                null, new BigDecimal("30.00"), null, null, Instant.parse("2026-03-15T00:00:00Z"));

        assertThat(result.remainingRatio())
                .as("an unlimited (free) subscription has no unused remainder to discount")
                .isEqualByComparingTo("1");
        assertThat(result.amount()).isEqualByComparingTo("30.0000");
    }

    // --- minimum threshold (source: MinimumUpgradePaymentAmount) ---

    @Test
    void anAmountBelowTheMinimumRequestsNoPaymentSoTheEditionChangesImmediately() {
        Instant start = Instant.parse("2026-03-01T00:00:00Z");
        Instant end = Instant.parse("2026-04-01T00:00:00Z");
        Instant now = start.plus(Duration.ofDays(15).plusHours(12));

        // (11.00 - 10.00) * 0.5 = 0.50, below the 1.00 threshold
        ProrationResult result = calculator.calculate(
                new BigDecimal("10.00"), new BigDecimal("11.00"), start, end, now);

        assertThat(result.amount()).isEqualByComparingTo("0.5000");
        assertThat(result.paymentRequired()).isFalse();
    }

    @Test
    void anAmountExactlyAtTheMinimumStillRequestsPayment() {
        Instant start = Instant.parse("2026-03-01T00:00:00Z");
        Instant end = Instant.parse("2026-04-01T00:00:00Z");

        ProrationResult result = calculator.calculate(
                new BigDecimal("10.00"), new BigDecimal("11.00"), start, end, start);

        assertThat(result.amount()).isEqualByComparingTo("1.0000");
        assertThat(result.paymentRequired())
                .as("the threshold is inclusive: 'below the minimum' must mean strictly below")
                .isTrue();
    }

    // --- end-of-month clamp (ADR-0013): no 30- or 365-day constants ---

    @Test
    void oneMonthFromTheEndOfJanuaryClampsToTheEndOfFebruary() {
        assertThat(BillingPeriod.MONTHLY.advance(Instant.parse("2026-01-31T00:00:00Z")))
                .as("31 Jan + 1 month must clamp to 28 Feb in a non-leap year, not to 2 March")
                .isEqualTo(Instant.parse("2026-02-28T00:00:00Z"));
        assertThat(BillingPeriod.MONTHLY.advance(Instant.parse("2024-01-31T00:00:00Z")))
                .as("and to 29 Feb in a leap year")
                .isEqualTo(Instant.parse("2024-02-29T00:00:00Z"));
        assertThat(BillingPeriod.ANNUAL.advance(Instant.parse("2024-02-29T00:00:00Z")))
                .as("29 Feb + 1 year must clamp to 28 Feb")
                .isEqualTo(Instant.parse("2025-02-28T00:00:00Z"));
    }

    @Test
    void theClampedFebruaryPeriodIsPricedOverItsRealLengthNotOverThirtyDays() {
        // A period ending 28 Feb 2026 started 28 Jan 2026: 31 real days, not the source's flat 30.
        Instant end = Instant.parse("2026-02-28T00:00:00Z");
        Instant start = BillingPeriod.MONTHLY.rewind(end);
        assertThat(start).isEqualTo(Instant.parse("2026-01-28T00:00:00Z"));
        assertThat(Duration.between(start, end).toDays()).isEqualTo(31);

        // Exactly one day left of those 31.
        ProrationResult result = calculator.calculate(
                BigDecimal.ZERO, new BigDecimal("31.00"), start, end, end.minus(Duration.ofDays(1)));

        assertThat(result.amount())
                .as("31.00 over 31 real days is 1.00 for the final day; a 30-day constant would say 1.0333")
                .isEqualByComparingTo("1.0000");
    }

    @Test
    void aShortFebruaryPeriodIsAlsoPricedOverItsRealLength() {
        // 28 Feb -> 28 Mar is 28 days.
        Instant start = Instant.parse("2026-02-28T00:00:00Z");
        Instant end = BillingPeriod.MONTHLY.advance(start);
        assertThat(Duration.between(start, end).toDays()).isEqualTo(28);

        ProrationResult result = calculator.calculate(
                BigDecimal.ZERO, new BigDecimal("28.00"), start, end, end.minus(Duration.ofDays(7)));

        assertThat(result.amount())
                .as("28.00 over 28 real days is 7.00 for the final week")
                .isEqualByComparingTo("7.0000");
    }
}
