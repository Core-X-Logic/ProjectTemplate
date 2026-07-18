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

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F1 — the request body limit that only ever existed on five anonymous paths.
 *
 * <p>{@code zero.ratelimit.max-body-bytes} (16 KB) is applied by {@code RateLimitFilter}, and that
 * filter runs on {@code zero.ratelimit.paths} alone — the five {@code permitAll} endpoints. Every
 * other endpoint in the application had <b>no body bound whatsoever</b>. Tomcat does not impose one
 * on a JSON body ({@code maxPostSize} covers form encoding only), so the size of the byte array
 * Jackson buffers was chosen entirely by the caller.
 *
 * <p><b>Who can drive it.</b> {@code @RequestBody} binding happens during argument resolution, which
 * runs <em>before</em> {@code @PreAuthorize} — the same ordering {@code SaasAuthorizationIT} relies
 * on to prove its 403s are real. So the caller needs no permission at all: a principal holding
 * nothing but a valid token, whose every request ends in 403, still gets its body deserialized in
 * full first. Authentication is the only gate, and on a platform that provisions its own users
 * authentication is not a gate at all.
 *
 * <p><b>Measured against the shipped configuration, before the fix.</b> Recorded as observed rather
 * than as predicted, which is the correction {@code RateLimitBypassIT} had to make once already:
 * <ul>
 *   <li>zero-permission token, 1.5 MB to {@code POST /api/users} → <b>403</b>, the refusal arriving
 *       after the entire body had been read and deserialized.</li>
 *   <li>admin token, same body → <b>409</b>: the request ran all the way through the service layer,
 *       so the padding was carried through binding, validation and a database round trip.</li>
 *   <li>1.5 MB {@code Transfer-Encoding: chunked} → <b>201 CREATED</b>. Measured against an interim
 *       fix that checked {@code Content-Length} only, and the reason
 *       {@link #aChunkedBodyIsBoundedToo} exists: a bound that a caller can switch off with one
 *       header is not a bound, and that is precisely what D1 was.</li>
 * </ul>
 *
 * <p>The allocation size was in every case the caller's to choose. What that buys at scale — heap
 * exhaustion, and the 500-with-stack-trace that E1/E4 drove to zero — depends on the heap and the
 * concurrency, so this class asserts the property that does not: <b>the caller does not get to
 * choose.</b>
 *
 * <p><b>The two layers.</b> The real control belongs at the reverse proxy
 * ({@code client_max_body_size} — see {@code docs/RELEASE-RUNBOOK.md}), which refuses the bytes
 * before they reach a JVM thread at all. This filter is the second layer, for the deployment that
 * lands behind a misconfigured proxy or none: it is the difference between "bounded" and "bounded if
 * someone remembered".
 *
 * <p>Written against the shipped defaults on purpose — no property overrides — because the value
 * that matters is the one an unconfigured production deployment actually runs with.
 */
class RequestBodyLimitIT extends AbstractIntegrationIT {

    private static final String DEFAULT_TENANT = "default";

    /** {@code zero.request.max-body-bytes} default, as shipped in {@code application.yml}. */
    private static final int GLOBAL_LIMIT_BYTES = 1024 * 1024;

    /** {@code zero.ratelimit.max-body-bytes} default — the stricter bound, on five paths. */
    private static final int THROTTLED_LIMIT_BYTES = 16 * 1024;

    /**
     * Comfortably over the global bound and comfortably under Tomcat's {@code maxSwallowSize} (2 MB),
     * so the connection survives long enough for the client to read the 413 rather than being reset
     * mid-write. A reset would also be a refusal, but it would not prove which one this is.
     */
    private static final int OVERSIZED_BYTES = GLOBAL_LIMIT_BYTES + (512 * 1024);

    private static final String POWERLESS_USERNAME = "body-limit-nobody";
    private static final String POWERLESS_PASSWORD = "Password123!";

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

    // --- the finding ------------------------------------------------------

    /**
     * The reported bypass, driven by the principal it was reported for: authenticated, zero
     * permissions, every request otherwise a 403.
     */
    @Test
    void aZeroPermissionCallerCannotPostAnUnboundedBody() {
        HttpHeaders headers = bearerHeaders(powerlessToken(), DEFAULT_TENANT);

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/users", HttpMethod.POST, new HttpEntity<>(oversizedJson(), headers), JsonNode.class);

        assertThat(response.getStatusCode())
                .as("a caller with no permissions still had its body buffered in full before "
                        + "@PreAuthorize ran; the bytes have to be refused, not merely followed by a 403")
                .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertNoErrorLines("a zero-permission caller posting an oversized body");
    }

    /**
     * The bound is a property of the application, not of one endpoint. Every authenticated
     * {@code @RequestBody} route was open the same way, so the assertion sweeps rather than samples —
     * this is the enumeration mistake D3/E2 were each written to stop repeating.
     */
    @Test
    void theBoundAppliesToEveryAuthenticatedBodyEndpoint() {
        HttpHeaders headers = bearerHeaders(
                accessToken(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), DEFAULT_TENANT);
        String payload = oversizedJson();

        for (String path : List.of(
                "/api/users",
                "/api/roles",
                "/api/organization-units",
                "/api/profile/change-password")) {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    path, HttpMethod.POST, new HttpEntity<>(payload, headers), JsonNode.class);

            assertThat(response.getStatusCode())
                    .as("%s accepted an unbounded body. A limit that covers five paths out of the "
                            + "application is a limit on five paths, not on the application", path)
                    .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        }

        assertNoErrorLines("an oversized body on every authenticated write endpoint");
    }

    /**
     * PUT is not a different question from POST, but it is a different code path through the servlet
     * container, and a Content-Length check written against one verb is worth proving against the other.
     */
    @Test
    void theBoundCoversPutAsWellAsPost() {
        HttpHeaders headers = bearerHeaders(
                accessToken(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), DEFAULT_TENANT);
        String payload = oversizedJson();

        for (String path : List.of("/api/users/1", "/api/settings/tenant", "/api/profile")) {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    path, HttpMethod.PUT, new HttpEntity<>(payload, headers), JsonNode.class);

            assertThat(response.getStatusCode())
                    .as("PUT %s accepted an unbounded body", path)
                    .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        }

        assertNoErrorLines("an oversized PUT body");
    }

    // --- the control: the limit must not break the application ------------

    /**
     * The half of this that a "reject everything" fix would also pass. A real request is four orders
     * of magnitude below the bound and has to be completely untouched by it.
     */
    @Test
    void anOrdinaryRequestIsUntouched() {
        HttpHeaders headers = bearerHeaders(
                accessToken(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), DEFAULT_TENANT);

        Map<String, Object> body = Map.of(
                "username", "body-limit-ordinary",
                "email", "body-limit-ordinary@example.com",
                "password", POWERLESS_PASSWORD,
                "roleNames", Set.of("Admin"));

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/users", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);

        assertThat(response.getStatusCode())
                .as("an ordinary create must still work; got %s: %s",
                        response.getStatusCode(), response.getBody())
                .isIn(HttpStatus.CREATED, HttpStatus.OK, HttpStatus.CONFLICT);
        assertNoErrorLines("an ordinary create");
    }

    /**
     * A body just under the bound must pass the filter and be answered by the application, not by
     * the filter. 400/403/409 are all fine here — what must not happen is 413, which would mean the
     * comparison is off by a boundary and legitimate requests are being refused.
     */
    @Test
    void aBodyJustUnderTheBoundStillReachesTheApplication() {
        HttpHeaders headers = bearerHeaders(
                accessToken(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), DEFAULT_TENANT);

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/users", HttpMethod.POST,
                new HttpEntity<>(paddedJson(GLOBAL_LIMIT_BYTES - 4096), headers), JsonNode.class);

        assertThat(response.getStatusCode())
                .as("a body inside the bound belongs to the application, not to the filter")
                .isNotEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertNoErrorLines("a body just under the bound");
    }

    /** A request with no body at all must not be mistaken for a violation. */
    @Test
    void aBodylessRequestIsUntouched() {
        HttpHeaders headers = bearerHeaders(
                accessToken(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), DEFAULT_TENANT);

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/users?page=0&size=1", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertNoErrorLines("an ordinary GET");
    }

    // --- the stricter rule keeps winning ----------------------------------

    /**
     * The regression this fix could most easily cause. {@code /api/auth/login} is anonymous and bounded
     * at 16 KB by {@code RateLimitFilter} (B2), which is 64x stricter than the new global bound. A
     * global filter placed ahead of the limiter — or one that "took over" the check — would quietly
     * raise that path's bound to 1 MB and hand B2 back its 20 KB pad field.
     *
     * <p>The {@code maxBodyBytes} property in the response is what distinguishes the two: it says
     * which of the two rules answered, not merely that something did.
     */
    @Test
    void theThrottledPathsKeepTheirStricterBound() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(TENANT_HEADER, DEFAULT_TENANT);

        Map<String, String> body = new LinkedHashMap<>();
        body.put("usernameOrEmail", "victim-user");
        body.put("password", "definitely-not-the-password");
        // Over the 16 KB throttled bound, far under the 1 MB global one.
        body.put("pad", "A".repeat(20 * 1024));

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/auth/login", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);

        assertThat(response.getStatusCode())
                .as("20 KB is inside the global bound but outside this path's own; the stricter rule "
                        + "has to be the one that answers")
                .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("maxBodyBytes").asInt())
                .as("a 413 quoting %d would mean the global filter answered first and the anonymous "
                        + "paths silently gained 64x the body budget B2 gave them",
                        GLOBAL_LIMIT_BYTES)
                .isEqualTo(THROTTLED_LIMIT_BYTES);
    }

    /** And the anonymous paths must not lose their ordinary traffic to the new filter either. */
    @Test
    void anOrdinaryLoginIsUntouched() {
        assertThat(login(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertNoErrorLines("an ordinary login");
    }

    // --- the branch a header check cannot cover ---------------------------

    /**
     * {@code Transfer-Encoding: chunked} declares no length, so there is no header to check and the
     * filter has to read the body under the bound instead.
     *
     * <p>This is the test that stops the fix being a header check wearing a body limit's name. A
     * {@code Content-Length}-only implementation passes every other assertion in this class and
     * leaves the whole vulnerability reachable by setting one header — which is the exact shape of
     * D1, where the caller's own {@code Content-Type} decided whether the 16 KB bound applied.
     *
     * <p>Driven with the JDK client and a length-less body publisher, because {@code RestTemplate}
     * buffers and therefore always declares a length: the attack could not be expressed through it.
     */
    @Test
    void aChunkedBodyIsBoundedToo() throws Exception {
        String token = accessToken(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD);
        byte[] payload = oversizedJson().getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/users"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(TENANT_HEADER, DEFAULT_TENANT)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        // No length: the JDK client falls back to chunked encoding.
                        .POST(HttpRequest.BodyPublishers.ofInputStream(
                                () -> new ByteArrayInputStream(payload)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode())
                .as("chunked declares no Content-Length, so a header check alone leaves the whole "
                        + "finding reachable by setting one header. Body: %s", response.body())
                .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
        assertThat(response.body()).contains("PAYLOAD_TOO_LARGE");
        assertNoErrorLines("an oversized chunked body");
    }

    /** The same encoding, under the bound, must still be delivered intact to the application. */
    @Test
    void aChunkedBodyUnderTheBoundIsDeliveredIntact() throws Exception {
        String token = accessToken(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD);
        String username = "body-limit-chunked";
        byte[] payload = ("{\"username\":\"" + username + "\",\"email\":\"" + username
                + "@example.com\",\"password\":\"" + POWERLESS_PASSWORD + "\"}")
                .getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/users"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(TENANT_HEADER, DEFAULT_TENANT)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .POST(HttpRequest.BodyPublishers.ofInputStream(
                                () -> new ByteArrayInputStream(payload)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode())
                .as("the filter buffers this body to measure it and must replay every byte of it; a "
                        + "400 here would mean the request reached the controller truncated. Body: %s",
                        response.body())
                .isIn(HttpStatus.CREATED.value(), HttpStatus.OK.value(), HttpStatus.CONFLICT.value());
        assertNoErrorLines("an ordinary chunked create");
    }

    /**
     * The assumption underneath the declared-length fast path, driven rather than asserted in a
     * comment.
     *
     * <p>A request declaring a length within the bound is forwarded <em>unbuffered</em>, because
     * HTTP/1.1 Content-Length framing means the body <em>is</em> exactly that many bytes and whatever
     * follows belongs to the next request on the connection. If that were not so — if the container
     * kept reading past the declared length — then declaring {@code Content-Length: 41} and writing
     * megabytes would walk straight past this filter, and the fix would be decorative.
     *
     * <p>Driven over a raw socket because both {@code RestTemplate} and the JDK client refuse to send
     * a body that disagrees with its own header, which is exactly why neither can express this.
     *
     * <p>What the assertion rests on: the server produces a complete response having consumed only
     * the declared bytes. A container that did not frame the body would still be waiting for more
     * when the read times out, and a container that over-read would have handed the controller
     * corrupted JSON instead of the blank-field validation failure it answers with.
     */
    @Test
    void theContainerFramesTheBodyAtTheDeclaredLength() throws Exception {
        String token = accessToken(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD);
        byte[] declared = "{\"username\":\"\",\"email\":\"\",\"password\":\"\"}"
                .getBytes(StandardCharsets.UTF_8);

        String statusLine;
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(20_000);
            OutputStream out = socket.getOutputStream();
            out.write(("POST /api/users HTTP/1.1\r\n"
                    + "Host: localhost:" + port + "\r\n"
                    + "Authorization: Bearer " + token + "\r\n"
                    + TENANT_HEADER + ": " + DEFAULT_TENANT + "\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Content-Length: " + declared.length + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(declared);
            // Past the declared length, and therefore not part of this request at all. Kept small
            // enough to land in the send buffer in one go: the server answers and closes as soon as
            // it has its 41 bytes, so a larger trailing write would race that close and fail on a
            // reset — which would be the right behaviour observed through a flaky assertion.
            out.write("A".repeat(1024).getBytes(StandardCharsets.UTF_8));
            out.flush();

            statusLine = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
        }

        assertThat(statusLine)
                .as("no response means the container was still waiting for bytes past the declared "
                        + "length, which is the framing the fast path depends on")
                .isNotNull();
        assertThat(statusLine)
                .as("the controller answered the 41-byte body it was framed to receive — three blank "
                        + "fields failing @Valid — not the bytes that followed it. Got: %s", statusLine)
                .contains(" 400 ");
        assertNoErrorLines("a request followed by bytes past its declared Content-Length");
    }

    // --- helpers ----------------------------------------------------------

    private void assertNoErrorLines(String who) {
        assertThat(captured.list)
                .as("%s produced ERROR lines. An oversized body is a client mistake; answering it with "
                        + "a stack trace is how the caller spends the operator's log budget", who)
                .filteredOn(event -> event.getLevel() == Level.ERROR)
                .isEmpty();
        assertThat(captured.list)
                .as("the stack trace is what makes each of these cost kilobytes instead of bytes")
                .allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }

    /** A token for a principal that holds no permission at all — authentication and nothing else. */
    private String powerlessToken() {
        HttpHeaders adminHeaders = bearerHeaders(
                accessToken(DEFAULT_TENANT, SEED_ADMIN_USERNAME, SEED_ADMIN_PASSWORD), DEFAULT_TENANT);

        Map<String, Object> create = new LinkedHashMap<>();
        create.put("username", POWERLESS_USERNAME);
        create.put("email", POWERLESS_USERNAME + "@example.com");
        create.put("password", POWERLESS_PASSWORD);
        create.put("roleNames", Set.of());

        ResponseEntity<JsonNode> created = restTemplate.exchange(
                "/api/users", HttpMethod.POST, new HttpEntity<>(create, adminHeaders), JsonNode.class);
        assertThat(created.getStatusCode())
                .as("arrange step: creating the powerless user must succeed or already have; got %s",
                        created.getBody())
                .isIn(HttpStatus.CREATED, HttpStatus.OK, HttpStatus.CONFLICT);

        return accessToken(DEFAULT_TENANT, POWERLESS_USERNAME, POWERLESS_PASSWORD);
    }

    private String oversizedJson() {
        return paddedJson(OVERSIZED_BYTES);
    }

    /** Structurally valid JSON of approximately {@code totalBytes}, so nothing rejects it earlier. */
    private String paddedJson(int totalBytes) {
        String prefix = "{\"username\":\"pad\",\"email\":\"pad@example.com\","
                + "\"password\":\"Password123!\",\"pad\":\"";
        String suffix = "\"}";
        int padLength = Math.max(0, totalBytes - prefix.length() - suffix.length());
        return prefix + "A".repeat(padLength) + suffix;
    }
}
