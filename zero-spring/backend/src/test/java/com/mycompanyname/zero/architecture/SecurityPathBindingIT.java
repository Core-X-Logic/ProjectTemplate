package com.mycompanyname.zero.architecture;

import com.mycompanyname.zero.AbstractIntegrationIT;
import com.mycompanyname.zero.config.RateLimitProperties;
import com.mycompanyname.zero.shared.web.EndpointPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds the path strings that make access decisions to the routes this application actually serves,
 * in BOTH directions.
 *
 * <p><b>The defect this closes (R-38A).</b> Five access decisions name a URL prefix owned by another
 * module. Because the binding is a string, the compiler, Modulith and bytecode ArchUnit are all
 * blind to it: rename {@code /api/localization} to {@code /api/i18n} and
 * {@code permitAll("/api/localization/**")} silently stops matching while every gate stays green. The
 * user-visible result is that the login screen cannot fetch the dictionary it needs BEFORE it can
 * render a login form, because that dictionary is now behind the credential the form exists to
 * collect.
 *
 * <p><b>WHICH LAYER IS LOAD-BEARING — read this before trusting a green result here.</b> Everything in
 * this class is derived from PARSED SOURCE TEXT of one named file. That layer was audited three times
 * and evaded three times, twice with a full green build and the grant live on the running chain: a
 * backslash-u escaped dot (javac decodes it before lexing, so both text detectors agree and the
 * cross-check cannot fire), a readable literal written in a helper class OUTSIDE
 * {@code SecurityConfig}, and — reasoned, then measured — a second {@code SecurityFilterChain} bean
 * and {@code WebSecurityCustomizer.ignoring()}, neither of which contains a {@code requestMatchers}
 * token for any text check to find. Measured, all four: this class 6/6 green and
 * {@code ArchitectureRulesTest} 9/9 green while {@code /api/users/**}, {@code /api/tenants/**},
 * {@code /api/roles/**} and {@code /api/audit-logs/**} were respectively at {@code permitAll}.
 *
 * <p>Scanning the source text of a fluent DSL cannot be made airtight — each fix closes one spelling
 * and the next spelling appears. {@code FilterChainReachabilityIT} is therefore the LOAD-BEARING
 * check: it probes the running filter chain, which is indifferent to spelling, to the file a grant is
 * written in, and to which Spring Security API produced it. It caught all four of the above.
 *
 * <p>These rules are KEPT deliberately, as defence in depth and as the fast half of the feedback loop:
 * they name a FILE and a LINE, run in milliseconds without a servlet container, and catch the
 * claim-without-grant direction that a reachability probe reports only as a working 401. What they
 * must never again be mistaken for is the guarantee. If this class is green and
 * {@code FilterChainReachabilityIT} is red, that is not a contradiction — it is exactly the case this
 * layer cannot see, and the wire wins.
 *
 * <p><b>Declare-and-verify, not generate.</b> The grant stays exactly where it is — literals in
 * {@code SecurityConfig} — so one reviewer still reads the complete effective grant in one file.
 * Generating permitAll matchers from annotations would decentralise the grant: a module could open
 * itself and {@code SecurityConfig} would show nothing. What is added is the handler's counter-claim
 * ({@code @EndpointPolicy}) and the assertion that the two agree. Neither side can widen exposure
 * alone: a claim with no grant is red, and a grant covering an unclaimed handler is red.
 */
class SecurityPathBindingIT extends AbstractIntegrationIT {

    private static final String SECURITY_CONFIG =
            "com.mycompanyname.zero.identity.auth.SecurityConfig";

    // Actuator contributes controllerEndpointHandlerMapping, which is also a
    // RequestMappingHandlerMapping; the qualifier picks the MVC one that serves /api.
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private RateLimitProperties rateLimitProperties;

    private EndpointInventory endpoints;

    @BeforeEach
    void buildInventory() {
        endpoints = new EndpointInventory(handlerMapping);
    }

    /**
     * The parsed grant, with canaries. {@link JavaSources#permitAllMatchers} already throws when it
     * parses nothing; these are asserted on top of that, because "parsed SOMETHING" is not "parsed
     * the right thing" and every assertion below is only as good as this set.
     *
     * <p><b>What "already throws" does and does not cover.</b> The zero-group throw and the canaries
     * below catch a TOTAL parse loss. They do not catch an ADDITIVE one, and that was measured: a
     * grant written {@code .requestMatchers(PARTNER_PATHS).permitAll()} matched the group pattern,
     * yielded no literal, left both canaries standing and passed the size floor — the whole tenancy
     * admin surface at {@code permitAll}, build green. {@code permitAllMatchers} now refuses to
     * return at all unless EVERY {@code requestMatchers} argument in the file is an inline string
     * literal, so "the parsed set" and "the effective grant" cannot diverge silently. Everything
     * asserted below is therefore about the complete grant, not about the readable part of it.
     *
     * <p>The canaries deliberately pin the SHAPE of the parse, never a product route. An earlier
     * version named {@code /api/auth/login}, and a probe caught the mistake: widening that matcher to
     * {@code /api/auth/**} — a legitimate edit, and exactly the mis-scoping this class exists to
     * detect — tripped the canary first and MASKED the real assertion with a parser complaint. A
     * guard that fires before the rule it guards makes the rule unreadable. So: one grant from the
     * always-on block and one from the profile-conditional block (proving the parser reads the whole
     * chain and not just the top of it), neither of which any route rename can move.
     */
    private Set<String> apiPermitAllMatchers() {
        Set<String> all = JavaSources.permitAllMatchers(SECURITY_CONFIG);
        assertThat(all)
                .describedAs("the SecurityConfig parser stopped seeing the framework grants that "
                        + "bracket the chain. Every assertion in this class is derived from this set, "
                        + "so a silent parse regression would turn the whole file green while proving "
                        + "nothing")
                .contains("/actuator/health/**", "/swagger-ui.html");

        Set<String> api = new TreeSet<>();
        all.stream().filter(matcher -> matcher.startsWith("/api")).forEach(api::add);
        assertThat(api)
                .describedAs("the parser found framework grants but no /api grant at all. The "
                        + "anonymous API surface cannot have vanished; the parse is partial")
                .hasSizeGreaterThanOrEqualTo(3);
        return api;
    }

    // -----------------------------------------------------------------------------------
    // Resolution — does the string point at anything at all?
    // -----------------------------------------------------------------------------------

    /**
     * The single assertion that catches the localization rename, at the {@code SecurityConfig} site.
     * A grant that covers zero live routes grants nothing; the endpoint it was written for is now
     * answering 401.
     */
    @Test
    @DisplayName("every permitAll /api matcher resolves to at least one live route")
    void everyPermitAllMatcherResolvesToALiveRoute() {
        Set<String> matchers = apiPermitAllMatchers();
        assertThat(matchers).isNotEmpty();

        for (String matcher : matchers) {
            assertThat(endpoints.apiPatternsCoveredBy(matcher))
                    .describedAs("permitAll(\"%s\") in SecurityConfig covers no route this "
                            + "application serves. Either the endpoint moved and this grant silently "
                            + "stopped granting, or the string never matched. Live /api routes: %s",
                            matcher, endpoints.apiPatterns())
                    .isNotEmpty();
        }
    }

    /** The same question for the throttle list, which is a path decision made from a property. */
    @Test
    @DisplayName("every zero.ratelimit.paths entry resolves to at least one live route")
    void everyThrottledPathResolvesToALiveRoute() {
        assertThat(rateLimitProperties.getPaths())
                .describedAs("the throttle list is empty, so the anonymous endpoints are unmetered "
                        + "and every assertion about it is vacuous")
                .isNotEmpty();

        for (String path : rateLimitProperties.getPaths()) {
            assertThat(endpoints.apiPatternsCoveredBy(path))
                    .describedAs("zero.ratelimit.paths names \"%s\", which matches no live route — "
                            + "an endpoint someone believes is throttled and is not", path)
                    .isNotEmpty();
        }
    }

    // -----------------------------------------------------------------------------------
    // Agreement — grant ⊆ claim and claim ⊆ grant
    // -----------------------------------------------------------------------------------

    /**
     * Claim without grant. A module adds an endpoint it believes is public, forgets the matcher, and
     * production answers 401 while every gate today stays green.
     */
    @Test
    @DisplayName("every ANONYMOUS claim is backed by a permitAll matcher")
    void everyAnonymousClaimIsGrantedByAPermitAllMatcher() {
        Set<String> granted = endpoints.apiPatternsCoveredByAny(apiPermitAllMatchers());
        Set<String> claimed = endpoints.apiPatternsClaiming(EndpointPolicy.Exposure.ANONYMOUS);

        assertThat(claimed)
                .describedAs("no route claims ANONYMOUS — this assertion would be vacuous")
                .isNotEmpty();
        assertThat(granted)
                .describedAs("routes claiming @EndpointPolicy(ANONYMOUS) that NO permitAll matcher "
                        + "covers. The handler believes it is reachable without a credential and the "
                        + "filter chain disagrees; callers get 401")
                .containsAll(claimed);
    }

    /**
     * Grant without claim — the dangerous direction, over the grants this parser can SEE.
     *
     * <p>A matcher typed {@code /api/account/**} instead of {@code /api/account/confirm-email} exposes
     * {@code change-password}, and this catches it at the line that wrote it.
     *
     * <p><b>Its blind spot, stated because it was measured.</b> {@code JavaSources.permitAllMatchers}
     * is only ever called with the {@code SECURITY_CONFIG} constant, so a grant written anywhere else
     * never enters the set this iterates. That is the N2 evasion, and this test — whose javadoc used
     * to call itself "the direction that leaks" without qualification — stayed green through it.
     * {@code FilterChainReachabilityIT} walks every MAPPED ROUTE instead of every PARSED GRANT, which
     * is the only walk that can discover a grant nobody declared.
     */
    @Test
    @DisplayName("no permitAll matcher covers a route that does not claim ANONYMOUS")
    void everyPermitAllGrantOnlyCoversClaimedRoutes() {
        Set<String> granted = endpoints.apiPatternsCoveredByAny(apiPermitAllMatchers());
        Set<String> claimed = endpoints.apiPatternsClaiming(EndpointPolicy.Exposure.ANONYMOUS);

        assertThat(granted)
                .describedAs("permitAll grants cover no live route at all — vacuous")
                .isNotEmpty();
        assertThat(claimed)
                .describedAs("routes exposed anonymously by a permitAll matcher whose handler does "
                        + "NOT claim @EndpointPolicy(ANONYMOUS). Either the matcher is wider than "
                        + "intended, or an endpoint was added inside an already-open prefix. This is "
                        + "the direction that leaks")
                .containsAll(granted);
    }

    /**
     * The live proof, and the backstop for the one drift the agreement rules cannot see: the claim
     * and the grant being deleted TOGETHER, consistently and wrongly. Only a real credential-free
     * request notices that.
     *
     * <p><b>This walks the CLAIMED set, and that is its ceiling.</b> Checking that every claim works
     * can never discover a grant nobody claimed — the set it iterates does not contain it. It also
     * {@code continue}s past every non-GET and every path-variable route, which is how a loop turns
     * into a silent exclusion. {@code FilterChainReachabilityIT} is the inverse and the load-bearing
     * one: it walks every route the dispatcher maps, probes each with a method that route does not
     * support (so no handler is ever reached), and excludes only by name and reason.
     */
    @Test
    @DisplayName("every ANONYMOUS route really answers without a credential")
    void everyAnonymousRouteAnswersWithoutACredential() {
        Set<String> claimed = endpoints.apiPatternsClaiming(EndpointPolicy.Exposure.ANONYMOUS);
        assertThat(claimed).isNotEmpty();

        for (String pattern : claimed) {
            HandlerMethod handler = endpoints.handlerFor(pattern);
            // GET-only and path-variable-free: this probe must not have side effects, and a POST
            // body would be a different test. The POST endpoints are covered by the agreement rules
            // above plus RateLimitMediaTypeFailClosedIT, which drives them for real.
            if (pattern.contains("{") || !isGetLike(handler)) {
                continue;
            }
            assertThat(restTemplate.getForEntity(pattern, String.class).getStatusCode().value())
                    .describedAs("%s claims ANONYMOUS but a credential-free request was refused. "
                            + "This is the localization-rename failure: the login screen cannot load "
                            + "the dictionary it needs before it can offer a login form", pattern)
                    .isNotEqualTo(401);
        }
    }

    // -----------------------------------------------------------------------------------
    // Derived obligations
    // -----------------------------------------------------------------------------------

    /**
     * The throttle list, DERIVED rather than hardcoded.
     *
     * <p>{@code application.yml} claimed this was already checked against {@code SecurityConfig} by
     * {@code RateLimitMediaTypeFailClosedIT.everyAnonymousBodyEndpointIsThrottled}. It was not: that
     * test hardcodes the same five paths and never reads {@code SecurityConfig}, so the two lists
     * agreed only because one was copied from the other — which is how {@code confirm-email} slipped
     * through unthrottled in the first place, as that test's own javadoc admits. This derives the
     * obligation from the handlers instead, so the next anonymous POST cannot repeat it. The existing
     * test keeps its job of proving the throttle actually bites.
     */
    @Test
    @DisplayName("every anonymous endpoint taking a body is in zero.ratelimit.paths")
    void everyAnonymousBodyHandlerIsThrottled() {
        Set<String> throttled = endpoints.apiPatternsCoveredByAny(rateLimitProperties.getPaths());
        Set<String> anonymousBodyRoutes = new LinkedHashSet<>();
        for (String pattern : endpoints.apiPatternsClaiming(EndpointPolicy.Exposure.ANONYMOUS)) {
            if (takesRequestBody(endpoints.handlerFor(pattern))) {
                anonymousBodyRoutes.add(pattern);
            }
        }

        assertThat(anonymousBodyRoutes)
                .describedAs("no anonymous endpoint takes a @RequestBody, so this derivation would "
                        + "certify nothing — five such endpoints exist")
                .isNotEmpty();
        assertThat(throttled)
                .describedAs("anonymous endpoints that accept a request body and are NOT covered by "
                        + "zero.ratelimit.paths. An unauthenticated caller can drive these into "
                        + "bcrypt, the database or the mail sender at will")
                .containsAll(anonymousBodyRoutes);
    }

    private static boolean takesRequestBody(HandlerMethod handler) {
        for (MethodParameter parameter : handler.getMethodParameters()) {
            if (parameter.hasParameterAnnotation(RequestBody.class)) {
                return true;
            }
        }
        return false;
    }

    private boolean isGetLike(HandlerMethod handler) {
        return handler.hasMethodAnnotation(org.springframework.web.bind.annotation.GetMapping.class);
    }
}
