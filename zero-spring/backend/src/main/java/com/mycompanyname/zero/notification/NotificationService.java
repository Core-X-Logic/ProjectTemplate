package com.mycompanyname.zero.notification;

import com.mycompanyname.zero.notification.domain.UserNotification;
import com.mycompanyname.zero.notification.domain.UserNotificationRepository;
import com.mycompanyname.zero.shared.domain.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * In-app notification inbox API. Pure application service: it operates only on explicit
 * {@code userId}/{@code tenantId} arguments and never reads the security context, so it carries no
 * dependency on the identity module (which would create a cycle — identity already depends on this
 * module). The web adapter ({@code NotificationController}) is responsible for deriving the caller
 * identity from the JWT.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class NotificationService {

    private final UserNotificationRepository repository;

    public void publish(Long userId, Long tenantId, String name, NotificationLevel level,
                        String title, String body, String data) {
        UserNotification notification = new UserNotification();
        notification.setUserId(userId);
        notification.setTenantId(tenantId);
        notification.setNotificationName(name);
        notification.setLevel(level == null ? NotificationLevel.INFO : level);
        notification.setTitle(title);
        notification.setBody(body);
        notification.setData(data);
        repository.save(notification);
    }

    @Transactional(readOnly = true)
    public Page<UserNotification> list(Long userId, Pageable pageable) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return repository.countByUserIdAndIsReadFalse(userId);
    }

    public void markRead(Long id, Long userId) {
        int updated = repository.markRead(id, userId, Instant.now());
        if (updated == 0) {
            // 0 rows: either the notification does not exist (NOT_FOUND) or it belongs to another
            // user (FORBIDDEN). Distinguish so we do not leak existence across users.
            if (repository.existsById(id)) {
                throw DomainException.forbidden("Notification " + id + " does not belong to the current user");
            }
            throw DomainException.notFound("Notification not found: " + id);
        }
    }

    public void markAllRead(Long userId) {
        repository.markAllRead(userId, Instant.now());
    }
}
