package com.mycompanyname.zero.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.util.UrlPathHelper;

import java.util.List;
import java.util.Locale;

/**
 * Decides whether a request is on a throttled path, and names the bucket it belongs to (B1).
 *
 * <p><b>Why this exists.</b> The filter used to compare {@code request.getRequestURI()} against the
 * configured strings with {@code Set.contains}. {@code getRequestURI()} is the <em>raw</em> request
 * line: still percent-encoded, still carrying path parameters, still carrying the servlet context
 * path. Spring MVC and Spring Security route on the decoded lookup path instead, so the two
 * disagreed and every disagreement was a way past the limiter:
 *
 * <ul>
 *   <li>{@code POST /api/auth/%6Cogin} — decodes to {@code /api/auth/login} and reaches the login
 *       controller, but is not string-equal to it, so it was never counted. Confirmed end to end:
 *       unlimited password guesses at full bcrypt cost.</li>
 *   <li>{@code server.servlet.context-path=/zero} — every raw URI then starts with {@code /zero},
 *       matches nothing in the list, and the limiter is disabled deployment-wide, silently, with
 *       no configuration error to notice.</li>
 *   <li>{@code POST /api/auth/login;x=1}, {@code %3Bx=1}, {@code /api//auth/login} — path
 *       parameters and duplicate slashes, each giving a fresh raw URI and therefore a fresh bucket
 *       for every value. Spring Security's {@code StrictHttpFirewall} happens to reject all three
 *       before the chain runs, so they are not currently exploitable here — but that is a default
 *       in a component this class does not own, and one relaxed firewall setting away from being a
 *       bypass again. Normalised here so the limiter stands on its own.</li>
 * </ul>
 *
 * <p><b>The fix.</b> Match the same path the request will actually be routed on:
 * {@link UrlPathHelper} strips the context path, removes path parameters and collapses duplicate
 * slashes, then percent-decodes. Any {@code ;} surviving the decode (from an encoded {@code %3B})
 * is stripped afterwards, so a second encoding layer buys nothing either.
 *
 * <p><b>Case.</b> Matching is deliberately case-<em>insensitive</em>, which is stricter than the
 * routing it shadows. Servlet routing is case-sensitive, so {@code /API/AUTH/LOGIN} is a 404 rather
 * than a bypass today — but the cost of being wrong in that direction is one throttled 404, and the
 * cost of being wrong in the other direction is an unlimited credential-stuffing channel. Fail
 * closed.
 *
 * <p>The normalised path is also what keys the buckets, so every spelling of one endpoint draws on
 * one allowance instead of minting a fresh one.
 */
final class ThrottledPathMatcher {

    /**
     * Shared, immutable, and configured the way Spring's own dispatch is: {@code urlDecode=true},
     * {@code removeSemicolonContent=true}.
     */
    private static final UrlPathHelper PATH_HELPER = UrlPathHelper.defaultInstance;

    private final AntPathMatcher matcher;
    private final List<String> patterns;

    ThrottledPathMatcher(List<String> configuredPaths) {
        this.matcher = new AntPathMatcher();
        this.matcher.setCaseSensitive(false);
        this.patterns = configuredPaths.stream()
                .filter(path -> path != null && !path.isBlank())
                .map(ThrottledPathMatcher::normalisePattern)
                .toList();
    }

    /**
     * The canonical form of this request's path: context-path free, decoded, free of path
     * parameters, lower-cased. Never null — an unparseable URI degrades to {@code "/"} rather than
     * to "not throttled".
     */
    String lookupPath(HttpServletRequest request) {
        String path;
        try {
            path = PATH_HELPER.getPathWithinApplication(request);
        } catch (RuntimeException ex) {
            // A malformed percent-escape makes the decoder throw. Refusing to name a path here would
            // mean refusing to throttle it, so hand back a token that matches nothing routable and
            // still gives the request a stable bucket.
            return "/__unparseable__";
        }
        return normalise(path);
    }

    /** True when {@code lookupPath} is one of the configured throttled endpoints. */
    boolean matches(String lookupPath) {
        for (String pattern : patterns) {
            if (matcher.match(pattern, lookupPath)) {
                return true;
            }
        }
        return false;
    }

    private static String normalisePattern(String pattern) {
        String trimmed = pattern.trim();
        if (!trimmed.startsWith("/")) {
            trimmed = "/" + trimmed;
        }
        return normalise(trimmed);
    }

    /**
     * {@code UrlPathHelper} already removed path parameters and collapsed {@code //} — but it does so
     * <em>before</em> percent-decoding, so {@code /login%3Bx=1} still arrives here as
     * {@code /login;x=1}. Strip once more, post-decode.
     */
    private static String normalise(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        String result = path;
        int semicolon = result.indexOf(';');
        while (semicolon >= 0) {
            int nextSlash = result.indexOf('/', semicolon);
            result = nextSlash < 0
                    ? result.substring(0, semicolon)
                    : result.substring(0, semicolon) + result.substring(nextSlash);
            semicolon = result.indexOf(';');
        }
        while (result.contains("//")) {
            result = result.replace("//", "/");
        }
        // A trailing slash is not a distinct endpoint in Spring Boot 3 (it is a 404), and treating it
        // as one would hand back the free pass this whole class exists to remove.
        while (result.length() > 1 && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        if (!result.startsWith("/")) {
            result = "/" + result;
        }
        return result.toLowerCase(Locale.ROOT);
    }
}
