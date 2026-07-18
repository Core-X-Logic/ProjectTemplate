package com.mycompanyname.zero.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
 * E1/E4 — the log budget, stated as a number a test can hold.
 *
 * <p>C3, D2 and D3 each closed one exception that had been answering 500 with a full stack trace at
 * {@code ERROR}. E1 found another one — {@code ?sort=;drop} — which is the sharpest of the set so far:
 * <b>29,554 bytes of log per request, 233 stack frames, one ERROR line</b>, and it needs nothing but a
 * valid token. Not a permission, not a role, not a tenant — any authenticated principal, on every
 * paged endpoint in the application ({@code /api/notifications}, {@code /api/users}, {@code /api/roles},
 * {@code /api/audit-logs}).
 *
 * <p>The pattern across four rounds is that fixing the reported spelling leaves the next one open, so
 * this test does not assert on any particular exception. It asserts the property that was actually
 * being violated: <b>a client that can only make mistakes must not be able to write {@code ERROR} lines
 * at all.</b> Anything a future change lets slip back to the fallback fails here, whatever it is called.
 *
 * <p>Zero is the right threshold rather than "fewer". An {@code ERROR} line means an operator is
 * supposed to look; a caller who can manufacture them on demand can bury a real fault in noise, and
 * under prod's JSON logging can spend the log budget as fast as they can send requests.
 *
 * <p>The last test is the control: a genuine unexpected fault must still produce exactly the {@code
 * ERROR} and stack trace it always did. A test that only demands silence would be satisfied by a
 * handler that swallowed everything, which would be a far worse bug than the one being fixed.
 */
class ClientErrorLogBudgetIT extends AbstractIntegrationIT {

    /** No {@code boundary} parameter — malformed by construction. */
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

    private void assertLogBudgetSpent(String who) {
        assertThat(captured.list)
                .as("%s produced ERROR lines. Every one is a stack trace an operator has to read past "
                        + "— and a caller who can produce them on demand can bury a real fault", who)
                .filteredOn(event -> event.getLevel() == Level.ERROR)
                .isEmpty();
        assertThat(captured.list)
                .as("the stack trace is what makes each of these cost kilobytes instead of bytes")
                .allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }

    /**
     * E1 as reported, plus the endpoints it was never reported against. Every paged endpoint in the
     * application takes the same {@code Pageable}, so they were all open at once.
     */
    @Test
    void anInvalidSortPropertyIsABadRequestOnEveryPagedEndpoint() {
        String token = accessToken("default", SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD);
        HttpHeaders headers = bearerHeaders(token, "default");

        for (String path : List.of(
                "/api/notifications?sort=;drop",
                "/api/notifications?sort=nosuchprop,asc",
                "/api/users?sort=;drop",
                "/api/users?sort=nosuchprop",
                "/api/roles?sort=;drop",
                "/api/audit-logs?sort=;drop",
                // The @Query path: no punctuation, so Spring Data passes it through and Hibernate
                // rejects it several frames later. A fix keyed on the punctuation check alone
                // would leave exactly this one answering 500.
                "/api/users?search=a&sort=nosuchprop")) {
            ResponseEntity<String> response = restTemplate.exchange(
                    path, HttpMethod.GET, new HttpEntity<>(headers), String.class);

            assertThat(response.getStatusCode())
                    .as("%s — a sort property that does not exist is a client mistake; this answered "
                            + "500 with 233 stack frames and 29,554 bytes of log. Body: %s",
                            path, response.getBody())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody())
                    .as("the 500 body said INTERNAL, which is a lie to the caller and a false alarm "
                            + "to whoever reads the log")
                    .doesNotContain("INTERNAL");
        }

        assertLogBudgetSpent("an authenticated caller sorting by a property that does not exist");
    }

    /**
     * The response must not reflect the submitted sort property back. Consistent with every other
     * handler in this class: the caller already knows what it sent, and the log is where it belongs.
     */
    @Test
    void theRejectedSortPropertyIsNotEchoedIntoTheResponseBody() {
        String token = accessToken("default", SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD);
        String marker = "zzUniqueSortMarkerzz";

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/notifications?sort=" + marker, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(token, "default")), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .as("client input reflected into a response body is how a 400 handler turns into a "
                        + "gadget; the property goes to the log instead")
                .doesNotContain(marker);
        assertThat(captured.list)
                .as("but it does have to reach the log, or an operator cannot diagnose the caller")
                .anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains(marker));
    }

    /**
     * E4. The budget for a caller holding <em>only</em> a valid token: every client mistake this
     * application can be handed, in one pass, asserted as a single number.
     */
    @Test
    void aLowPrivilegeAuthenticatedClientCannotWriteAnyErrorLinesAtAll() {
        String token = accessToken("default", SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD);
        HttpHeaders auth = bearerHeaders(token, "default");

        HttpHeaders textPlain = new HttpHeaders();
        textPlain.setBearerAuth(token);
        textPlain.set(TENANT_HEADER, "default");
        textPlain.set(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE);

        HttpHeaders multipart = new HttpHeaders();
        multipart.setBearerAuth(token);
        multipart.set(TENANT_HEADER, "default");
        multipart.set(HttpHeaders.CONTENT_TYPE, BOUNDARYLESS_MULTIPART);

        HttpHeaders xml = new HttpHeaders();
        xml.setBearerAuth(token);
        xml.set(TENANT_HEADER, "default");
        xml.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_XML_VALUE);

        for (int round = 0; round < 3; round++) {
            // Broken content type.
            expectClientError(HttpMethod.POST, "/api/users", textPlain, "nope");
            // Boundaryless multipart — resolved before handler mapping, so any path will do.
            expectClientError(HttpMethod.GET, "/api/notifications", multipart, null);
            // Nothing this server can produce.
            expectClientError(HttpMethod.GET, "/api/notifications", xml, null);
            // Unknown path.
            expectClientError(HttpMethod.GET, "/api/no-such-endpoint", auth, null);
            // Wrong method.
            expectClientError(HttpMethod.DELETE, "/api/notifications", auth, null);
            // Invalid sort — E1.
            expectClientError(HttpMethod.GET, "/api/notifications?sort=;drop", auth, null);
            expectClientError(HttpMethod.GET, "/api/users?search=a&sort=nope", auth, null);
            // Invalid path variable.
            expectClientError(HttpMethod.GET, "/api/users/not-a-number", auth, null);
            // Invalid query-parameter type.
            expectClientError(HttpMethod.GET, "/api/audit-logs?minDuration=abc", auth, null);
            // Malformed JSON body.
            expectClientError(HttpMethod.POST, "/api/users", auth, "{ not json");
        }

        assertLogBudgetSpent("30 requests from a caller holding nothing but a valid token");
    }

    /**
     * The same sweep with no credentials at all. An anonymous caller reaches fewer handlers (most of
     * these are 401 before they get anywhere), which is exactly why the assertion is worth making:
     * the paths that <em>do</em> get through must still be silent.
     */
    @Test
    void anAnonymousClientCannotWriteAnyErrorLinesAtAll() throws Exception {
        for (int round = 0; round < 3; round++) {
            send("GET", "/api/localization/languages",
                    HttpHeaders.CONTENT_TYPE, BOUNDARYLESS_MULTIPART);
            send("GET", "/actuator/health", HttpHeaders.CONTENT_TYPE, BOUNDARYLESS_MULTIPART);
            send("GET", "/api/localization/a/b/c");
            send("GET", "/api/localization/languages?sort=;drop");
            send("GET", "/api/localization/languages", HttpHeaders.ACCEPT, MediaType.APPLICATION_XML_VALUE);
            send("GET", "/api/notifications?sort=;drop");
        }

        assertLogBudgetSpent("an anonymous caller");
    }

    /**
     * REGRESSION / control. None of the above may be achieved by making the fallback quieter: a
     * genuinely unexpected exception still has to arrive as a 500 with one {@code ERROR} and its stack
     * trace. Driven through the handler rather than over HTTP, because there is no honest way to make
     * a controller fail internally on demand without adding one that exists only to do so.
     */
    @Test
    void aGenuinelyUnexpectedExceptionStillProducesItsErrorAndStackTrace() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        handler.handleUnexpected(new IllegalStateException("the connection pool is exhausted"));

        assertThat(captured.list)
                .as("a test that only demanded silence would be satisfied by a handler that swallowed "
                        + "everything — which is a worse bug than the one E1 closes")
                .anyMatch(event -> event.getLevel() == Level.ERROR
                        && event.getThrowableProxy() != null);
    }

    private void expectClientError(HttpMethod method, String path, HttpHeaders headers, String body) {
        ResponseEntity<String> response = restTemplate.exchange(
                path, method, new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode().value())
                .as("%s %s should be a client error, not a server fault — got %s: %s",
                        method, path, response.getStatusCode(), response.getBody())
                .isBetween(400, 499);
    }

    /** Raw client so a malformed header reaches the server exactly as written. */
    private void send(String method, String path, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
