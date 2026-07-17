package com.mycompanyname.zero.identity.repo;

import com.mycompanyname.zero.identity.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    void deleteByUserId(Long userId);

    @Modifying
    @Query("update RefreshToken rt set rt.revoked = true where rt.userId = :userId and rt.revoked = false")
    int revokeAllByUserId(@Param("userId") Long userId);

    /**
     * Atomic, conditional revoke used for refresh rotation: only one concurrent caller can flip
     * {@code revoked} from false to true. Returns 0 when the token was already revoked (race lost).
     */
    @Modifying
    @Query("update RefreshToken t set t.revoked = true where t.id = :id and t.revoked = false")
    int revokeIfActive(@Param("id") Long id);
}
