package com.mycompanyname.zero.saas.subscription;

import com.mycompanyname.zero.saas.api.SubscriptionGuard;
import com.mycompanyname.zero.tenancy.TenantAccessCheck;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Wires the subscription validity gate into {@code TenantResolverFilter} (F5-ARCHITECTURE §7.1).
 *
 * <p>The arrow points {@code saas -> tenancy}: {@code tenancy} owns {@link TenantAccessCheck} and
 * stays a leaf module, which is what keeps the "no {@code tenancy -> saas} dependency" rule of
 * CONTRACT-phase5 intact while still putting the check where the architecture asks for it.
 *
 * <p>The exemption list is the reason an expired tenant is locked out but not locked <em>in</em>:
 * it can still authenticate, read its own subscription and reach the pages that let it recover
 * (F5-R7). Everything else answers 403 {@code SUBSCRIPTION_INVALID}.
 */
@Component
public class SubscriptionAccessCheck implements TenantAccessCheck {

    /**
     * Paths an expired/unpaid tenant may still reach. Authentication and account recovery must stay
     * open (otherwise nobody could log in to fix the situation), as must the tenant's own
     * subscription view and the infrastructure endpoints.
     */
    static final List<String> DEFAULT_EXEMPT_PATHS = List.of(
            "/api/auth/**",
            "/api/account/**",
            "/api/localization/**",
            "/api/subscriptions/me",
            "/actuator/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/error");

    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final SubscriptionGuard subscriptionGuard;
    private final List<String> exemptPaths;

    public SubscriptionAccessCheck(SubscriptionGuard subscriptionGuard,
                                   @Value("${zero.saas.subscription-gate.exempt-paths:}") String exemptPaths) {
        this.subscriptionGuard = subscriptionGuard;
        this.exemptPaths = parse(exemptPaths);
    }

    @Override
    public Optional<String> denyReason(Long tenantId, String requestPath) {
        if (tenantId == null || isExempt(requestPath)) {
            return Optional.empty();
        }
        if (subscriptionGuard.isTenantSubscriptionValid(tenantId)) {
            return Optional.empty();
        }
        return Optional.of("This tenant's subscription is not active. "
                + "Open the subscription page to renew it before using the application.");
    }

    /** Visible for the exemption test: the effective list after configuration is applied. */
    List<String> exemptPaths() {
        return exemptPaths;
    }

    private boolean isExempt(String requestPath) {
        if (requestPath == null || requestPath.isBlank()) {
            return true;
        }
        for (String pattern : exemptPaths) {
            if (pathMatcher.match(pattern, requestPath)) {
                return true;
            }
        }
        return false;
    }

    /** A blank property keeps the built-in list; anything else replaces it wholesale. */
    private static List<String> parse(String configured) {
        if (configured == null || configured.isBlank()) {
            return DEFAULT_EXEMPT_PATHS;
        }
        List<String> parsed = Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
        return parsed.isEmpty() ? DEFAULT_EXEMPT_PATHS : parsed;
    }
}
