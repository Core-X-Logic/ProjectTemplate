package com.mycompanyname.zero.saas.subscription;

import com.mycompanyname.zero.shared.domain.DomainException;

import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.Locale;

/**
 * Billing cadence of a paid subscription. Periods are calendar-based ({@link Period}) rather than a
 * fixed 30/365-day count (ADR-0013): 30 days is not a month, and {@code java.time} performs the
 * end-of-month clamp for us (31 Jan + 1 month = 28/29 Feb).
 */
public enum BillingPeriod {

    MONTHLY(Period.ofMonths(1)),
    ANNUAL(Period.ofYears(1));

    private final Period period;

    BillingPeriod(Period period) {
        this.period = period;
    }

    /** {@code from} advanced by exactly one billing period, in UTC. */
    public Instant advance(Instant from) {
        return from.atOffset(ZoneOffset.UTC).plus(period).toInstant();
    }

    /**
     * {@code from} moved back by exactly one billing period, in UTC — the start of the period that
     * ends at {@code from}. Used by proration, which needs the length of the current period and
     * cannot assume 30/365 days (ADR-0013).
     */
    public Instant rewind(Instant from) {
        return from.atOffset(ZoneOffset.UTC).minus(period).toInstant();
    }

    /** Parses a client-supplied value; {@code null}/blank means "no period" (free edition). */
    public static BillingPeriod parseOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw DomainException.validation("Unknown billing period: " + raw + " (expected MONTHLY or ANNUAL)");
        }
    }
}
