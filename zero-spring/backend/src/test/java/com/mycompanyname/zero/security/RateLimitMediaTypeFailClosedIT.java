package com.mycompanyname.zero.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mycompanyname.zero.AbstractIntegrationIT;
import com.mycompanyname.zero.config.RateLimitFilter;
import com.mycompanyname.zero.identity.web.dto.LoginRequest;
import com.mycompanyname.zero.shared.web.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live-reproduction evidence for D1 and D2 — the throttle's media-type allowlist was fail-OPEN, and
 * a wildcard {@code Content-Type} crashed the request before any handler could answer it.
 *
 * <p><b>D1, the architectural fault.</b> {@code RateLimitFilter.carriesJson()} asked "does this look
 * like JSON?" and, when the answer was no, skipped <em>both</em> halves of the limiter's work: the
 * body-size refusal and the username bucket. That is an allowlist guarding a security control, and
 * an allowlist is only as complete as its author's inventory of the classpath. The inventory was
 * wrong: {@code springdoc-openapi-starter-webmvc-ui} drags in {@code jackson-dataformat-yaml}, Boot
 * auto-registers {@code MappingJackson2YamlHttpMessageConverter} for it, and YAML 1.2 is a superset
 * of JSON — so the byte-for-byte identical login body, relabelled {@code application/yaml}, bound to
 * {@link LoginRequest} and reached bcrypt while the limiter looked away. Turning springdoc off in
 * prod does not remove the converter.
 *
 * <p>Live, before the fix: at capacity 3, ten of ten {@code application/yaml} requests against one
 * account with a rotating {@code X-Forwarded-For} answered {@code 401 LOGIN_FAILED} — no limit in
 * force in either dimension. The same body as {@code application/json} was refused on the fourth.
 * A 20 KB body answered 400 under YAML and 413 under JSON, so the size bound was gone too, and
 * eight {@code /api/account/forgot-password} sweeps answered 204 apiece — an account/tenant
 * enumeration oracle with no throttle on it.
 *
 * <p><b>Why the previous fixes did not hold.</b> B2 was closed for oversized bodies, then C1
 * reopened it through {@code +json} suffixes and was closed again by widening the allowlist. This
 * is the third round of the same fault, so the test is deliberately not written as a third list of
 * spellings. {@link #theApplicationDeserializesLoginBodiesFromMoreThanJson} asks the
 * <em>application</em> which media types it will deserialize a login body from — via
 * {@code RequestMappingHandlerAdapter.getMessageConverters()} — and the matrices below are driven
 * from that answer. A format that lands on the classpath tomorrow shows up in this test by itself,
 * which is the property the hand-written matrix in {@link RateLimitContentTypeBypassIT} lacked (D5).
 *
 * <p><b>D2.</b> <code>Content-Type: &#42;/&#42;</code> and {@code application/*} parse as valid media
 * types but are rejected by {@code HttpHeaders.setContentType}, which {@code ServletServerHttpRequest
 * .getHeaders()} calls while resolving the {@code @RequestBody} argument — before the handler runs,
 * so the 415 handler never sees it and the {@code Exception} fallback turns it into a 500 with a
 * stack trace at ERROR. Anonymous, unauthenticated, one request per stack trace.
 *
 * <p>Shares {@link RateLimitBypassIT}'s context configuration verbatim so Spring reuses the cached
 * context instead of booting another one.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "zero.ratelimit.enabled=true",
                "zero.ratelimit.capacity=2",
                "zero.ratelimit.refill-period=PT1M",
                "zero.ratelimit.trusted-proxy-count=1"
        })
class RateLimitMediaTypeFailClosedIT extends AbstractIntegrationIT {

    private static final int CAPACITY = 2;
    private static final String LOGIN_PATH = "/api/auth/login";
    private static final String FORGOT_PATH = "/api/account/forgot-password";
    private static final String CONFIRM_EMAIL_PATH = "/api/account/confirm-email";

    /** ~20 KB, comfortably past the 16 KB inspection bound — the pad from the live reproduction. */
    private static final String PAD = "A".repeat(20 * 1024);

    /**
     * Spellings the report exercised by hand, kept <em>in addition to</em> the derived set rather
     * than instead of it. The derived set covers what the application can read; these cover what an
     * attacker can type, including types no converter claims.
     */
    private static final List<String> HAND_WRITTEN_SPELLINGS = List.of(
            "application/yaml", "application/x-yaml", "text/yaml", "application/x-foo");

    /**
     * Driven with the JDK client throughout. {@code RestTemplate} parses {@code Content-Type} into a
     * {@code MediaType} before writing, so an invalid spelling would blow up in the test rather than
     * on the wire — and the invalid spellings are precisely what is under test.
     */
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    private RateLimitFilter rateLimitFilter;

    @Autowired
    private RequestMappingHandlerAdapter handlerAdapter;

    @LocalServerPort
    private int port;

    private Logger handlerLogger;
    private ListAppender<ILoggingEvent> captured;

    @BeforeEach
    void clearBucketsAndCaptureLog() {
        rateLimitFilter.reset();
        handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        captured = new ListAppender<>();
        captured.start();
        handlerLogger.addAppender(captured);
    }

    @AfterEach
    void releaseLog() {
        handlerLogger.detachAppender(captured);
        captured.stop();
    }

    // --- D5: the matrix, asked of the application itself -------------------

    /**
     * The premise every other test here rests on, asserted rather than assumed. This is the question
     * the filter should have been asking all along: not "does this spelling look like JSON?" but
     * "will this application deserialize a request body from it?".
     *
     * <p>It passes against the vulnerable code too — it describes the application, not the filter.
     * That is the point: the fact was always visible from inside the process, and the limiter simply
     * never consulted it.
     */
    @Test
    void theApplicationDeserializesLoginBodiesFromMoreThanJson() {
        Set<MediaType> readable = readableLoginBodyMediaTypes();

        assertThat(readable)
                .as("the baseline: JSON is readable, or every assumption below is wrong")
                .contains(MediaType.APPLICATION_JSON);
        assertThat(nonJson(readable))
                .as("this test exists because the readable set is wider than JSON — springdoc pulls "
                        + "in jackson-dataformat-yaml and Boot registers a converter for it. If this "
                        + "is ever empty the D1 threat model has changed and the matrices below are "
                        + "no longer proving anything")
                .isNotEmpty();
    }

    // --- D1: fail closed on anything the limiter cannot account for -------

    /**
     * The headline finding. Every request below comes from a fresh address, so the IP bucket cannot
     * stop any of them; the username bucket is the only control left, and the limiter cannot charge
     * it because it cannot read the body. A body the limiter cannot account for therefore must not
     * reach the credential check at all.
     *
     * <p>Live before the fix, with {@code application/yaml}: ten of ten answered
     * {@code 401 LOGIN_FAILED}.
     */
    @Test
    void aBodyFormatTheLimiterCannotParseNeverReachesTheCredentialCheck() {
        int address = 0;
        for (String contentType : formatsUnderTest()) {
            for (int attempt = 1; attempt <= CAPACITY * 3; attempt++) {
                HttpResponse<String> response = post(
                        LOGIN_PATH, contentType, "198.51.101." + (++address),
                        loginBody("fail-closed-victim"));

                assertThat(response.body())
                        .as("%s attempt %d: the limiter cannot charge a username bucket for a body "
                                + "it cannot parse, and every request here comes from a different "
                                + "address — reaching bcrypt means no limit is in force at all",
                                contentType, attempt)
                        .doesNotContain("LOGIN_FAILED");
                assertThat(response.statusCode())
                        .as("%s attempt %d must be refused by the limiter, not handled", contentType, attempt)
                        .isIn(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(),
                                HttpStatus.PAYLOAD_TOO_LARGE.value(),
                                HttpStatus.TOO_MANY_REQUESTS.value());
            }
        }
    }

    /**
     * D5, stated as one invariant over the <em>whole</em> derived set rather than as a list of
     * spellings: whatever the application can deserialize a login body from, an attacker must not be
     * able to spend more than the allowance on one account through it. Every request below targets
     * the same victim from a different address, so the username dimension is the only one that can
     * stop the last one.
     *
     * <p>The two halves of the fix show up as two different refusals, and the test accepts either —
     * what it does not accept is the credential check running. JSON is <em>counted</em> (the limiter
     * parses it, charges the username bucket, and answers 429 past capacity); everything else is
     * <em>refused</em> (the limiter cannot read an identity from it, so it never reaches a handler).
     * Both are "the limit is in force"; only reaching bcrypt uncounted is not.
     *
     * <p>This is the test that would have caught D1 on the day the YAML converter arrived, and it is
     * the reason the matrix is derived: a format added to the classpath next year is covered here
     * without anybody remembering to add it.
     */
    @Test
    void everyFormatTheApplicationReadsIsEitherCountedOrRefused() {
        int address = 0;
        for (MediaType mediaType : readableLoginBodyMediaTypes()) {
            String victim = "derived-victim-" + mediaType.getSubtype();
            String contentType = mediaType.toString();

            for (int attempt = 1; attempt <= CAPACITY; attempt++) {
                post(LOGIN_PATH, contentType, "198.51.102." + (++address), loginBody(victim));
            }

            HttpResponse<String> overflow = post(
                    LOGIN_PATH, contentType, "198.51.102." + (++address), loginBody(victim));

            assertThat(overflow.body())
                    .as("%s: %d attempts on one account from %d different addresses have already been "
                            + "made — reaching the credential check again means this format is a way "
                            + "to spray one account without limit", contentType, CAPACITY, CAPACITY)
                    .doesNotContain("LOGIN_FAILED");
            assertThat(overflow.statusCode())
                    .as("%s must be either counted (429) or refused (415/413), never served", contentType)
                    .isIn(HttpStatus.TOO_MANY_REQUESTS.value(),
                            HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(),
                            HttpStatus.PAYLOAD_TOO_LARGE.value());
        }
    }

    /**
     * D1(a). The size bound is a property of the endpoint, not of the label on the body: a request
     * whose {@code Content-Type} the limiter does not recognise used to skip the bound entirely.
     * Live before the fix: the same 20 KB body answered 413 as {@code application/json} and 400 as
     * {@code application/yaml} — the second one having been parsed in full first.
     */
    @Test
    void theBodySizeBoundAppliesWhateverTheBodyIsLabelled() {
        int address = 0;
        for (String contentType : formatsUnderTest()) {
            HttpResponse<String> response = post(
                    LOGIN_PATH, contentType, "198.51.103." + (++address), oversizedLoginBody());

            assertThat(response.statusCode())
                    .as("%s: a 20 KB body on an endpoint that takes two short strings must be "
                            + "refused for its size no matter what it claims to be", contentType)
                    .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
        }
    }

    /**
     * The same gap with no {@code Content-Type} at all. {@code HttpErrorContractIT} already proves
     * such a body cannot reach the controller; this proves the limiter refuses it on its own terms
     * rather than relying on the framework to do so — "the framework surely rejects that" is exactly
     * the reasoning that left the {@code +json} suffixes open in C1.
     */
    @Test
    void aBodyWithNoContentTypeIsStillBounded() {
        HttpResponse<String> response = post(LOGIN_PATH, null, "198.51.104.1", oversizedLoginBody());

        assertThat(response.statusCode())
                .as("an unlabelled 20 KB body is still a 20 KB body")
                .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
    }

    /**
     * The enumeration amplifier. {@code /api/account/forgot-password} answers 204 whether or not the
     * account exists — deliberately — but that only holds as a privacy property while the endpoint
     * is throttled. Behind an unreadable media type it was not: eight of eight sweeps from eight
     * addresses answered 204, which is an unmetered oracle for probing accounts and tenant names.
     */
    @Test
    void forgotPasswordCannotBeSweptBehindAnUnparseableMediaType() {
        for (int attempt = 1; attempt <= 8; attempt++) {
            HttpResponse<String> response = post(
                    FORGOT_PATH, "application/yaml", "198.51.105." + attempt,
                    "{\"usernameOrEmail\":\"sweep-probe-" + attempt + "\",\"tenant\":\"default\"}");

            assertThat(response.statusCode())
                    .as("sweep %d: a 204 here is the endpoint doing its work uncounted", attempt)
                    .isNotEqualTo(HttpStatus.NO_CONTENT.value());
        }
    }

    /**
     * The control that keeps the fix from being "refuse everything". Over-refusing would be a
     * self-inflicted outage on the platform's most important endpoint, and it would look exactly
     * like a passing security test.
     */
    @Test
    void ordinaryJsonStillReachesTheCredentialCheck() {
        HttpResponse<String> response = post(
                LOGIN_PATH, MediaType.APPLICATION_JSON_VALUE, "198.51.106.1", loginBody("control-user"));

        assertThat(response.statusCode())
                .as("the fix must not break the endpoint it protects")
                .isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(response.body()).contains("LOGIN_FAILED");
    }

    /**
     * The other half of that control, and the one easy to get wrong: a body the limiter <em>can</em>
     * read but which carries no username field at all is perfectly legitimate — {@code refresh},
     * {@code reset-password} and {@code confirm-email} all look like this. "No username found" must
     * mean "charge the IP bucket and carry on", never "refuse".
     */
    @Test
    void aJsonBodyWithNoUsernameFieldIsNotRefused() {
        HttpResponse<String> response = post(
                "/api/auth/refresh", MediaType.APPLICATION_JSON_VALUE, "198.51.106.2",
                "{\"refreshToken\":\"not-a-valid-token\"}");

        assertThat(response.statusCode())
                .as("a JSON body without a username is ordinary traffic, not a bypass attempt")
                .isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    // --- D2: wildcard Content-Type ----------------------------------------

    /**
     * <code>&#42;/&#42;</code> and {@code application/*} are valid media types to
     * {@code MediaType.parseMediaType} and illegal to
     * {@code HttpHeaders.setContentType}, which the {@code @RequestBody} argument
     * resolver reaches through {@code ServletServerHttpRequest.getHeaders()}. The
     * {@code IllegalArgumentException} is thrown before the handler is invoked, so the 415 handler
     * never sees it and the {@code Exception} fallback answers 500 — writing a full stack trace at
     * ERROR for an anonymous caller, once per request, as fast as it cares to ask.
     */
    @Test
    void aWildcardContentTypeIsRefusedWithoutWritingAStackTrace() {
        int address = 0;
        for (String contentType : List.of("*/*", "application/*")) {
            HttpResponse<String> response = post(
                    LOGIN_PATH, contentType, "198.51.107." + (++address), loginBody("wildcard-probe"));

            assertThat(response.statusCode())
                    .as("%s is a client mistake, not an internal failure", contentType)
                    .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value());
            assertThat(response.body())
                    .as("%s must not reach the credential check either", contentType)
                    .doesNotContain("LOGIN_FAILED");
        }

        assertThat(captured.list)
                .as("an anonymous caller able to write ERROR-level stack traces on demand buries "
                        + "the faults that are real, and on prod's JSON logging it is a cheap way to "
                        + "spend the log budget")
                .noneMatch(event -> event.getLevel() == Level.ERROR);
    }

    /** An outright malformed type takes the same road: refused here, never handed to the framework. */
    @Test
    void anInvalidContentTypeIsRefusedWithoutWritingAStackTrace() {
        HttpResponse<String> response = post(
                LOGIN_PATH, "application/", "198.51.108.1", loginBody("invalid-type-probe"));

        assertThat(response.statusCode())
                .as("an unparseable media type cannot be honoured, so it must be refused")
                .isIn(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(), HttpStatus.BAD_REQUEST.value());
        assertThat(response.body()).doesNotContain("LOGIN_FAILED");
        assertThat(captured.list).noneMatch(event -> event.getLevel() == Level.ERROR);
    }

    // --- D3: the throttled-path list ---------------------------------------

    /**
     * {@code /api/account/confirm-email} is {@code permitAll}, takes a {@code @RequestBody}, and hits
     * the database on every call — and it was absent from {@code zero.ratelimit.paths}. Live: eight
     * of eight from one fixed address were served. Anonymous, unmetered database load, and a free
     * channel for guessing confirmation codes.
     */
    @Test
    void confirmEmailIsThrottledLikeEveryOtherAnonymousBodyEndpoint() {
        String clientIp = "198.51.109.1";

        for (int attempt = 1; attempt <= CAPACITY; attempt++) {
            assertThat(post(CONFIRM_EMAIL_PATH, MediaType.APPLICATION_JSON_VALUE, clientIp,
                    "{\"code\":\"no-such-code-" + attempt + "\"}").statusCode())
                    .as("attempt %d is inside the allowance", attempt)
                    .isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        }

        assertThat(post(CONFIRM_EMAIL_PATH, MediaType.APPLICATION_JSON_VALUE, clientIp,
                "{\"code\":\"no-such-code-overflow\"}").statusCode())
                .as("an anonymous endpoint that touches the database on every call has to be metered")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    /**
     * Every remaining anonymous {@code @RequestBody} endpoint, checked the same way. Listing them by
     * hand is what let {@code confirm-email} slip; this at least fails loudly if one of the known
     * ones is dropped from the configuration.
     */
    @Test
    void everyAnonymousBodyEndpointIsThrottled() {
        List<String> paths = List.of(
                LOGIN_PATH, "/api/auth/refresh", FORGOT_PATH, "/api/account/reset-password",
                CONFIRM_EMAIL_PATH);
        int address = 0;

        for (String path : paths) {
            String clientIp = "198.51.110." + (++address);
            HttpStatus last = null;
            for (int attempt = 0; attempt <= CAPACITY; attempt++) {
                last = HttpStatus.valueOf(post(path, MediaType.APPLICATION_JSON_VALUE, clientIp, "{}")
                        .statusCode());
            }
            assertThat(last)
                    .as("%s is anonymous and takes a body, so it must be in zero.ratelimit.paths", path)
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    // --- helpers ----------------------------------------------------------

    /**
     * D5. The media types this application will actually bind a {@link LoginRequest} from, asked of
     * the converters the {@code RequestMappingHandlerAdapter} is configured with — the same list the
     * argument resolver consults. Wildcard entries such as {@code application/*+json} are dropped
     * because they cannot be sent as a {@code Content-Type}; the concrete ones are what an attacker
     * can actually put on the wire.
     */
    private Set<MediaType> readableLoginBodyMediaTypes() {
        Set<MediaType> readable = new LinkedHashSet<>();
        handlerAdapter.getMessageConverters().forEach(converter ->
                converter.getSupportedMediaTypes().stream()
                        .filter(MediaType::isConcrete)
                        .filter(mediaType -> converter.canRead(LoginRequest.class, mediaType))
                        .forEach(readable::add));
        return readable;
    }

    private static Set<MediaType> nonJson(Set<MediaType> mediaTypes) {
        Set<MediaType> result = new LinkedHashSet<>(mediaTypes);
        result.removeIf(mediaType -> MediaType.APPLICATION_JSON.isCompatibleWith(mediaType)
                || mediaType.getSubtype().endsWith("+json"));
        return result;
    }

    /**
     * The derived set first, then the hand-written spellings the report used. Derivation is what
     * makes the test survive a new converter appearing on the classpath; the hand-written entries
     * cover the types no converter claims, which must be refused just the same.
     */
    private List<String> formatsUnderTest() {
        List<String> formats = new ArrayList<>();
        nonJson(readableLoginBodyMediaTypes()).forEach(mediaType -> formats.add(mediaType.toString()));
        HAND_WRITTEN_SPELLINGS.stream().filter(spelling -> !formats.contains(spelling)).forEach(formats::add);
        return formats;
    }

    private static String loginBody(String username) {
        return "{\"usernameOrEmail\":\"" + username + "\",\"password\":\"definitely-not-the-password\"}";
    }

    private static String oversizedLoginBody() {
        return "{\"usernameOrEmail\":\"victim-big\",\"password\":\"x\",\"pad\":\"" + PAD + "\"}";
    }

    private HttpResponse<String> post(String path, String contentType, String forwardedFor, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header(TENANT_HEADER, "default")
                .header("X-Forwarded-For", forwardedFor)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (contentType != null) {
            request.header(HttpHeaders.CONTENT_TYPE, contentType);
        }
        try {
            return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new IllegalStateException("request to " + path + " failed", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while calling " + path, ex);
        }
    }
}
