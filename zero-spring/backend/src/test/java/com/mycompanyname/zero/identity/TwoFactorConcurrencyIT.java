package com.mycompanyname.zero.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.identity.domain.TwoFactorChallenge;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency safety of the 2FA verify path (stack-review Finding 2). The single-use guarantees must
 * hold under parallel requests, not just sequentially — refresh-token rotation next door already does
 * this with a guarded UPDATE ({@code RefreshTokenRepository.revokeIfActive}); the 2FA consume/decrement
 * had been left as a check-then-act (TOCTOU).
 */
class TwoFactorConcurrencyIT extends AbstractTwoFactorIT {

    private static final String PASSWORD = "Concurrent-2FA-1!";

    /**
     * A recovery code is single-use even when two requests redeem it at the same instant. Two DIFFERENT
     * challenges are used so the per-challenge lock cannot be what serialises them — only the guarded
     * consume on the recovery-code row can. Without it, both threads read the row as unconsumed, both
     * BCrypt-match, both mint a token: one code, two sessions.
     */
    @Test
    void aRecoveryCodeCannotBeDoubleSpentUnderConcurrency() throws Exception {
        TwoFactorUser user = createHostUserWithTwoFactor(PASSWORD, 2);
        String recoveryCode = user.recoveryCodes().get(0);
        String challengeA = loginForChallenge(user);
        String challengeB = loginForChallenge(user);

        List<Integer> statuses = raceTwo(
                () -> verify(null, challengeA, recoveryCode).getStatusCode().value(),
                () -> verify(null, challengeB, recoveryCode).getStatusCode().value());

        long successes = statuses.stream().filter(s -> s == HttpStatus.OK.value()).count();
        assertThat(successes)
                .as("exactly one of two concurrent redemptions of the SAME recovery code may succeed "
                        + "(no double-spend); statuses were %s", statuses)
                .isEqualTo(1);
    }

    /**
     * Parallel wrong guesses against ONE challenge must each cost an attempt: K concurrent failures
     * decrement {@code attempts_remaining} by exactly K. A lost update (all read 5, all write 4) would
     * dilute the per-challenge cap to a single decrement, letting an attacker spend far more than
     * {@code max-attempts} guesses.
     */
    @Test
    void parallelWrongGuessesDecrementTheChallengeByTheTrueCount() throws Exception {
        TwoFactorUser user = createHostUserWithTwoFactor(PASSWORD, 1);
        String challenge = loginForChallenge(user);
        int parallel = 4; // below max-attempts (5) so the count, not the cap, is what is measured
        String wrong = wrongTotp(user.secret());

        ExecutorService pool = Executors.newFixedThreadPool(parallel);
        CyclicBarrier barrier = new CyclicBarrier(parallel);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < parallel; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await();
                    return verify(null, challenge, wrong).getStatusCode().value();
                }));
            }
            for (Future<Integer> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        TwoFactorChallenge stored = challengeRepository.findByTokenHash(sha256Hex(challenge)).orElseThrow();
        assertThat(stored.getAttemptsRemaining())
                .as("%d concurrent wrong guesses must decrement attempts_remaining by exactly %d "
                        + "(no lost update)", parallel, parallel)
                .isEqualTo(twoFactorProperties.getMaxAttempts() - parallel);
    }

    // --- helpers ---------------------------------------------------------------------------

    private List<Integer> raceTwo(Callable<Integer> first, Callable<Integer> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Future<Integer> a = pool.submit(() -> {
                barrier.await();
                return first.call();
            });
            Future<Integer> b = pool.submit(() -> {
                barrier.await();
                return second.call();
            });
            return List.of(a.get(), b.get());
        } finally {
            pool.shutdownNow();
        }
    }

    private String loginForChallenge(TwoFactorUser user) {
        ResponseEntity<JsonNode> response = login(null, user.username(), user.password());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = response.getBody().path("twoFactor").path("challengeToken").asText();
        assertThat(token).isNotBlank();
        return token;
    }
}
