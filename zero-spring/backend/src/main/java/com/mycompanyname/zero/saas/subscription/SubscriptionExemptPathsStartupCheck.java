package com.mycompanyname.zero.saas.subscription;

import com.mycompanyname.zero.shared.web.EndpointPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Validates the EFFECTIVE subscription-gate exemption list against the routes this application
 * actually serves, once, at {@link ApplicationReadyEvent}.
 *
 * <p><b>Why this exists.</b> {@code zero.saas.subscription-gate.exempt-paths} is a {@code @Value}
 * override, so the effective set is not statically knowable and no build-time rule can see it.
 * {@code SubscriptionAccessCheck.parse} replaces the built-in list WHOLESALE on any non-blank value —
 * so a single typo'd override does not merely fail to exempt the path the operator meant, it removes
 * {@code /api/auth/**} as well and locks every tenant of an expired subscription out of the login
 * they would need in order to renew it. That is the recovery path being deleted by a spelling
 * mistake, and it must fail at boot rather than at 3am.
 *
 * <p><b>Why startup and not lazy.</b> Same reason as {@code RateLimitFilter.reportBodyFormatInventory}:
 * a check that runs on first use produces its signal only where somebody is already probing. It also
 * follows the precedent {@code JwtSecretValidator} and {@code CorsProperties} set for the
 * "unresolved placeholder binds as a literal string" trap in CLAUDE.md.
 *
 * <p><b>What it deliberately does NOT do.</b> It refuses to boot on an exemption that resolves to
 * nothing, and it says out loud, at WARN, when the effective set is wider than the built-in one and
 * which handlers that widening covers without their consent. It does not BLOCK a widening. The
 * override is an operator escape hatch for a live incident; making it fatal would take that away.
 * The residual — an operator setting {@code /api/**} and disabling the gate entirely with one WARN
 * line — is real and is recorded in the risk register rather than papered over here.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionExemptPathsStartupCheck {

    private final SubscriptionAccessCheck accessCheck;
    private final ObjectProvider<RequestMappingHandlerMapping> handlerMappings;

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @EventListener(ApplicationReadyEvent.class)
    public void validateExemptPathsAgainstLiveMappings() {
        Map<String, HandlerMethod> registered = registeredPatterns();
        if (registered.isEmpty()) {
            throw new IllegalStateException(
                    "No request mappings were registered, so the subscription-gate exemption list "
                            + "cannot be validated against anything. A check that certifies nothing "
                            + "must fail, not pass.");
        }

        List<String> effective = accessCheck.exemptPaths();
        List<String> unresolvable = new ArrayList<>();
        for (String pattern : effective) {
            if (!pattern.startsWith("/api")) {
                // /actuator, /v3/api-docs, /swagger-ui and /error are framework-owned surfaces with
                // no @RestController to resolve against. Claiming to verify them would be a lie.
                continue;
            }
            if (matchingHandlers(pattern, registered).isEmpty()) {
                unresolvable.add(pattern);
            }
        }
        if (!unresolvable.isEmpty()) {
            throw new IllegalStateException(
                    "zero.saas.subscription-gate.exempt-paths names " + unresolvable + ", which "
                            + "match no route this application serves. The property REPLACES the "
                            + "built-in list wholesale, so a typo here does not just fail to exempt "
                            + "one path — it removes /api/auth/** too and locks every tenant with an "
                            + "expired subscription out of the login screen they need in order to "
                            + "renew. Effective list: " + effective + ". Known routes under /api: "
                            + new TreeSet<>(registered.keySet()));
        }

        Set<String> added = new LinkedHashSet<>(effective);
        SubscriptionAccessCheck.DEFAULT_EXEMPT_PATHS.forEach(added::remove);
        if (added.isEmpty() && effective.size() == SubscriptionAccessCheck.DEFAULT_EXEMPT_PATHS.size()) {
            log.info("Subscription gate exemptions are the built-in set ({} entries), all resolving "
                    + "to live routes.", effective.size());
            return;
        }
        for (String widening : added) {
            Set<String> unclaimed = new TreeSet<>();
            matchingHandlers(widening, registered).forEach((pattern, handler) -> {
                if (!claimsSubscriptionExemption(handler)) {
                    unclaimed.add(pattern);
                }
            });
            log.warn("Subscription gate exemption '{}' is NOT part of the built-in set and was added "
                            + "by configuration. It exempts {} route(s) whose handlers do not claim "
                            + "EndpointPolicy.Exposure.SUBSCRIPTION_EXEMPT: {}. An expired tenant can "
                            + "reach these.",
                    widening, unclaimed.size(), unclaimed);
        }
        if (added.isEmpty()) {
            log.warn("Subscription gate exemptions were overridden by configuration and are NARROWER "
                    + "than the built-in set. Effective: {}. Built-in: {}.",
                    effective, SubscriptionAccessCheck.DEFAULT_EXEMPT_PATHS);
        }
    }

    /** Every registered URL pattern under {@code /api}, mapped to one handler that serves it. */
    private Map<String, HandlerMethod> registeredPatterns() {
        Map<String, HandlerMethod> patterns = new LinkedHashMap<>();
        handlerMappings.orderedStream().forEach(mapping ->
                mapping.getHandlerMethods().forEach((info, handler) ->
                        patternValues(info).forEach(pattern -> {
                            if (pattern.startsWith("/api")) {
                                patterns.putIfAbsent(pattern, handler);
                            }
                        })));
        return patterns;
    }

    /**
     * Boot 3 parses mappings with {@code PathPatternParser}, so this condition is the authoritative
     * one. If a deployment ever switches back to {@code AntPathMatcher} it becomes null, the
     * registered set comes back empty, and the caller refuses to boot — loudly wrong rather than
     * quietly certifying nothing.
     */
    private static Set<String> patternValues(RequestMappingInfo info) {
        return info.getPathPatternsCondition() == null
                ? Set.of()
                : info.getPathPatternsCondition().getPatternValues();
    }

    private Map<String, HandlerMethod> matchingHandlers(String exemptPattern,
                                                        Map<String, HandlerMethod> registered) {
        Map<String, HandlerMethod> matched = new LinkedHashMap<>();
        registered.forEach((pattern, handler) -> {
            if (pathMatcher.match(exemptPattern, pattern)) {
                matched.put(pattern, handler);
            }
        });
        return matched;
    }

    private static boolean claimsSubscriptionExemption(HandlerMethod handler) {
        EndpointPolicy policy = handler.getMethodAnnotation(EndpointPolicy.class);
        if (policy == null) {
            return false;
        }
        for (EndpointPolicy.Exposure exposure : policy.value()) {
            if (exposure == EndpointPolicy.Exposure.SUBSCRIPTION_EXEMPT) {
                return true;
            }
        }
        return false;
    }
}
