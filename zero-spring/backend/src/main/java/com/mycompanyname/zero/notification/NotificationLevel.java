package com.mycompanyname.zero.notification;

/**
 * Severity/intent of an in-app notification. Part of the notification module's public API (base
 * package) so callers such as the identity module can classify the notifications they publish.
 * Persisted as its {@code name()} in the {@code user_notifications.level} column (varchar(16)).
 */
public enum NotificationLevel {
    INFO,
    SUCCESS,
    WARNING,
    ERROR
}
