package com.mycompanyname.zero.identity.invitation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserInvitationRepository extends JpaRepository<UserInvitation, Long> {

    /**
     * Token lookup for the anonymous accept flow. The caller must have disabled the Hibernate
     * tenant/host filters first (the anonymous request carries no tenant, so the aspect would
     * otherwise pin this query to {@code tenant_id is null} and hide every tenant invitation).
     */
    Optional<UserInvitation> findByTokenHash(String tokenHash);

    Page<UserInvitation> findAllByTenantId(Long tenantId, Pageable pageable);

    Page<UserInvitation> findAllByTenantIdIsNull(Pageable pageable);

    boolean existsByTenantIdAndEmailIgnoreCaseAndStatus(Long tenantId, String email, InvitationStatus status);

    boolean existsByTenantIdIsNullAndEmailIgnoreCaseAndStatus(String email, InvitationStatus status);

    boolean existsByTenantIdAndUsernameIgnoreCaseAndStatus(Long tenantId, String username, InvitationStatus status);

    boolean existsByTenantIdIsNullAndUsernameIgnoreCaseAndStatus(String username, InvitationStatus status);

    /**
     * Single-use state transition — the {@code revokeIfActive}/{@code consumeIfUnconsumed} pattern.
     * Exactly one of any number of concurrent callers observes {@code affected rows == 1}; the
     * losers see 0 and must refuse. The status check lives INSIDE the statement, never in a prior
     * read, because the prior read is what a race invalidates.
     *
     * <p>The states are bound as PARAMETERS, not written as HQL enum literals, and that is a fix,
     * not a style choice: the literal form ({@code i.status = com...InvitationStatus.PENDING})
     * rendered a value the {@code @Enumerated(STRING)} column never matches, so the guarded update
     * touched 0 rows and every accept answered "invalid invitation" — measured red in
     * {@code InvitationFlowIT} before the parameter form went in (in the project this flow was
     * ported from). A parameter binds through the attribute's own converter and cannot diverge
     * from what reads wrote.
     */
    @Modifying
    @Query("update UserInvitation i set i.status = :next where i.id = :id and i.status = :expected")
    int transition(@Param("id") Long id,
                   @Param("expected") InvitationStatus expected,
                   @Param("next") InvitationStatus next);

    /**
     * Records which account an accepted invitation produced. Separate from {@link #transition}
     * because the user row (and its id) only exists after the claim succeeded; a bulk update is used
     * rather than mutating the loaded entity, whose stale PENDING status a full-column UPDATE would
     * silently write back over the claim.
     */
    @Modifying
    @Query("update UserInvitation i set i.acceptedUserId = :userId where i.id = :id")
    int linkAcceptedUser(@Param("id") Long id, @Param("userId") Long userId);
}
