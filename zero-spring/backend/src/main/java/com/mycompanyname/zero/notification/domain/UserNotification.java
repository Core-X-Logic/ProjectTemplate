package com.mycompanyname.zero.notification.domain;

import com.mycompanyname.zero.notification.NotificationLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * A single in-app notification delivered to a user's inbox. Strictly user-scoped: {@code userId}
 * (a global {@code users.id}) is the isolation key, so no Hibernate tenant filter is needed. The
 * table maps one-to-one to the {@code user_notifications} DDL in {@code V3__notifications.sql};
 * schema validation (ddl-auto=validate) enforces that alignment.
 */
@Entity
@Table(name = "user_notifications")
@Getter
@Setter
public class UserNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "notification_name", nullable = false, length = 128)
    private String notificationName;

    @Enumerated(EnumType.STRING)
    @Column(name = "level", nullable = false, length = 16)
    private NotificationLevel level = NotificationLevel.INFO;

    @Column(name = "title", nullable = false, length = 256)
    private String title;

    @Column(name = "body", length = 2000)
    private String body;

    @Column(name = "data", length = 2000)
    private String data;

    @Column(name = "is_read", nullable = false)
    private boolean read = false;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
