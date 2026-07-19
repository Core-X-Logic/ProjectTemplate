package com.mycompanyname.zero.saas.subscription;

import com.mycompanyname.zero.AbstractIntegrationIT;
import com.mycompanyname.zero.architecture.EndpointInventory;
import com.mycompanyname.zero.shared.web.EndpointPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The subscription gate's exemption list, bound to the routes it actually exempts.
 *
 * <p>Lives in {@code saas.subscription} because {@code SubscriptionAccessCheck.exemptPaths()} and
 * {@code DEFAULT_EXEMPT_PATHS} are package-private — deliberately, so the exemption set is not part
 * of anyone's API — and because this is the module whose decision is being checked.
 *
 * <p>Three of the five measured R-38A edges are here: {@code /api/auth/**} and {@code /api/account/**}
 * are served by {@code identity}, {@code /api/localization/**} by {@code localization}, and
 * {@code saas} declares {@code allowedDependencies = {"shared", "tenancy", "settings"}} — none of
 * them. Declaring those edges would grant {@code saas} import rights over every type in
 * {@code identity} to describe a coupling that is not a type coupling at all, and would still not go
 * red on a rename. This does.
 */
class SubscriptionExemptPathBindingIT extends AbstractIntegrationIT {

    // Actuator contributes controllerEndpointHandlerMapping, which is also a
    // RequestMappingHandlerMapping; the qualifier picks the MVC one that serves /api.
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private SubscriptionAccessCheck accessCheck;

    private EndpointInventory endpoints;

    @BeforeEach
    void buildInventory() {
        endpoints = new EndpointInventory(handlerMapping);
    }

    /**
     * Reads the EFFECTIVE list, after {@code @Value} resolution, not {@code DEFAULT_EXEMPT_PATHS} —
     * so an in-repository profile override is covered too, and so is the {@code /api/subscriptions/me}
     * suffix, which no static rule can see because it is an ordinary string.
     */
    @Test
    @DisplayName("every effective /api exemption resolves to at least one live route")
    void everyExemptPathResolvesToALiveRoute() {
        assertThat(accessCheck.exemptPaths())
                .describedAs("the exemption list is empty; an expired tenant could not even reach "
                        + "login, and every assertion here would be vacuous")
                .isNotEmpty();

        for (String pattern : accessCheck.exemptPaths()) {
            if (!pattern.startsWith("/api")) {
                continue;
            }
            assertThat(endpoints.apiPatternsCoveredBy(pattern))
                    .describedAs("exemption \"%s\" matches no live route. An expired tenant is being "
                            + "refused a path somebody intended to leave open — and if that path is "
                            + "on the recovery journey, the tenant cannot renew. Live /api routes: %s",
                            pattern, endpoints.apiPatterns())
                    .isNotEmpty();
        }
    }

    /** The shipped configuration must not quietly differ from the list that is code-reviewed. */
    @Test
    @DisplayName("the shipped configuration leaves the built-in exemption list in force")
    void theEffectiveListIsTheBuiltInListUnderTheShippedConfiguration() {
        assertThat(accessCheck.exemptPaths())
                .describedAs("a yml override is replacing the built-in exemptions wholesale. That is "
                        + "legal at runtime, but it must not happen inside this repository without "
                        + "the change being visible here")
                .containsExactlyElementsOf(SubscriptionAccessCheck.DEFAULT_EXEMPT_PATHS);
    }

    /**
     * Claim without grant: a handler believes an expired tenant can reach it and the gate disagrees.
     * Harmless to security, fatal to recovery — this is the direction that locks a paying customer
     * out of the page where they would have paid.
     */
    @Test
    @DisplayName("every SUBSCRIPTION_EXEMPT claim is backed by an exemption entry")
    void everySubscriptionExemptClaimIsGranted() {
        Set<String> exempted = endpoints.apiPatternsCoveredByAny(accessCheck.exemptPaths());
        Set<String> claimed = endpoints.apiPatternsClaiming(EndpointPolicy.Exposure.SUBSCRIPTION_EXEMPT);

        assertThat(claimed)
                .describedAs("no route claims SUBSCRIPTION_EXEMPT — vacuous")
                .isNotEmpty();
        assertThat(exempted)
                .describedAs("routes claiming SUBSCRIPTION_EXEMPT that no exemption entry covers. An "
                        + "expired tenant will be refused these with SUBSCRIPTION_INVALID")
                .containsAll(claimed);
    }

    /**
     * Grant without claim — and this is what makes the {@code /api/auth/**} wildcard's blast radius
     * visible. Today a new method on {@code AuthController} silently inherits the exemption and
     * nothing anywhere records that decision. Now it fails the build until someone writes it down.
     */
    @Test
    @DisplayName("no exemption entry covers a route that does not claim SUBSCRIPTION_EXEMPT")
    void everySubscriptionExemptGrantIsClaimed() {
        Set<String> exempted = endpoints.apiPatternsCoveredByAny(accessCheck.exemptPaths());
        Set<String> claimed = endpoints.apiPatternsClaiming(EndpointPolicy.Exposure.SUBSCRIPTION_EXEMPT);

        assertThat(exempted)
                .describedAs("the exemptions cover no live route at all — vacuous")
                .isNotEmpty();
        assertThat(claimed)
                .describedAs("routes exempted from the subscription gate whose handlers do not claim "
                        + "@EndpointPolicy(SUBSCRIPTION_EXEMPT). A wildcard exemption is wider than "
                        + "whoever wrote it believed, or a new endpoint was added inside one")
                .containsAll(exempted);
    }
}
