package com.mycompanyname.zero.saas;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A {@link Clock} that tracks real time but can be shifted forward, so a test can watch a
 * subscription cross its trial/period/grace deadlines without waiting for them.
 *
 * <p>An <em>offset</em> rather than a fixed instant on purpose: everything the production code does
 * around the shift (creating a subscription, activating it) still gets monotonically increasing
 * timestamps, which is what the {@code created_at}/{@code occurred_at} ordering relies on.
 */
public class MutableClock extends Clock {

    private volatile Duration offset = Duration.ZERO;

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return Instant.now().plus(offset);
    }

    /** Moves the clock forward; cumulative across calls. */
    public void advance(Duration amount) {
        offset = offset.plus(amount);
    }

    /** Back to real time. Tests must call this in teardown — the bean is shared by the context. */
    public void reset() {
        offset = Duration.ZERO;
    }
}
