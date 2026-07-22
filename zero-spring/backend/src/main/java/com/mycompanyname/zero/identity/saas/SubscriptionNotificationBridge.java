package com.mycompanyname.zero.identity.saas;

import com.mycompanyname.zero.identity.domain.User;
import com.mycompanyname.zero.identity.repo.UserRepository;
import com.mycompanyname.zero.notification.NotificationLevel;
import com.mycompanyname.zero.notification.NotificationService;
import com.mycompanyname.zero.saas.api.SubscriptionChanged;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Session;
import org.springframework.context.MessageSource;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns SaaS lifecycle events into in-app notifications for the affected tenant's {@code Admin}
 * role members (ASP.NET Zero parity: the subscription-expiring notifier — here in-app, windowed
 * and idempotent instead of exact-day emails).
 *
 * <p>Lives in <b>identity</b>, not saas: only identity may see both the user store and the
 * notification module, and it already depends on {@code saas :: api} — the reverse edge
 * (saas → identity) would close a cycle. Same pattern as the welcome notification.
 *
 * <p>Synchronous listener in the SAME transaction as the subscription change (MANDATORY):
 * the notification commits or rolls back with the transition, so there is no "status changed but
 * nobody was told" half-state, and a listener failure aborts nothing silently.
 *
 * <p>Only OPERATIONALLY relevant reasons notify. Provisioning/assignment/edition-change entries
 * stay in the {@code subscription_events} audit trail without producing notification noise.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionNotificationBridge {

    static final String ADMIN_ROLE_NAME = "Admin";
    static final String NOTIFICATION_NAME_PREFIX = "saas.subscription.";

    /** reason → (message key suffix, level). */
    private static final Map<String, Notice> NOTICES = Map.of(
            "ACTIVATED", new Notice("Activated", NotificationLevel.SUCCESS),
            "CANCELLED", new Notice("Cancelled", NotificationLevel.WARNING),
            "TRIAL_ENDED", new Notice("Expired", NotificationLevel.ERROR),
            "PERIOD_ENDED", new Notice("PeriodEnded", NotificationLevel.WARNING),
            "GRACE_ENDED", new Notice("Expired", NotificationLevel.ERROR),
            "DOWNGRADED", new Notice("Downgraded", NotificationLevel.WARNING),
            SubscriptionChanged.REASON_EXPIRY_NOTICE, new Notice("ExpiringSoon", NotificationLevel.WARNING));

    private record Notice(String keySuffix, NotificationLevel level) {
    }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneOffset.UTC);

    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final MessageSource messageSource;

    @PersistenceContext
    private EntityManager entityManager;

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onSubscriptionChanged(SubscriptionChanged event) {
        Notice notice = NOTICES.get(event.reason());
        if (notice == null || event.tenantId() == null) {
            return;
        }
        // The tenancy aspect has enabled the host/tenant Hibernate filter on this Session (the
        // caller is usually a host request or the lifecycle job), which would hide the TARGET
        // tenant's users. The recipient lookup is a deliberate cross-tenant read that is already
        // explicitly tenant-scoped by its parameters (the primary defense), so the second-line
        // filter is suspended for JUST this query and restored right after — the rest of the
        // transaction keeps its isolation.
        List<User> admins = withoutTenantFilters(() -> userRepository
                .findByTenantIdAndActiveTrueAndRoles_NameIgnoreCase(event.tenantId(), ADMIN_ROLE_NAME));
        if (admins.isEmpty()) {
            log.warn("Subscription event {} for tenant {} has no active Admin recipients",
                    event.reason(), event.tenantId());
            return;
        }
        String edition = event.editionDisplayName() == null ? "-" : event.editionDisplayName();
        String deadline = formatDeadline(event);
        String title = message("Saas.Subscription." + notice.keySuffix() + ".Title", edition, deadline);
        String body = message("Saas.Subscription." + notice.keySuffix() + ".Body", edition, deadline);
        for (User admin : admins) {
            notificationService.publish(admin.getId(), event.tenantId(),
                    NOTIFICATION_NAME_PREFIX + event.reason().toLowerCase(Locale.ROOT),
                    notice.level(), title, body, null);
        }
        log.debug("Notified {} tenant admin(s) of {} for tenant {}",
                admins.size(), event.reason(), event.tenantId());
    }

    /**
     * Runs {@code query} with the tenancy filters suspended, then restores exactly the filter that
     * was enabled before (tenant, host or none) so the surrounding transaction is unaffected.
     */
    private <T> T withoutTenantFilters(Supplier<T> query) {
        Session session = entityManager.unwrap(Session.class);
        boolean tenantEnabled = session.getEnabledFilter("tenantFilter") != null;
        boolean hostEnabled = session.getEnabledFilter("hostFilter") != null;
        session.disableFilter("tenantFilter");
        session.disableFilter("hostFilter");
        try {
            return query.get();
        } finally {
            // Restore what the tenancy aspect had enabled. The tenant filter's parameter is
            // re-read from TenantContext — the same source the aspect bound it from, on the
            // same thread, so the restored state is identical.
            if (tenantEnabled && com.mycompanyname.zero.shared.tenant.TenantContext.getTenantId() != null) {
                session.enableFilter("tenantFilter")
                        .setParameter("tenantId", com.mycompanyname.zero.shared.tenant.TenantContext.getTenantId());
            }
            if (hostEnabled) {
                session.enableFilter("hostFilter");
            }
        }
    }

    /** Trial end wins when trialing; otherwise the billed period end; "-" when neither exists. */
    private String formatDeadline(SubscriptionChanged event) {
        Instant deadline = event.trialEndAt() != null ? event.trialEndAt() : event.periodEndAt();
        return deadline == null ? "-" : DATE.format(deadline);
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, key, Locale.ENGLISH);
    }
}
