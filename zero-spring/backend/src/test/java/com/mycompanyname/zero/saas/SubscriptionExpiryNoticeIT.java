package com.mycompanyname.zero.saas;

import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.identity.domain.User;
import com.mycompanyname.zero.identity.repo.UserRepository;
import com.mycompanyname.zero.notification.domain.UserNotification;
import com.mycompanyname.zero.notification.domain.UserNotificationRepository;
import com.mycompanyname.zero.saas.subscription.SubscriptionLifecycleProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pre-expiry notice (ASP.NET Zero parity: the expire-email notifier — but WINDOWED and idempotent,
 * where the source matched the exact day and skipped tenants whenever a run was missed).
 *
 * <p>Clock-driven like {@link SubscriptionLifecycleIT}: the production window queries, the ledger
 * check and the notification bridge all run exactly as in production.
 */
@Import(MutableClockConfig.class)
class SubscriptionExpiryNoticeIT extends AbstractSaasIT {

    private static final String NOTICE_REASON = "EXPIRY_NOTICE";
    private static final String NOTICE_NAME = "saas.subscription.expiry_notice";

    @Autowired
    private MutableClock clock;

    @Autowired
    private SubscriptionLifecycleProcessor lifecycleProcessor;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserNotificationRepository userNotificationRepository;

    @AfterEach
    void restoreRealTime() {
        clock.reset();
    }

    @Test
    void aSubscriptionInsideTheNoticeWindowIsWarnedExactlyOnce() {
        long tenantId = ensureTenant("saas-notice-window");
        long editionId = createPaidEdition("notice-window", "20.00", null, 0, 0);
        assignEditionOk(tenantId, editionId, "MONTHLY", false);
        post("/api/subscriptions/" + tenantId + "/activate");

        // Not yet inside the 7-day window (a month is at least 28 days; at day 10 there are 18+ left).
        clock.advance(Duration.ofDays(10));
        lifecycleProcessor.processDueSubscriptions();
        assertThat(noticeCount(tenantId))
                .as("outside the notice window nothing must be recorded")
                .isZero();

        // Day 26: 2-5 days remain whatever the month length — inside the window, but NOT expired.
        clock.advance(Duration.ofDays(16));
        lifecycleProcessor.processDueSubscriptions();
        assertThat(statusOf(tenantId)).isEqualTo("ACTIVE");
        assertThat(noticeCount(tenantId))
                .as("inside the window exactly one notice event must be recorded")
                .isEqualTo(1);
        assertThat(noticeNotifications(tenantId))
                .as("the notice must reach the tenant admin as an in-app notification")
                .hasSize(1);

        // An hourly job re-runs: the ledger must swallow the repeat (no duplicate warning).
        clock.advance(Duration.ofHours(1));
        lifecycleProcessor.processDueSubscriptions();
        assertThat(noticeCount(tenantId))
                .as("a re-run inside the same window must NOT duplicate the notice")
                .isEqualTo(1);
        assertThat(noticeNotifications(tenantId)).hasSize(1);
    }

    private long noticeCount(long tenantId) {
        List<JsonNode> events = new ArrayList<>();
        getSubscription(tenantId).path("events").forEach(events::add);
        return events.stream()
                .filter(event -> NOTICE_REASON.equals(event.path("reason").asText()))
                .count();
    }

    private List<UserNotification> noticeNotifications(long tenantId) {
        // inTenantDatabase: see SaasNotificationBridgeIT — `users` is policed since V12 and
        // `user_notifications` since V13; this read is issued from the test thread, which announces
        // no context of its own, so unwrapped it would answer 0 rows and hide a delivered notice.
        return inTenantDatabase(tenantId, () -> {
            User admin = userRepository
                    .findByTenantIdAndUsernameIgnoreCase(tenantId, "admin")
                    .orElseThrow(() -> new AssertionError("bootstrap admin missing for tenant " + tenantId));
            return userNotificationRepository
                    .findByUserIdOrderByCreatedAtDesc(admin.getId(), PageRequest.of(0, 50))
                    .getContent().stream()
                    .filter(notification -> NOTICE_NAME.equals(notification.getNotificationName()))
                    .toList();
        });
    }

    private String statusOf(long tenantId) {
        return subscriptionOf(getSubscription(tenantId)).path("status").asText();
    }
}
