package com.mycompanyname.zero.identity.repo;

import com.mycompanyname.zero.identity.domain.TwoFactorChallenge;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TwoFactorChallengeRepository extends JpaRepository<TwoFactorChallenge, Long> {

    /** Plain read (no lock). For callers that only inspect the row, never mutate it. */
    Optional<TwoFactorChallenge> findByTokenHash(String tokenHash);

    /**
     * The lookup {@code verifyTwoFactor} uses: a {@code SELECT ... FOR UPDATE} that serialises the
     * whole verify per challenge. Concurrent redemptions of the same challenge queue on this row lock,
     * so the second sees the consumed/decremented state the first committed — closing both the
     * attempts-counter lost update and the challenge double-spend (stack-review Finding 2). Must be
     * called inside a transaction. Mirrors the atomicity {@code RefreshTokenRepository.revokeIfActive}
     * gives refresh rotation.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from TwoFactorChallenge c where c.tokenHash = :tokenHash")
    Optional<TwoFactorChallenge> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);
}
