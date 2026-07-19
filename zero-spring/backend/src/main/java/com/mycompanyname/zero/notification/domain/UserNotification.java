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
import org.hibernate.annotations.Filter;

import java.time.Instant;

/**
 * A single in-app notification delivered to a user's inbox. The table maps one-to-one to the
 * {@code user_notifications} DDL in {@code V3__notifications.sql}; schema validation
 * (ddl-auto=validate) enforces that alignment.
 *
 * <p><b>{@code tenantFilter} but deliberately NOT {@code hostFilter}.</b> This class used to claim
 * that {@code userId} (a global {@code users.id}) being the isolation key made a tenant filter
 * unnecessary. That is true of every read path written SO FAR and false as a guarantee: every query
 * in {@code UserNotificationRepository} keys on {@code userId} alone, so nothing but the filter
 * stands between a tenant session and a row tagged for another tenant — which is why
 * {@code TenantFilterCoverageIT} fails on the pre-filter code.
 *
 * <p>{@code hostFilter} ({@code tenant_id is null}) is omitted for a reason specific to this
 * entity: {@code NotificationService.publish(userId, tenantId, ...)} takes recipient and tenant tag
 * independently, so a host recipient may legitimately hold a row tagged with the tenant the alert
 * is ABOUT ("tenant acme's subscription expired"). Filtering host down to {@code tenant_id is null}
 * would hide such a notification from its own recipient — a silent delivery failure — while buying
 * nothing, since {@code userId} already isolates users completely.
 */
@Entity
@Table(name = "user_notifications")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
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
