package com.mycompanyname.zero.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.mycompanyname.zero.AbstractIntegrationIT;
import com.mycompanyname.zero.shared.web.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D3 — the class, not the two spellings of it.
 *
 * <p>C3 and D2 each closed one exception at a time: a 405 handler, a 415 handler, a 406 handler, a
 * narrow {@code IllegalArgumentException} handler. Every one of those was a real fix and every one
 * left the same hole open, because the hole is not any particular exception. It is that
 * {@code @ExceptionHandler(Exception.class)} sits behind <em>all</em> of Spring's own MVC
 * exceptions, and those exceptions already carry a correct status and need no stack trace. Anything
 * Spring throws that nobody thought to enumerate becomes a 500 with ~180 frames at {@code ERROR}.
 *
 * <p>Two more triggers were found by looking for that shape rather than for more symptoms:
 *
 * <ul>
 *   <li><b>{@code MultipartException}</b> — {@code Content-Type: multipart/form-data} with no
 *       {@code boundary}, on any path at all. It is thrown by {@code DispatcherServlet.checkMultipart}
 *       before handler mapping, so it does not even need an endpoint that reads a body. Live:
 *       {@code GET /actuator/health} with that header answered 500, 181 log lines, no response body,
 *       no credentials. Measured at ~21 KB of log per request — 30 serial requests produced 629 KB in
 *       1.3 s, roughly 42 GB/day from a single client. {@code /actuator/health/**} must stay
 *       {@code permitAll} for the kubelet probe and is not in {@code zero.ratelimit.paths}, so there
 *       is nothing in front of it.</li>
 *   <li><b>{@code NoResourceFoundException}</b> — a 404 surfacing as a 500. Plain
 *       {@code GET /api/localization}, no headers at all, answered {@code 500 {"code":"INTERNAL"}}
 *       with 169 log lines. Anonymous, unthrottled, and reachable in prod.</li>
 * </ul>
 *
 * <p>Both are covered here, but the fix they drove is the general one: any {@code ErrorResponse} —
 * Spring's own interface for "an exception that knows its HTTP status" — is answered with that status
 * and one {@code WARN} line, and only genuinely unexpected exceptions still reach the {@code ERROR}
 * fallback. See {@link GlobalExceptionHandlerFrameworkExceptionTest} for the handler-level contract,
 * including the regression evidence that the fallback still exists.
 */
class FrameworkExceptionContractIT extends AbstractIntegrationIT {

    /** No {@code boundary} parameter — the whole trigger. */
    private static final String BOUNDARYLESS_MULTIPART = "multipart/form-data";

    @LocalServerPort
    private int port;

    private Logger handlerLogger;
    private ListAppender<ILoggingEvent> captured;

    @BeforeEach
    void captureHandlerLog() {
        handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        captured = new ListAppender<>();
        captured.start();
        handlerLogger.addAppender(captured);
    }

    @AfterEach
    void releaseHandlerLog() {
        handlerLogger.detachAppender(captured);
        captured.stop();
    }

    private HttpResponse<String> get(String path, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET();
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * T1. The header alone is the attack; no body, no endpoint that accepts one, no credentials.
     *
     * <p>{@code StandardServletMultipartResolver} decides a request is multipart purely on the
     * {@code Content-Type} prefix, and {@code DispatcherServlet} resolves it before it has even looked
     * for a handler — so this fires on paths that have nothing to do with uploads, including the
     * health probe.
     */
    @Test
    void aBoundarylessMultipartContentTypeAnswers4xxOnPermitAllPaths() throws Exception {
        for (String path : List.of("/actuator/health", "/api/localization/languages")) {
            HttpResponse<String> response = get(path, HttpHeaders.CONTENT_TYPE, BOUNDARYLESS_MULTIPART);

            assertThat(response.statusCode())
                    .as("a malformed multipart header is a client mistake; %s answered 500 with a "
                            + "181-line stack trace and an empty body — got %s", path, response.body())
                    .isBetween(400, 499);
            assertThat(response.body())
                    .as("a 500 here returned nothing at all — the caller could not even tell what "
                            + "it got wrong")
                    .isNotBlank();
        }

        assertThat(captured.list)
                .as("~21 KB of ERROR-level log per request, from an unauthenticated client, on a path "
                        + "that must stay permitAll for the kubelet and has no rate limiter in front "
                        + "of it")
                .noneMatch(event -> event.getLevel() == Level.ERROR);
    }

    /**
     * T2. A 404 is the single most ordinary thing a web server says, and this one was a 500.
     *
     * <p>{@code GET /api/localization} matches neither {@code /languages} nor {@code /{culture}} —
     * it is a bare prefix — so it reaches {@code ResourceHttpRequestHandler} and
     * {@code NoResourceFoundException}. That exception has carried a 404 status of its own since
     * Spring 6.1; the fallback threw it away and replaced it with 500 plus a stack trace.
     */
    @Test
    void anUnmappedPathUnderAPermitAllPrefixAnswers404WithoutAStackTrace() throws Exception {
        for (String path : List.of("/api/localization", "/api/localization/a/b/c")) {
            HttpResponse<String> response = get(path);

            assertThat(response.statusCode())
                    .as("%s is a path that does not exist, not a server fault — got %s",
                            path, response.body())
                    .isEqualTo(HttpStatus.NOT_FOUND.value());
            assertThat(response.body())
                    .as("the 500 body said INTERNAL, which is a lie to the caller and a false alarm "
                            + "to whoever reads the log")
                    .doesNotContain("INTERNAL");
        }

        assertThat(captured.list)
                .as("anonymous, unthrottled, and one stack trace per request: the cheapest "
                        + "log-flooding primitive in the application")
                .noneMatch(event -> event.getLevel() == Level.ERROR);
    }

    /**
     * The measurement that makes this an availability finding rather than a tidiness one. The fix is
     * only worth the name if the ERROR count is zero, not merely lower.
     */
    @Test
    void anAnonymousClientCannotWriteAnyErrorLinesAtAll() throws Exception {
        for (int i = 0; i < 10; i++) {
            get("/api/localization", HttpHeaders.CONTENT_TYPE, BOUNDARYLESS_MULTIPART);
            get("/actuator/health", HttpHeaders.CONTENT_TYPE, BOUNDARYLESS_MULTIPART);
            get("/api/localization/a/b/c");
        }

        assertThat(captured.list)
                .as("30 requests produced 5430 log lines and 629 KB before the fix; the budget an "
                        + "anonymous caller is entitled to spend is zero")
                .filteredOn(event -> event.getLevel() == Level.ERROR)
                .isEmpty();
        assertThat(captured.list)
                .as("no stack trace belongs on any of these — the trace is what makes each request "
                        + "cost kilobytes instead of bytes")
                .allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }

    /**
     * The rest of the family, driven over the wire in one pass. None of these were reported; they are
     * here because they are the same class of defect and the point of D3 is to close the class.
     */
    @Test
    void theRemainingStandardMvcExceptionsAnswerTheirOwnStatusWithoutAStackTrace() {
        String token = accessToken("default", SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD);

        assertThat(exchange(HttpMethod.PUT, "/api/auth/login", jsonHeaders(), null).getStatusCode())
                .as("wrong method")
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);

        HttpHeaders textHeaders = new HttpHeaders();
        textHeaders.set(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE);
        textHeaders.set(TENANT_HEADER, "default");
        assertThat(exchange(HttpMethod.POST, "/api/auth/login", textHeaders, "nope").getStatusCode())
                .as("unsupported media type")
                .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);

        HttpHeaders xmlHeaders = new HttpHeaders();
        xmlHeaders.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_XML_VALUE);
        assertThat(exchange(HttpMethod.GET, "/api/localization/languages", xmlHeaders, null)
                .getStatusCode())
                .as("unacceptable Accept")
                .isEqualTo(HttpStatus.NOT_ACCEPTABLE);

        assertThat(exchange(HttpMethod.POST, "/api/auth/login", jsonHeaders(), "{ not json")
                .getStatusCode())
                .as("malformed JSON body")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        HttpHeaders authHeaders = bearerHeaders(token, "default");
        assertThat(exchange(HttpMethod.GET, "/api/users/not-a-number", authHeaders, null)
                .getStatusCode())
                .as("a path variable that cannot be converted to Long is a client mistake, not an "
                        + "internal failure")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange(HttpMethod.GET, "/api/audit-logs?minDuration=abc", authHeaders, null)
                .getStatusCode())
                .as("query parameter type mismatch")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(captured.list)
                .as("every one of these is a client mistake with a status of its own; none of them "
                        + "is worth a stack trace")
                .noneMatch(event -> event.getLevel() == Level.ERROR);
    }

    /**
     * The control. Handing framework exceptions their own statuses must not have widened into
     * intercepting Spring Security's — those are rethrown so the filter chain writes 401/403 itself,
     * and swallowing them would turn every authorization failure into a 500.
     */
    @Test
    void securityExceptionsStillProduceTheirOwnStatuses() {
        ResponseEntity<JsonNode> anonymous = restTemplate.exchange(
                "/api/users", HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), JsonNode.class);
        assertThat(anonymous.getStatusCode())
                .as("no credentials is a 401, and the exception handler must keep its hands off it")
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        String token = accessToken("default", SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD);
        ResponseEntity<JsonNode> crossTenant = restTemplate.exchange(
                "/api/tenants", HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(token, "default")), JsonNode.class);
        assertThat(crossTenant.getStatusCode())
                .as("a tenant admin reaching a host-only endpoint is a 403 — if this became a 500 the "
                        + "fix would have broken authorization reporting")
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(captured.list).noneMatch(event -> event.getLevel() == Level.ERROR);
    }

    private ResponseEntity<String> exchange(HttpMethod method, String path, HttpHeaders headers,
                                            String body) {
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(TENANT_HEADER, "default");
        return headers;
    }
}
