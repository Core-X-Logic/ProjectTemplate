package com.mycompanyname.zero.architecture;

import com.mycompanyname.zero.AbstractIntegrationIT;
import com.mycompanyname.zero.shared.web.EndpointPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.server.PathContainer;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asks the RUNNING FILTER CHAIN which routes it will serve without a credential, and requires every
 * such route to have claimed {@code @EndpointPolicy(ANONYMOUS)}.
 *
 * <p><b>This is the load-bearing layer of R-38A. The source-text rules are not.</b>
 * {@code SecurityPathBindingIT} and Rule 6 in {@code ArchitectureRules} parse {@code SecurityConfig}'s
 * fluent DSL and compare the literals they find against the handler mapping. That approach was audited
 * three times and evaded three times, twice with a full green build and the grant live on the chain:
 *
 * <ul>
 *   <li><b>N1 — the unicode escape.</b> The dot of the fluent call written as a backslash-u escape
 *       of code point 002e, i.e. an escaped dot immediately followed by
 *       {@code requestMatchers(EVASIVE_PATHS).permitAll()}. javac decodes backslash-u escapes
 *       <em>before</em> lexing — this javadoc had to be reworded because spelling the escape out
 *       verbatim made the file itself fail to compile, which demonstrates the mechanism in one
 *       line — so that is a genuine call, but neither
 *       text detector contains a literal dot to match — so the two detectors AGREE, the cross-check in
 *       {@code JavaSources.verifyScanAgreesWithIndependentCount} cannot fire, and {@code javap}
 *       confirmed the {@code invokevirtual}. Measured: {@code /api/tenants/**} at {@code permitAll},
 *       surefire 138 / failsafe 271 / BUILD SUCCESS.
 *   <li><b>N2 — the realistic one.</b> A perfectly readable inline literal written OUTSIDE
 *       {@code SecurityConfig}: {@code class AnonymousPathPolicy { void apply(auth) {
 *       auth.requestMatchers("/api/users/**").permitAll(); } } }. The readability check passes it (it
 *       IS a literal), the ownership check passes it ({@code identity} owns {@code /api/users} and the
 *       class lives in {@code identity}), and {@code JavaSources.permitAllMatchers} is only ever called
 *       with the constant {@code SecurityConfig} — so the grant never enters the set the agreement
 *       assertions run over, including the one whose own javadoc calls itself "the direction that
 *       leaks". BUILD SUCCESS.
 *   <li><b>Reasoned, not reproduced.</b> A second {@code SecurityFilterChain} bean,
 *       {@code .securityMatcher(...)} and {@code WebSecurityCustomizer.ignoring()} contain no
 *       {@code requestMatchers} token at all, so no text check can even be attempted on them.
 * </ul>
 *
 * <p>The conclusion the audits force is that scanning the source text of a fluent DSL cannot be made
 * airtight — each fix closes one spelling and the next spelling appears. The only spelling-independent
 * measurement is the chain itself. The text rules are KEPT, because they name a file and a line and
 * this test cannot; they are fast, precise feedback. They are no longer the guarantee.
 *
 * <p><b>The discriminator, and why a plain GET is not one.</b> Send, with no credential and no body,
 * an HTTP method the target pattern does not map. {@code 401} means the chain is CLOSED for that path;
 * anything else means it is OPEN. {@code AuthorizationFilter} runs before {@code DispatcherServlet}, so
 * on a closed path the request dies at the entry point and never reaches handler lookup; on an open
 * path it reaches {@code RequestMappingHandlerMapping}, which cannot match the method and throws
 * before any handler is selected. Method security is therefore structurally unreachable on this probe.
 * A plain anonymous {@code GET} lacks that property and was measured returning the same {@code 401} in
 * both configurations — once from the filter chain, once from {@code @PreAuthorize} <em>after</em> the
 * handler had been dispatched and audited. Same status, opposite meaning.
 *
 * <p>The reading is {@code 401} vs not-{@code 401}, never {@code 401} vs {@code 405}: an open chain
 * legitimately answers {@code 404} (unmapped path under an open prefix) and {@code 429} (throttled),
 * so asserting {@code == 405} would produce false reds.
 *
 * <p><b>No side effects, proved rather than asserted.</b> The unsupported-method probe resolves to no
 * handler at all ({@code HttpRequestMethodNotSupportedException} is thrown during lookup), so no
 * controller body, no argument resolution and no {@code @RequestBody} binding runs. Independently: six
 * such probes against an {@code audit_logs} table at zero rows produced zero rows, while one served
 * {@code GET} moved it to one. The naive anonymous {@code GET} is NOT side-effect free — on an open
 * chain it was dispatched and audited before method security denied it.
 *
 * <p><b>Methods deliberately never emitted.</b> {@code TRACE} and invented verbs are answered
 * {@code 400} by Tomcat in both configurations, before the chain, with a fabricated {@code Allow}
 * header belonging to no route. CORS-preflight {@code OPTIONS} is answered {@code 200} by
 * {@code CorsFilter} in both configurations — a probe whose meaning flips on the presence of an
 * {@code Origin} header is not a probe. This test uses {@code java.net.http.HttpClient} directly, with
 * no headers beyond what the JDK must send, so no {@code Origin} can leak in from a configured
 * {@code RestTemplate}.
 */
class FilterChainReachabilityIT extends AbstractIntegrationIT {

    /**
     * The verbs the probe may use, in preference order. {@code HEAD} and {@code OPTIONS} are absent
     * because the framework synthesises them for any mapped pattern, so neither is ever genuinely
     * "unmapped"; {@code TRACE} and invented verbs are absent for the reason in the class javadoc.
     * {@code GET} is last: it is only reached when a pattern maps all four write verbs and not
     * {@code GET}, in which case it is as unmapped — and as handler-free — as the others.
     */
    private static final List<RequestMethod> PROBE_CANDIDATES = List.of(
            RequestMethod.PATCH, RequestMethod.DELETE, RequestMethod.PUT,
            RequestMethod.POST, RequestMethod.GET);

    /**
     * Substituted for every {@code {pathVariable}} so a pattern becomes a URI that can be sent. The
     * value is irrelevant to the measurement — the probe never reaches argument binding — but the
     * substituted URI MUST still match the pattern it came from, which {@link #concreteUriFor}
     * verifies against {@code PathPatternParser} and fails loudly when it does not. A URI that has
     * silently stopped matching would measure a different route, and the dangerous direction of that
     * error is a leak read as closed.
     */
    private static final String PATH_VARIABLE_FILLER = "1";

    /**
     * The one NAMED exclusion, applied by ownership rather than by path spelling.
     *
     * <p>{@code @EndpointPolicy} targets {@code METHOD} and can only be written on a handler this
     * project declares; there is no way to annotate {@code org.springdoc}'s
     * {@code OpenApiWebMvcResource}. Its exposure is a deliberate, separately locked decision:
     * {@code SecurityConfig} grants {@code /v3/api-docs/**} and {@code /swagger-ui/**} only when the
     * {@code dev} or {@code test} profile is active (B6/C5 — closed by default, opened by naming the
     * environments that want it), and {@code application-prod.yml} disables springdoc outright as the
     * second lock.
     *
     * <p>Excluded by NAME and reason, never by a {@code continue} — an open route owned by any other
     * foreign package is a failure, not a skip. That distinction is the whole difference between this
     * test and {@code everyAnonymousRouteAnswersWithoutACredential}, which walks the claimed set and
     * skips everything else.
     */
    private static final String FOREIGN_PACKAGE_ALLOWED_TO_BE_OPEN = "org.springdoc";

    /**
     * The second and last NAMED exclusion: Boot's error forward target.
     *
     * <p>{@code BasicErrorController} is mapped with no method condition at all, so it answers every
     * verb and there is no unsupported one to probe with — measured, as the loud failure this
     * exclusion replaces: {@code Pattern /error maps every HTTP method this probe can use (mapped: [],
     * matches-all-methods=true)}. It is also not a route a client addresses: the container FORWARDS to
     * it on the ERROR dispatch, and its reachability is therefore not the access decision this test
     * measures.
     *
     * <p>Guarded, not trusted. The exclusion applies only while every handler on {@code /error} is
     * foreign-declared. An application controller mapped at {@code /error} is a real route with a real
     * handler and would be probed like any other — the exclusion cannot be inherited by writing one.
     */
    private static final String ERROR_FORWARD_TARGET = "/error";

    @LocalServerPort
    private int port;

    // Actuator contributes controllerEndpointHandlerMapping, which is also a
    // RequestMappingHandlerMapping; the qualifier picks the MVC one that serves /api.
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private EndpointInventory endpoints;
    private HttpClient client;

    @BeforeEach
    void setUp() {
        endpoints = new EndpointInventory(handlerMapping);
        client = HttpClient.newBuilder()
                // A redirect is an ANSWER, and answering is what "open" means. Following one would
                // report the status of some other route.
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * The inverse of {@code SecurityPathBindingIT.everyAnonymousRouteAnswersWithoutACredential}.
     *
     * <p>That test walks the CLAIMED set and checks the claims work. Walking the claimed set can never
     * discover an unclaimed grant — the N2 evasion sat outside every set it consults. This walks every
     * route the dispatcher maps and asks the chain about each one, so a grant is caught no matter how
     * it was spelled, where it was written, or which Spring Security API produced it.
     */
    @Test
    @DisplayName("every route the live chain serves without a credential claims @EndpointPolicy(ANONYMOUS)")
    void everyRouteReachableWithoutACredentialClaimsAnonymous() {
        Set<String> patterns = new TreeSet<>(endpoints.patterns());

        Set<String> open = new TreeSet<>();
        Set<String> closed = new TreeSet<>();
        Set<String> throttled = new TreeSet<>();
        Set<String> foreignOpenAndAllowed = new TreeSet<>();
        Set<String> excluded = new TreeSet<>();
        Map<String, String> unclaimed = new LinkedHashMap<>();

        for (String pattern : patterns) {
            if (ERROR_FORWARD_TARGET.equals(pattern)
                    && endpoints.handlersFor(pattern).stream().allMatch(FilterChainReachabilityIT::isForeign)) {
                excluded.add(pattern);
                continue;
            }
            Probe probe = probe(pattern);
            if (probe.status() == 429) {
                // NOT evidence of openness. RateLimitFilter sits after CorsFilter and therefore ahead
                // of the authorization filter, so it can answer before the chain decides — which would
                // read a CLOSED throttled path as open. Measured at capacity 3: probes 1-3 on
                // /api/auth/login gave 405, probes 4-6 gave 429. Each pattern is probed exactly once
                // per run and the suite runs at capacity 10000, so this is unreachable in practice;
                // when it does happen the run is inconclusive and says so, rather than guessing.
                throttled.add(probe.describe());
                continue;
            }
            if (probe.status() == 401) {
                closed.add(pattern);
                continue;
            }
            open.add(pattern);

            List<HandlerMethod> handlers = new ArrayList<>(endpoints.handlersFor(pattern));
            if (handlers.stream().allMatch(FilterChainReachabilityIT::isForeign)) {
                if (handlers.stream().allMatch(FilterChainReachabilityIT::isForeignAndAllowedOpen)) {
                    foreignOpenAndAllowed.add(pattern);
                    continue;
                }
                unclaimed.put(pattern, probe.describe() + " handled by "
                        + handlers.stream().map(h -> h.getBeanType().getName()).toList()
                        + ", which this project does not declare and cannot annotate");
                continue;
            }
            List<HandlerMethod> unclaimedHandlers = handlers.stream()
                    .filter(handler -> !EndpointInventory.claims(handler, EndpointPolicy.Exposure.ANONYMOUS))
                    .toList();
            if (!unclaimedHandlers.isEmpty()) {
                unclaimed.put(pattern, probe.describe() + " handled by "
                        + unclaimedHandlers.stream().map(EndpointInventory::key).toList());
            }
        }

        // ---- vacuity guards, before the rule they guard ------------------------------------
        assertThat(throttled)
                .describedAs("probe(s) answered 429, which RateLimitFilter can produce BEFORE the "
                        + "authorization filter decides anything. That is not evidence of openness "
                        + "and must not be read as any answer at all: this run is INCONCLUSIVE, not "
                        + "green. Rerun; if it repeats, zero.ratelimit.capacity in the test profile "
                        + "has been lowered below what one probe per pattern needs")
                .isEmpty();

        // Relationship, not a magnitude. A hardcoded "there are 43 routes" is a number someone edits
        // to make the build green; this says every mapped pattern was either measured or excluded by
        // name, so a route cannot leave the measured set without also leaving the inventory.
        assertThat(open.size() + closed.size() + excluded.size())
                .describedAs("some mapped pattern(s) were neither measured nor named as an exclusion. "
                        + "Mapped: %s. Open: %s. Closed: %s. Excluded by name: %s. A route that is "
                        + "silently neither is an unmeasured route, and every assertion below would "
                        + "pass over a set that quietly shrank — the exact vacuous-green this "
                        + "repository has shipped five times",
                        patterns.size(), open, closed, excluded)
                .isEqualTo(patterns.size());

        assertThat(closed)
                .describedAs("the probe read EVERY one of the %d mapped routes as OPEN. A discriminator "
                        + "that never returns 401 is not measuring the filter chain — it is measuring "
                        + "something that answers the same way regardless, and would certify a "
                        + "completely unsecured application", patterns.size())
                .isNotEmpty();

        Set<String> claimedAnonymous = endpoints.apiPatternsClaiming(EndpointPolicy.Exposure.ANONYMOUS);
        assertThat(claimedAnonymous)
                .describedAs("no route claims @EndpointPolicy(ANONYMOUS), so the guard below has no "
                        + "demonstrable anonymous surface to check itself against")
                .isNotEmpty();
        assertThat(open)
                .describedAs("the probe read every route as CLOSED, yet %d route(s) claim "
                        + "@EndpointPolicy(ANONYMOUS) and are covered by a permitAll matcher. Either "
                        + "the login screen is genuinely broken, or — far more likely — the probe is "
                        + "answering 401 for a reason that has nothing to do with the chain (a filter "
                        + "placed ahead of AuthorizationFilter, a wrong port, a redirect). A probe "
                        + "that always says 'closed' can never find a leak", claimedAnonymous.size())
                .containsAll(claimedAnonymous);

        // ---- the rule ----------------------------------------------------------------------
        Set<String> openAndClaimed = new TreeSet<>(open);
        openAndClaimed.removeAll(unclaimed.keySet());
        openAndClaimed.removeAll(foreignOpenAndAllowed);
        assertThat(unclaimed)
                .describedAs("""
                        These routes are REACHABLE WITHOUT A CREDENTIAL on the running filter chain, \
                        and their handlers do not claim it.

                        For each one, do exactly one of two things:
                          (a) the exposure is intended -> add @EndpointPolicy(ANONYMOUS) to the \
                        handler method, and make sure SecurityPathBindingIT's grant-side assertions \
                        still agree; or
                          (b) the exposure is NOT intended -> remove the grant that opened it.

                        Before looking for that grant: IT NEED NOT BE IN SecurityConfig. This test \
                        measures the chain, not any file. A permitAll written in a helper class \
                        called from the chain builder, a second SecurityFilterChain bean, a \
                        .securityMatcher(...), or WebSecurityCustomizer.ignoring() all produce this \
                        failure and none of them appear in SecurityConfig's source. Grep the whole \
                        of src/main/java for permitAll, SecurityFilterChain and WebSecurityCustomizer \
                        rather than reading one file. If the source-text rules in SecurityPathBindingIT \
                        and ArchitectureRules Rule 6 are green while this is red, that is not a \
                        contradiction - it is precisely the case they cannot see, and this test is \
                        the load-bearing one.

                        Probe verdict per route (method chosen because the route does not map it, so \
                        no handler is ever reached): %s

                        Routes measured open and correctly claiming ANONYMOUS: %s
                        Routes measured open, foreign-owned, allowed by name: %s
                        Routes measured closed: %s""",
                        unclaimed.values(), openAndClaimed, foreignOpenAndAllowed, closed)
                .isEmpty();
    }

    // ---------------------------------------------------------------------------------------
    // the probe
    // ---------------------------------------------------------------------------------------

    private record Probe(String pattern, String uri, String method, int status) {
        String describe() {
            return method + " " + uri + " (pattern " + pattern + ") -> " + status;
        }
    }

    private Probe probe(String pattern) {
        String uri = concreteUriFor(pattern);
        String method = unmappedMethodFor(pattern, uri);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + uri))
                // noBody(), and no Content-Type: RateLimitFilter refuses a body it cannot account for
                // and a Content-Type it cannot parse, but deliberately lets "no label and no bytes"
                // through so the framework can answer the 405 that C3 exists to produce. Adding either
                // would turn this probe's answer into that filter's answer.
                .method(method, HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(30))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Probe(pattern, uri, method, response.statusCode());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException(
                    "The reachability probe could not complete " + method + " " + uri + " (pattern "
                            + pattern + "). A probe that cannot ask the question must fail the build "
                            + "rather than report the route as closed.", e);
        }
    }

    /**
     * A sendable URI for a pattern, with every {@code {variable}} substituted — and verified to still
     * match the pattern it came from.
     *
     * <p>Failing loudly instead of skipping is the point. If the substitution stopped matching, the
     * probe would measure some OTHER route: on an open prefix that reads as a false red, and on a
     * closed application it reads as a false green over a route that was never asked about.
     */
    private String concreteUriFor(String pattern) {
        StringBuilder uri = new StringBuilder(pattern.length());
        int depth = 0;
        for (char c : pattern.toCharArray()) {
            if (c == '{') {
                if (depth++ == 0) {
                    uri.append(PATH_VARIABLE_FILLER);
                }
            } else if (c == '}') {
                depth--;
            } else if (depth == 0) {
                uri.append(c);
            }
        }
        String candidate = uri.toString();
        PathPattern parsed = PathPatternParser.defaultInstance.parse(pattern);
        if (!parsed.matches(PathContainer.parsePath(candidate))) {
            throw new IllegalStateException(
                    "Cannot build a URI that matches the mapped pattern " + pattern + " (tried "
                            + candidate + "). This route would go unprobed, and an unprobed route is "
                            + "an unmeasured one — which is how a leak reads as green. Teach "
                            + "concreteUriFor the syntax this pattern uses; do not skip it.");
        }
        return candidate;
    }

    /**
     * An HTTP method that no pattern matching {@code uri} maps.
     *
     * <p>Derived per route from {@code RequestMappingInfo.getMethodsCondition()}, never hardcoded to
     * {@code PATCH}: today no {@code @PatchMapping} exists in this codebase and all fourteen
     * {@code @RequestMapping} uses are class-level, but a route that later maps {@code PATCH} would
     * turn a hardcoded probe into a real dispatch with real side effects.
     *
     * <p>The union is taken over EVERY pattern the concrete URI matches, not just the pattern under
     * test. A URI derived from {@code /api/users/{id}} may also match a broader pattern, and if that
     * broader one maps the probe verb the request would reach a handler after all.
     */
    private String unmappedMethodFor(String pattern, String uri) {
        PathContainer path = PathContainer.parsePath(uri);
        Set<RequestMethod> mapped = new LinkedHashSet<>();
        boolean anyMapsEveryMethod = false;
        for (String candidatePattern : endpoints.patterns()) {
            if (!PathPatternParser.defaultInstance.parse(candidatePattern).matches(path)) {
                continue;
            }
            mapped.addAll(endpoints.mappedMethods(candidatePattern));
            anyMapsEveryMethod |= endpoints.mapsEveryMethod(candidatePattern);
        }
        if (!anyMapsEveryMethod) {
            for (RequestMethod candidate : PROBE_CANDIDATES) {
                if (!mapped.contains(candidate)) {
                    return candidate.name();
                }
            }
        }
        // Documented fallback: the pattern answers every verb this probe could use, so no unsupported
        // method exists. Bare OPTIONS still discriminates (401 closed / 200 open) PROVIDED no Origin
        // and no Access-Control-Request-Method are sent — with them, CorsFilter answers 200 on a
        // CLOSED path too. Weaker than the primary probe (OPTIONS resolves to a framework-synthetic
        // HandlerMethod, so interceptors fire even though no controller runs), which is why it is the
        // fallback and not the default.
        if (!mapped.contains(RequestMethod.OPTIONS) && !anyMapsEveryMethod) {
            return RequestMethod.OPTIONS.name();
        }
        throw new IllegalStateException(
                "Pattern " + pattern + " maps every HTTP method this probe can use (mapped: " + mapped
                        + ", matches-all-methods=" + anyMapsEveryMethod + "), so its reachability "
                        + "cannot be measured without dispatching a real request to a real handler. "
                        + "Failing loudly and naming it, rather than skipping it: a skipped route is "
                        + "an unmeasured route, and this test exists because unmeasured routes are "
                        + "how the grant-vs-claim rules were evaded three times.");
    }

    private static boolean isForeign(HandlerMethod handler) {
        return !handler.getBeanType().getName().startsWith(ArchitectureRules.BASE_PACKAGE);
    }

    private static boolean isForeignAndAllowedOpen(HandlerMethod handler) {
        return handler.getBeanType().getName().startsWith(FOREIGN_PACKAGE_ALLOWED_TO_BE_OPEN);
    }
}
