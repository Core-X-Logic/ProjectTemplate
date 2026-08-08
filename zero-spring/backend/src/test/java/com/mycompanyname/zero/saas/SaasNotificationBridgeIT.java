package com.mycompanyname.zero.saas;

import com.mycompanyname.zero.identity.domain.User;
import com.mycompanyname.zero.identity.repo.UserRepository;
import com.mycompanyname.zero.notification.domain.UserNotification;
import com.mycompanyname.zero.notification.domain.UserNotificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SaaS event → in-app notification bridge (ASP.NET Zero parity: subscription lifecycle events must
 * be visible to the affected tenant, not just recorded in the event trail).
 *
 * <p>Recipients are read straight from the repositories (tests may cross module lines; production
 * code may not): a fresh tenant's bootstrap {@code admin} holds the tenant Admin role, so it is the
 * exact recipient set the bridge resolves.
 */
class SaasNotificationBridgeIT extends AbstractSaasIT {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserNotificationRepository userNotificationRepository;

    @Test
    void activationAndCancellationNotifyTheTenantAdmin() {
        long tenantId = ensureTenant("saas-notify-activate");
        long editionId = createPaidEdition("notify-activate", "15.00", null, 0, 0);
        assignEditionOk(tenantId, editionId, "MONTHLY", false);

        post("/api/subscriptions/" + tenantId + "/activate");
        List<String> afterActivate = saasNotificationNames(tenantId);
        assertThat(afterActivate)
                .as("activating a subscription must notify the tenant's Admin members")
                .contains("saas.subscription.activated");

        post("/api/subscriptions/" + tenantId + "/cancel");
        assertThat(saasNotificationNames(tenantId))
                .as("cancelling must notify too")
                .contains("saas.subscription.cancelled");
    }

    @Test
    void provisioningAloneDoesNotSpamNotifications() {
        // Creating a tenant provisions a default subscription (reason PROVISIONED). That is an
        // audit-trail fact, not an operational alert — the bridge must stay silent.
        long tenantId = ensureTenant("saas-notify-quiet");

        assertThat(saasNotificationNames(tenantId))
                .as("provisioning/assignment entries must not produce notification noise")
                .isEmpty();
    }

    /** All saas.subscription.* notification names delivered to the tenant's bootstrap admin. */
    private List<String> saasNotificationNames(long tenantId) {
        // inTenantDatabase: `users` is policed since V12 and `user_notifications` since V13; a test
        // thread crosses no @Service boundary, so an unwrapped read answers 0 rows — here that false
        // zero would even AGREE with the "no noise" assertions, certifying nothing.
        return inTenantDatabase(tenantId, () -> {
            User admin = userRepository
                    .findByTenantIdAndUsernameIgnoreCase(tenantId, "admin")
                    .orElseThrow(() -> new AssertionError("bootstrap admin missing for tenant " + tenantId));
            return userNotificationRepository
                    .findByUserIdOrderByCreatedAtDesc(admin.getId(), PageRequest.of(0, 50))
                    .getContent().stream()
                    .map(UserNotification::getNotificationName)
                    .filter(name -> name != null && name.startsWith("saas.subscription."))
                    .toList();
        });
    }
}
