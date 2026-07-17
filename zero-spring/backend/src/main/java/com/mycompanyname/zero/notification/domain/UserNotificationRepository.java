package com.mycompanyname.zero.notification.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface UserNotificationRepository extends JpaRepository<UserNotification, Long> {

    Page<UserNotification> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /**
     * Explicit JPQL (rather than derived from the method name) so the boolean {@code read} property
     * — persisted as {@code is_read} — is resolved unambiguously regardless of accessor naming.
     */
    @Query("select count(n) from UserNotification n where n.userId = :userId and n.read = false")
    long countByUserIdAndIsReadFalse(@Param("userId") Long userId);

    /**
     * Conditional, ownership-scoped mark-read. Returns the number of rows affected: 1 when the
     * notification exists and belongs to {@code userId}, 0 otherwise (missing or foreign). The
     * caller uses that to decide between success, NOT_FOUND and FORBIDDEN.
     */
    @Modifying(clearAutomatically = true)
    @Query("update UserNotification n set n.read = true, n.readAt = :now "
            + "where n.id = :id and n.userId = :userId")
    int markRead(@Param("id") Long id, @Param("userId") Long userId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true)
    @Query("update UserNotification n set n.read = true, n.readAt = :now "
            + "where n.userId = :userId and n.read = false")
    int markAllRead(@Param("userId") Long userId, @Param("now") Instant now);
}
