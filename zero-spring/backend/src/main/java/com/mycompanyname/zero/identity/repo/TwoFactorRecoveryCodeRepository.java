package com.mycompanyname.zero.identity.repo;

import com.mycompanyname.zero.identity.domain.TwoFactorRecoveryCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface TwoFactorRecoveryCodeRepository extends JpaRepository<TwoFactorRecoveryCode, Long> {

    List<TwoFactorRecoveryCode> findByUserIdAndConsumedAtIsNull(Long userId);

    void deleteByUserId(Long userId);

    /**
     * Atomic, conditional consume — the recovery-code analogue of
     * {@code RefreshTokenRepository.revokeIfActive}. Only the caller that flips {@code consumed_at}
     * from null wins the code; a concurrent redemption updates 0 rows and must not authenticate. The
     * {@code where consumed_at is null} guard plus the row lock is what blocks the double-spend two
     * threads reading the same unconsumed row would otherwise cause (stack-review Finding 2).
     */
    @Modifying
    @Query("update TwoFactorRecoveryCode c set c.consumedAt = :now where c.id = :id and c.consumedAt is null")
    int consumeIfUnconsumed(@Param("id") Long id, @Param("now") Instant now);
}
