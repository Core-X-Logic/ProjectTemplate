package com.mycompanyname.zero.architecture;

import com.mycompanyname.zero.shared.web.EndpointPolicy;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The routes this application ACTUALLY serves, asked of Spring rather than derived from annotations.
 *
 * <p><b>Why this and not ArchUnit.</b> Nothing in this test tree reads
 * {@link RequestMappingHandlerMapping} today — the one place that touches MVC internals reads
 * {@code RequestMappingHandlerAdapter}, and only for its message converters. Every existing check
 * that reasons about paths therefore reasons about a reconstruction of the mapping table, and two
 * controllers ({@code AuditLogController}, {@code FeatureController}) carry no class-level
 * {@code @RequestMapping} at all, so any reconstruction that starts from class annotations silently
 * omits them. Asking the framework removes that whole class of drift: these are the resolved,
 * absolute patterns the dispatcher will match against.
 *
 * <p>Fails loudly on an empty inventory. A path-agreement assertion over zero routes is vacuously
 * true, which is the exact failure this repository has shipped five times.
 */
public final class EndpointInventory {

    private final Map<String, HandlerMethod> handlerByPattern = new LinkedHashMap<>();

    /**
     * EVERY handler on a pattern, not just the first. {@code TenantController} maps {@code list()} and
     * {@code create()} to the identical pattern {@code /api/tenants} (class-level
     * {@code @RequestMapping}, method-level {@code @GetMapping}/{@code @PostMapping} with no path), so
     * {@link #handlerByPattern} — which is {@code putIfAbsent} — holds one of the two and hides the
     * other. Reachability is a property of the PATTERN: if the filter chain lets an anonymous caller
     * through to {@code /api/tenants}, it lets them through to both handlers. Asking only the first
     * would certify a route on the strength of half of it.
     */
    private final Map<String, Set<HandlerMethod>> handlersByPattern = new LinkedHashMap<>();

    /** Union of the HTTP methods each pattern maps. Empty means "this pattern maps none of them". */
    private final Map<String, Set<RequestMethod>> methodsByPattern = new LinkedHashMap<>();

    /**
     * Patterns carrying at least one mapping with NO method condition, i.e. one that answers every
     * verb. For those, no unmapped method exists and the wire probe has to fall back — see
     * {@code FilterChainReachabilityIT}.
     */
    private final Set<String> patternsMappingEveryMethod = new LinkedHashSet<>();

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public EndpointInventory(RequestMappingHandlerMapping handlerMapping) {
        handlerMapping.getHandlerMethods().forEach((info, handler) ->
                patternValues(info).forEach(pattern -> {
                    handlerByPattern.putIfAbsent(pattern, handler);
                    handlersByPattern.computeIfAbsent(pattern, p -> new LinkedHashSet<>()).add(handler);
                    Set<RequestMethod> mapped = info.getMethodsCondition().getMethods();
                    if (mapped.isEmpty()) {
                        patternsMappingEveryMethod.add(pattern);
                    }
                    methodsByPattern.computeIfAbsent(pattern, p -> new LinkedHashSet<>()).addAll(mapped);
                }));
        if (handlerByPattern.isEmpty()) {
            throw new IllegalStateException(
                    "RequestMappingHandlerMapping reported no handler methods. Every assertion built "
                            + "on this inventory would pass over an empty set and verify nothing.");
        }
        if (apiPatterns().isEmpty()) {
            throw new IllegalStateException(
                    "No registered pattern starts with /api. This application is an API; seeing none "
                            + "means the inventory is not reading what it thinks it is reading. Known "
                            + "patterns: " + new TreeSet<>(handlerByPattern.keySet()));
        }
    }

    /** Every resolved pattern the dispatcher knows, absolute and fully concatenated. */
    public Set<String> patterns() {
        return Set.copyOf(handlerByPattern.keySet());
    }

    /** The subset under {@code /api} — the surface the access decisions in this codebase reason about. */
    public Set<String> apiPatterns() {
        Set<String> api = new TreeSet<>();
        handlerByPattern.keySet().stream().filter(p -> p.startsWith("/api")).forEach(api::add);
        return api;
    }

    /** Every {@code /api} pattern an Ant-style access-decision pattern covers. */
    public Set<String> apiPatternsCoveredBy(String antPattern) {
        Set<String> covered = new TreeSet<>();
        for (String pattern : apiPatterns()) {
            if (pathMatcher.match(antPattern, pattern)) {
                covered.add(pattern);
            }
        }
        return covered;
    }

    /** Every {@code /api} pattern any of the given decision patterns covers. */
    public Set<String> apiPatternsCoveredByAny(Iterable<String> antPatterns) {
        Set<String> covered = new TreeSet<>();
        antPatterns.forEach(pattern -> covered.addAll(apiPatternsCoveredBy(pattern)));
        return covered;
    }

    /** The {@code /api} patterns a single handler is mapped to. Never empty for a registered handler. */
    public Set<String> apiPatternsOf(HandlerMethod handler) {
        Set<String> patterns = new TreeSet<>();
        handlerByPattern.forEach((pattern, mapped) -> {
            if (mapped.equals(handler) && pattern.startsWith("/api")) {
                patterns.add(pattern);
            }
        });
        return patterns;
    }

    /** Every {@code /api} pattern whose handler claims {@code exposure}. */
    public Set<String> apiPatternsClaiming(EndpointPolicy.Exposure exposure) {
        Set<String> patterns = new TreeSet<>();
        handlerByPattern.forEach((pattern, handler) -> {
            if (pattern.startsWith("/api") && claims(handler, exposure)) {
                patterns.add(pattern);
            }
        });
        return patterns;
    }

    /** Handlers claiming {@code exposure}, keyed {@code AuthController#login} as Rule 5 keys them. */
    public Set<String> handlerKeysClaiming(EndpointPolicy.Exposure exposure) {
        Set<String> keys = new TreeSet<>();
        handlerByPattern.values().forEach(handler -> {
            if (claims(handler, exposure)) {
                keys.add(key(handler));
            }
        });
        return keys;
    }

    public HandlerMethod handlerFor(String pattern) {
        return handlerByPattern.get(pattern);
    }

    /** Every handler mapped to {@code pattern}. See {@link #handlersByPattern} for why not just one. */
    public Set<HandlerMethod> handlersFor(String pattern) {
        return Set.copyOf(handlersByPattern.getOrDefault(pattern, Set.of()));
    }

    /** The HTTP methods {@code pattern} maps. Meaningless unless {@link #mapsEveryMethod} is false. */
    public Set<RequestMethod> mappedMethods(String pattern) {
        return Set.copyOf(methodsByPattern.getOrDefault(pattern, Set.of()));
    }

    /** Whether some mapping on {@code pattern} carries no method condition and so answers every verb. */
    public boolean mapsEveryMethod(String pattern) {
        return patternsMappingEveryMethod.contains(pattern);
    }

    public static String key(HandlerMethod handler) {
        return handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName();
    }

    public static boolean claims(HandlerMethod handler, EndpointPolicy.Exposure exposure) {
        EndpointPolicy policy = handler.getMethodAnnotation(EndpointPolicy.class);
        return policy != null && List.of(policy.value()).contains(exposure);
    }

    /**
     * Boot 3 parses mappings with {@code PathPatternParser}. If a deployment switched back to
     * {@code AntPathMatcher} this condition would be null, the inventory would come back empty, and
     * the constructor above refuses — loud, not silent.
     */
    private static Set<String> patternValues(RequestMappingInfo info) {
        return info.getPathPatternsCondition() == null
                ? new LinkedHashSet<>()
                : new LinkedHashSet<>(info.getPathPatternsCondition().getPatternValues());
    }
}
