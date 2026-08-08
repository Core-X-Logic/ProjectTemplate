package com.mycompanyname.zero.saas.billing.credentials;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cool-down rules in isolation, on a hand-driven clock (no Spring, no sleeping): threshold,
 * consecutive-ness, expiry, and full reset after expiry — the unit half of what
 * {@code BillingCheckoutFailoverIT} proves end to end.
 */
class BillingCheckoutCircuitBreakerTest {

    private static final String PROVIDER = "paytr";

    private final SteppingClock clock = new SteppingClock();
    private final BillingCheckoutCircuitBreaker breaker = new BillingCheckoutCircuitBreaker(clock);

    @Test
    @DisplayName("one failure keeps the circuit closed; the second consecutive one opens it")
    void opensAtTheThresholdOnly() {
        breaker.recordFailure(PROVIDER);
        assertThat(breaker.isOpen(PROVIDER)).isFalse();
        breaker.recordFailure(PROVIDER);
        assertThat(breaker.isOpen(PROVIDER)).isTrue();
    }

    @Test
    @DisplayName("a success between failures resets the count — consecutive means consecutive")
    void successResetsTheCount() {
        breaker.recordFailure(PROVIDER);
        breaker.recordSuccess(PROVIDER);
        breaker.recordFailure(PROVIDER);
        assertThat(breaker.isOpen(PROVIDER)).isFalse();
    }

    @Test
    @DisplayName("the circuit closes when the cool-down passes, and the count starts fresh")
    void coolDownExpiresAndStateResets() {
        breaker.recordFailure(PROVIDER);
        breaker.recordFailure(PROVIDER);
        assertThat(breaker.isOpen(PROVIDER)).isTrue();

        clock.advance(BillingCheckoutCircuitBreaker.COOL_DOWN.plusSeconds(1));
        assertThat(breaker.isOpen(PROVIDER)).isFalse();

        // After expiry the episode is forgotten: ONE new failure must not re-open the circuit.
        breaker.recordFailure(PROVIDER);
        assertThat(breaker.isOpen(PROVIDER)).isFalse();
        breaker.recordFailure(PROVIDER);
        assertThat(breaker.isOpen(PROVIDER)).isTrue();
    }

    @Test
    @DisplayName("providers do not share state")
    void perProviderState() {
        breaker.recordFailure(PROVIDER);
        breaker.recordFailure(PROVIDER);
        assertThat(breaker.isOpen(PROVIDER)).isTrue();
        assertThat(breaker.isOpen("iyzico")).isFalse();
    }

    /** A fixed instant the test moves by hand — deterministic, no waiting. */
    private static final class SteppingClock extends Clock {

        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration amount) {
            now = now.plus(amount);
        }

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
            return now;
        }
    }
}
