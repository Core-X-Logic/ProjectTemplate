package com.mycompanyname.zero.notification.web;

import com.mycompanyname.zero.notification.NotificationService;
import com.mycompanyname.zero.notification.domain.UserNotification;
import com.mycompanyname.zero.notification.web.dto.NotificationDto;
import com.mycompanyname.zero.shared.domain.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Inbox API for in-app notifications. Web adapter that keeps {@link NotificationService} pure by
 * deriving the caller's {@code userId} straight from the authenticated JWT (Spring Security, not the
 * identity module — this mirrors {@code SettingController} and avoids a notification -&gt; identity
 * dependency). Every endpoint is user-scoped; no extra permission beyond authentication is required.
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public Page<NotificationDto> inbox(@AuthenticationPrincipal Jwt jwt, Pageable pageable) {
        Long userId = requireUserId(jwt);
        return notificationService.list(userId, pageable).map(NotificationController::toDto);
    }

    @GetMapping("/unread-count")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Long> unreadCount(@AuthenticationPrincipal Jwt jwt) {
        Long userId = requireUserId(jwt);
        return Map.of("count", notificationService.unreadCount(userId));
    }

    @PutMapping("/{id}/read")
    @PreAuthorize("isAuthenticated()")
    public void markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        Long userId = requireUserId(jwt);
        notificationService.markRead(id, userId);
    }

    @PutMapping("/read-all")
    @PreAuthorize("isAuthenticated()")
    public void markAllRead(@AuthenticationPrincipal Jwt jwt) {
        Long userId = requireUserId(jwt);
        notificationService.markAllRead(userId);
    }

    private static NotificationDto toDto(UserNotification n) {
        return new NotificationDto(
                n.getId(),
                n.getNotificationName(),
                n.getLevel(),
                n.getTitle(),
                n.getBody(),
                n.getData(),
                n.isRead(),
                n.getCreatedAt());
    }

    private Long requireUserId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw DomainException.unauthorized("Authentication required");
        }
        return Long.valueOf(jwt.getSubject());
    }
}
