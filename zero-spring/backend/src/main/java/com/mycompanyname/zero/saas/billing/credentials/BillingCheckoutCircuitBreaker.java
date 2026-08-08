package com.mycompanyname.zero.saas.billing.credentials;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * A deliberately small per-provider circuit breaker for CHECKOUT INITIATION only (ADR-0020).
 * {@value #FAILURE_THRESHOLD} consecutive transport-class initiation failures open the circuit for
 * {@link #COOL_DOWN}; while open, the failover loop SKIPS the provider instead of spending a
 * timeout on it per checkout. Hand-rolled on purpose — the recorded alternative (resilience4j) was
 * rejected in ADR-0020: one map, two rules, and a {@link Clock} the tests can move beat a new
 * dependency whose configuration surface exceeds this entire class.
 *
 * <p><b>What counts as a failure is the FAILOVER condition, not any exception</b>: only the
 * transport-class errors {@code BillingCheckoutService#isTransportFailure} recognises are recorded.
 * A 4xx refusal means the provider IS answering — tripping the breaker on it would take a healthy
 * provider out of rotation over our own bad request.
 *
 * <p><b>In-memory and per-node, deliberately.</b> The state must survive the checkout
 * TRANSACTION's rollback (a failed initiation rolls the payment row back, and a breaker stored in
 * that transaction would forget the very failure it exists to remember), so it cannot live in the
 * database transactionally; and per-node is the correct scope anyway — a connectivity failure is a
 * fact about THIS node's path to the provider. After the cool-down the state resets fully: the next
 * failure starts a fresh count (half-open probing was considered and rejected as over-fitting for
 * a surface with human-driven traffic).
 *
 * <p>The breaker never REFUSES a checkout on its own: {@code BillingCheckoutService} attempts
 * skipped providers anyway when every candidate is open — the breaker optimises latency, it is not
 * an authority on availability.
 */
@Component
@RequiredArgsConstructor
public class BillingCheckoutCircuitBreaker {

    /** Consecutive transport failures that open the circuit. */
    static final int FAILURE_THRESHOLD = 2;

    /** How long an opened circuit keeps the provider out of the failover rotation. */
    static final Duration COOL_DOWN = Duration.ofSeconds(60);

    private final Clock clock;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    /** Whether the failover loop should skip this provider right now. */
    public boolean isOpen(String providerId) {
        State state = states.get(providerId);
        if (state == null || state.openUntil == null) {
            return false;
        }
        if (clock.instant().isBefore(state.openUntil)) {
            return true;
        }
        // Cool-down over: forget the episode entirely so the next failure starts a fresh count.
        states.remove(providerId, state);
        return false;
    }

    /** Records one transport-class initiation failure; opens the circuit at the threshold. */
    public void recordFailure(String providerId) {
        states.compute(providerId, (id, current) -> {
            int failures = (current == null ? 0 : current.consecutiveFailures) + 1;
            Instant openUntil = failures >= FAILURE_THRESHOLD ? clock.instant().plus(COOL_DOWN) : null;
            return new State(failures, openUntil);
        });
    }

    /** A successful initiation closes everything — consecutive means consecutive. */
    public void recordSuccess(String providerId) {
        states.remove(providerId);
    }

    private record State(int consecutiveFailures, Instant openUntil) {
    }
}
