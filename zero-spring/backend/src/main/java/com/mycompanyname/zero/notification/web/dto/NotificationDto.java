package com.mycompanyname.zero.notification.web.dto;

import com.mycompanyname.zero.notification.NotificationLevel;

import java.time.Instant;

/**
 * Read model for a single inbox notification returned by {@code NotificationController}.
 */
public record NotificationDto(
        Long id,
        String notificationName,
        NotificationLevel level,
        String title,
        String body,
        String data,
        boolean isRead,
        Instant createdAt) {
}
