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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Evidence for C3 — an ordinary client mistake on an unauthenticated endpoint is not a system fault.
 *
 * <p>{@code GlobalExceptionHandler} had no handler for
 * {@code HttpRequestMethodNotSupportedException} or {@code HttpMediaTypeNotSupportedException}, so
 * both fell through to the {@code @ExceptionHandler(Exception.class)} fallback. Live:
 * {@code PUT /api/auth/login} answered {@code 500 {"code":"INTERNAL"}} instead of 405, and
 * {@code Content-Type: text/plain} answered 500 instead of 415 — each writing a full stack trace at
 * {@code ERROR}.
 *
 * <p>Two costs, and the logging one is the larger. Wrong status codes mislead clients; but anyone at
 * all, holding no credentials, could drive that loop and fill the log with stack traces — the noise
 * buries genuine faults, and on a JSON-logging prod setup it is a cheap way to spend the log budget.
 * A 500 also means every real fault now has to be told apart from a stream of fake ones.
 *
 * <p>The assertion on the captured log is the point of this class, not decoration: the status codes
 * alone could be fixed while still logging at ERROR with a trace.
 *
 * <p>406 is covered here too. It was not in the report; it was found by driving the 405/415 fix and
 * asking what else lands in the same fallback, and it turned out to be the worst of the three —
 * see {@link #anUnsatisfiableAcceptHeaderAnswers406OnAnUnthrottledAnonymousEndpoint}.
 *
 * <p>Runs in the default shared integration context deliberately — a wrong method needs no special
 * configuration, and a dedicated context would cost a boot for nothing.
 */
class HttpErrorContractIT extends AbstractIntegrationIT {

    private static final String LOGIN_PATH = "/api/auth/login";

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

    /**
     * {@code PATCH} is left out only because {@code TestRestTemplate}'s default
     * {@code HttpURLConnection}-backed factory cannot send it; it reaches the identical handler
     * mapping failure and is covered by the same fix.
     */
    @Test
    void anUnsupportedMethodOnLoginAnswers405() {
        for (HttpMethod method : List.of(HttpMethod.PUT, HttpMethod.DELETE)) {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    LOGIN_PATH, method, new HttpEntity<>(jsonHeaders()), JsonNode.class);

            assertThat(response.getStatusCode())
                    .as("%s on an endpoint that only accepts POST is a client mistake, not an "
                            + "internal failure", method)
                    .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().path("code").asText()).isEqualTo("METHOD_NOT_ALLOWED");
            assertThat(response.getHeaders().getFirst(HttpHeaders.ALLOW))
                    .as("a 405 has to say what is allowed instead")
                    .contains("POST");
        }
    }

    @Test
    void anUnsupportedContentTypeOnLoginAnswers415() {
        for (String contentType : List.of(
                MediaType.TEXT_PLAIN_VALUE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)) {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.CONTENT_TYPE, contentType);
            headers.set(TENANT_HEADER, "default");

            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    LOGIN_PATH, HttpMethod.POST,
                    new HttpEntity<>("usernameOrEmail=admin&password=whatever", headers), JsonNode.class);

            assertThat(response.getStatusCode())
                    .as("%s: the endpoint reads JSON and nothing else — that is a 415", contentType)
                    .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().path("code").asText()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        }
    }

    /**
     * The half that matters operationally. An unauthenticated caller must not be able to write stack
     * traces into the log at will, one per request, for as long as it cares to keep asking.
     */
    @Test
    void expectedClientMistakesDoNotWriteStackTracesAtErrorLevel() {
        restTemplate.exchange(LOGIN_PATH, HttpMethod.PUT,
                new HttpEntity<>(jsonHeaders()), JsonNode.class);
        restTemplate.exchange(LOGIN_PATH, HttpMethod.DELETE,
                new HttpEntity<>(jsonHeaders()), JsonNode.class);

        HttpHeaders textHeaders = new HttpHeaders();
        textHeaders.set(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE);
        textHeaders.set(TENANT_HEADER, "default");
        restTemplate.exchange(LOGIN_PATH, HttpMethod.POST,
                new HttpEntity<>("not json", textHeaders), JsonNode.class);

        assertThat(captured.list)
                .as("an anonymous caller writing ERROR-level stack traces on demand is a log-flooding "
                        + "primitive, and it buries the faults that are real")
                .noneMatch(event -> event.getLevel() == Level.ERROR);
    }

    /**
     * The control that keeps the fix honest: the handlers added for 405/415 must not have swallowed
     * the ordinary path. A correct POST still reaches the credential check.
     */
    @Test
    void aWellFormedLoginStillReachesTheCredentialCheck() {
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                LOGIN_PATH, HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "usernameOrEmail", "no-such-account",
                        "password", "definitely-not-the-password"), jsonHeaders()),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("LOGIN_FAILED");
    }

    /**
     * Found while verifying the 405/415 fix, and the reason this class also covers 406.
     * {@code Accept: application/xml} produced the same {@code 500 {"code":"INTERNAL"}} with a stack
     * trace at ERROR — on {@code /api/localization/**}, which is anonymous and, unlike the login
     * endpoints, <em>not</em> in {@code zero.ratelimit.paths}. No throttle bounds how fast that loop
     * can be driven, so it is the strongest of the three log-flooding primitives and the one that
     * would have survived a fix written only to the two reported exceptions.
     */
    @Test
    void anUnsatisfiableAcceptHeaderAnswers406OnAnUnthrottledAnonymousEndpoint() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_XML_VALUE);

        ResponseEntity<JsonNode> response = restTemplate.exchange("/api/localization/languages",
                HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);

        assertThat(response.getStatusCode())
                .as("asking for a representation this API does not produce is a client mistake")
                .isEqualTo(HttpStatus.NOT_ACCEPTABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("NOT_ACCEPTABLE");
        assertThat(captured.list)
                .as("this endpoint is anonymous AND unthrottled — a stack trace per request here is "
                        + "an unbounded way to fill the log")
                .noneMatch(event -> event.getLevel() == Level.ERROR);
    }

    /**
     * The remaining way a body could reach the credential check without the rate limiter having
     * recognised it as JSON: send no {@code Content-Type} at all. It cannot — Spring defaults an
     * absent content type to {@code application/octet-stream}, which no converter here reads — and
     * this asserts that rather than assuming it, because "the framework surely rejects that" is the
     * reasoning that left the {@code +json} suffixes open in the first place (C1).
     *
     * <p>Driven with the JDK client because {@code RestTemplate} always sets a content type when a
     * converter writes the body, so the case under test could not be expressed through it.
     */
    @Test
    void aBodyWithNoContentTypeNeverReachesTheCredentialCheck() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + LOGIN_PATH))
                        .header(TENANT_HEADER, "default")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"usernameOrEmail\":\"no-content-type-probe\",\"password\":\"x\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode())
                .as("the rate limiter skips username extraction when there is no content type, so "
                        + "the controller must not accept the body either — got %s", response.body())
                .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value());
        assertThat(response.body()).doesNotContain("LOGIN_FAILED");
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(TENANT_HEADER, "default");
        return headers;
    }
}
