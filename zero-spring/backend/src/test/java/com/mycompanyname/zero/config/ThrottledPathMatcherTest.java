package com.mycompanyname.zero.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-level evidence for B1's normalisation, alongside the end-to-end proof in
 * {@code RateLimitBypassIT}.
 *
 * <p>The integration test proves the limiter cannot be walked past. This one pins down <em>why</em>,
 * one spelling at a time — which is what makes a future regression legible instead of appearing as a
 * mysterious 401 in a security test.
 */
class ThrottledPathMatcherTest {

    private static final List<String> CONFIGURED = List.of(
            "/api/auth/login", "/api/auth/refresh", "/api/account/forgot-password");

    private final ThrottledPathMatcher matcher = new ThrottledPathMatcher(CONFIGURED);

    @Test
    void theCanonicalPathMatches() {
        assertThat(throttles("/api/auth/login")).isTrue();
    }

    @Test
    void aPercentEncodedSpellingMatches() {
        // %6C is 'l'. The container decodes this and routes it to the login controller.
        assertThat(throttles("/api/auth/%6Cogin")).isTrue();
        assertThat(lookup("/api/auth/%6Cogin")).isEqualTo("/api/auth/login");
    }

    @Test
    void aPathParameterIsStripped() {
        assertThat(lookup("/api/auth/login;x=1")).isEqualTo("/api/auth/login");
        assertThat(throttles("/api/auth/login;x=1")).isTrue();
    }

    @Test
    void aPathParameterOnAnEarlierSegmentIsStripped() {
        assertThat(throttles("/api;a=1/auth/login")).isTrue();
    }

    @Test
    void anEncodedPathParameterIsStrippedAfterDecoding() {
        // %3B is ';'. Spring strips path parameters before decoding, so this survives that pass and
        // has to be removed afterwards too.
        assertThat(lookup("/api/auth/login%3Bx=1")).isEqualTo("/api/auth/login");
        assertThat(throttles("/api/auth/login%3Bx=1")).isTrue();
    }

    @Test
    void duplicateSlashesCollapse() {
        assertThat(lookup("/api//auth/login")).isEqualTo("/api/auth/login");
        assertThat(throttles("/api//auth/login")).isTrue();
    }

    @Test
    void aTrailingSlashMatches() {
        assertThat(throttles("/api/auth/login/")).isTrue();
    }

    @Test
    void caseVariantsMatch() {
        // Stricter than servlet routing on purpose: the cost of a false positive is one throttled
        // 404, the cost of a false negative is the whole control.
        assertThat(throttles("/API/AUTH/LOGIN")).isTrue();
        assertThat(throttles("/Api/Auth/Login")).isTrue();
    }

    @Test
    void aContextPathIsRemovedBeforeMatching() {
        // The finding's quietest variant: setting server.servlet.context-path used to disable the
        // limiter across every path at once, with nothing in the logs to say so.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/zero/api/auth/login");
        request.setContextPath("/zero");

        assertThat(matcher.lookupPath(request)).isEqualTo("/api/auth/login");
        assertThat(matcher.matches(matcher.lookupPath(request))).isTrue();
    }

    @Test
    void unconfiguredPathsDoNotMatch() {
        // The other half of the contract: normalisation must not drag neighbours into the throttle.
        assertThat(throttles("/api/localization/languages")).isFalse();
        assertThat(throttles("/api/users")).isFalse();
        assertThat(throttles("/actuator/health")).isFalse();
        assertThat(throttles("/api/auth/logout")).isFalse();
        assertThat(throttles("/api/auth/login/extra")).isFalse();
    }

    @Test
    void aMalformedEscapeDoesNotBecomeAnExemption() {
        // An undecodable path must not throw its way out of the filter, and must not be silently
        // treated as "not throttled" either.
        String path = matcher.lookupPath(request("/api/auth/%zz"));

        assertThat(path).isNotNull().startsWith("/");
    }

    private boolean throttles(String requestUri) {
        return matcher.matches(matcher.lookupPath(request(requestUri)));
    }

    private String lookup(String requestUri) {
        return matcher.lookupPath(request(requestUri));
    }

    private static MockHttpServletRequest request(String requestUri) {
        return new MockHttpServletRequest("POST", requestUri);
    }
}
