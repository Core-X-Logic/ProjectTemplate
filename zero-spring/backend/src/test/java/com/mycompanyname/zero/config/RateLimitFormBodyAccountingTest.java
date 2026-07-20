package com.mycompanyname.zero.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2'-A: {@code application/x-www-form-urlencoded} became an ACCOUNTABLE format — the PayTR
 * notification webhook transports its payload that way, and a throttled path refuses every format
 * the limiter cannot read an identity from (D1, fail-closed). The D1 doctrine allows widening
 * {@code RequestBodyFormats.isAccountable} only together with teaching
 * {@code RateLimitFilter.extractUsername} the same format; this class pins BOTH halves, unit-level,
 * against the real filter.
 *
 * <p><b>Negative evidence (recorded).</b> Run against the pre-P2'-A code — form-urlencoded absent
 * from {@code isAccountable} — {@link #aFormEncodedNotificationBodyPassesTheThrottle} goes red with
 * status 415 and an unreached chain: the exact refusal that would have made every PayTR
 * notification fail on the provider side (which PayTR answers by NOT settling the money).
 *
 * <p>The fail-closed rule for everything else is deliberately re-asserted here
 * ({@link #anUnparseableFormatIsStillRefused}) so this widening cannot be misread as a precedent
 * for allowlisting: YAML stayed refused through the change.
 */
class RateLimitFormBodyAccountingTest {

    private static final String PAYTR_PATH = "/api/billing/webhook/paytr";
    private static final int CAPACITY = 2;

    private RateLimitFilter filter;

    @BeforeEach
    void buildFilter() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.setCapacity(CAPACITY);
        properties.setRefillPeriod(Duration.ofMinutes(1));
        properties.setPaths(List.of(PAYTR_PATH));
        // 0 = no proxy: the mock's remoteAddr IS the client, so each test picks addresses directly.
        properties.setTrustedProxyCount(0);

        RequestMappingHandlerAdapter adapter = new RequestMappingHandlerAdapter();
        adapter.setMessageConverters(List.of(
                new StringHttpMessageConverter(), new MappingJackson2HttpMessageConverter()));
        filter = new RateLimitFilter(properties, new ObjectMapper(),
                new RequestBodyFormats(new StubProvider(adapter)));
    }

    @Test
    @DisplayName("a form-encoded notification body passes the throttle to the chain (red pre-P2'-A: 415)")
    void aFormEncodedNotificationBodyPassesTheThrottle() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request(paytrNotificationBody(), "198.51.120.1"), response, chain);

        assertThat(chain.getRequest())
                .as("the PayTR transport format must reach the handler — a 415 here is a failed "
                        + "notification on the provider side, i.e. unsettled money")
                .isNotNull();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    /**
     * The half that keeps D1 closed: admitting the format is legal ONLY because an identity can be
     * read from it. Every request below carries the same {@code email} from a DIFFERENT address, so
     * the username dimension is the only control that can stop the overflow — exactly the D1 attack
     * shape, in the newly admitted format.
     */
    @Test
    @DisplayName("a username field inside a form body is charged to the username bucket")
    void aFormBodyUsernameFieldIsCounted() throws Exception {
        String body = "email=form-victim%40example.com&note=x";
        for (int attempt = 1; attempt <= CAPACITY; attempt++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request(body, "198.51.121." + attempt), response, new MockFilterChain());
            assertThat(response.getStatus())
                    .as("attempt %d is inside the allowance", attempt)
                    .isEqualTo(HttpStatus.OK.value());
        }

        MockFilterChain overflowChain = new MockFilterChain();
        MockHttpServletResponse overflow = new MockHttpServletResponse();
        filter.doFilter(request(body, "198.51.121.99"), overflow, overflowChain);

        assertThat(overflow.getStatus())
                .as("the rotating address defeats the IP bucket by design; only the username "
                        + "bucket can refuse this, so a pass here means form identities are not "
                        + "being counted — D1 reopened in the new format")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(overflowChain.getRequest()).isNull();
    }

    /**
     * A PayTR notification carries no username-vocabulary field, and that must mean "charge the IP
     * bucket and carry on" — never "refuse" (the same rule the JSON path applies to {@code refresh}
     * bodies) — while the IP dimension keeps its bite.
     */
    @Test
    @DisplayName("a form body with no identity field passes on the IP allowance, which still binds")
    void aFormBodyWithoutIdentityFieldsChargesOnlyTheIp() throws Exception {
        for (int attempt = 1; attempt <= CAPACITY; attempt++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request(paytrNotificationBody(), "198.51.122.7"), response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        }

        MockHttpServletResponse overflow = new MockHttpServletResponse();
        filter.doFilter(request(paytrNotificationBody(), "198.51.122.7"), overflow, new MockFilterChain());

        assertThat(overflow.getStatus())
                .as("no-username must not mean unmetered: the IP allowance is the bound")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    /**
     * The two form parsers' agreement, held to on the MALFORMED-pair input where it used to be
     * false: {@code CachedBodyHttpServletRequest.parseFormBody} drops only the pair that does not
     * URL-decode, while {@code extractFormUsername} wrapped its whole loop in one try — so
     * {@code username=...&x=%zz} reached the handler as a valid username WITH NO USERNAME BUCKET
     * CHARGED. One undecodable junk pair appended to every request was a free multiplier on the
     * username dimension, in exactly the C2 shape ("the filter reads the body more strictly than
     * the framework, and the gap is the bypass"). Negative evidence: against the whole-loop
     * spelling this test's overflow request reaches the chain instead of answering 429.
     */
    @Test
    @DisplayName("a malformed pair alongside a valid username still charges the username bucket")
    void aMalformedPairDoesNotUncountTheUsername() throws Exception {
        String body = "username=malformed-pair-victim&broken=%zz";
        for (int attempt = 1; attempt <= CAPACITY; attempt++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request(body, "198.51.124." + attempt), response, new MockFilterChain());
            assertThat(response.getStatus())
                    .as("attempt %d is inside the allowance", attempt)
                    .isEqualTo(HttpStatus.OK.value());
        }

        MockFilterChain overflowChain = new MockFilterChain();
        MockHttpServletResponse overflow = new MockHttpServletResponse();
        filter.doFilter(request(body, "198.51.124.99"), overflow, overflowChain);

        assertThat(overflow.getStatus())
                .as("the username is readable — the wrapper's parser hands the handler exactly "
                        + "this username — so the filter must charge it: dropping the WHOLE parse "
                        + "over one junk pair makes '&broken=%%zz' a free username-bucket bypass")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(overflowChain.getRequest()).isNull();
    }

    @Test
    @DisplayName("the widening is surgical: an unparseable format (YAML) is still refused fail-closed")
    void anUnparseableFormatIsStillRefused() throws Exception {
        MockHttpServletRequest request = request(paytrNotificationBody(), "198.51.123.1");
        request.setContentType("application/yaml");
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value());
        assertThat(chain.getRequest()).isNull();
    }

    private static MockHttpServletRequest request(String body, String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PAYTR_PATH);
        request.setContentType(MediaType.APPLICATION_FORM_URLENCODED_VALUE);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    private static String paytrNotificationBody() {
        return "merchant_oid=ZP1TESTOID&status=success&total_amount=999&hash=bm90LWEtcmVhbC1oYXNo";
    }

    /** Minimal {@link ObjectProvider}; the production code only ever calls {@code getIfAvailable}. */
    private record StubProvider(RequestMappingHandlerAdapter adapter)
            implements ObjectProvider<RequestMappingHandlerAdapter> {

        @Override
        public RequestMappingHandlerAdapter getObject() throws BeansException {
            return adapter;
        }

        @Override
        public RequestMappingHandlerAdapter getObject(Object... args) throws BeansException {
            return adapter;
        }

        @Override
        public RequestMappingHandlerAdapter getIfAvailable() throws BeansException {
            return adapter;
        }

        @Override
        public RequestMappingHandlerAdapter getIfUnique() throws BeansException {
            return adapter;
        }
    }
}
