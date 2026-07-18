package com.mycompanyname.zero.shared.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D2. The narrow {@code IllegalArgumentException} handler, and the line it must not cross.
 *
 * <p>A wildcard {@code Content-Type} never reaches a handler: {@code HttpHeaders.setContentType}
 * rejects it from inside {@code ServletServerHttpRequest.getHeaders()} while the {@code @RequestBody}
 * argument is being resolved, so {@code HttpMediaTypeNotSupportedException} is never thrown and the
 * existing 415 handler never sees it. The {@code Exception} fallback answered 500 with a stack trace
 * at ERROR instead — on every {@code @RequestBody} endpoint in the application, not merely the five
 * the rate limiter covers.
 *
 * <p>The obvious fix — {@code @ExceptionHandler(IllegalArgumentException.class)} returning 415 — is
 * worse than the bug. {@code IllegalArgumentException} is what a service throws when an invariant it
 * was given fails, and a blanket handler would quietly relabel every one of those as a client error:
 * a 415 to the caller, nothing above WARN in the log, and no stack trace to debug from. So the
 * handler matches on the message and delegates everything else back to the fallback, unchanged.
 *
 * <p>Exercised against the handler directly rather than over HTTP. The wildcard case is proved
 * end-to-end in {@code HttpErrorContractIT} and {@code RateLimitMediaTypeFailClosedIT}; what needs
 * pinning here is the <em>other</em> branch, and there is no honest way to make a controller throw a
 * genuine internal {@code IllegalArgumentException} over the wire without adding a controller that
 * exists only to do so.
 */
class GlobalExceptionHandlerIllegalArgumentTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private Logger handlerLogger;
    private ListAppender<ILoggingEvent> captured;

    @BeforeEach
    void captureLog() {
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

    /**
     * The exception Spring actually throws, produced by the code that actually throws it, so this
     * test breaks if a Spring upgrade changes the wording the handler keys on. Constructing the
     * message by hand would defeat the point.
     */
    private static IllegalArgumentException wildcardRejection(String contentType) {
        try {
            new HttpHeaders().setContentType(MediaType.parseMediaType(contentType));
            throw new AssertionError("HttpHeaders.setContentType accepted " + contentType + "; the "
                    + "D2 fault has changed shape and this handler needs revisiting");
        } catch (IllegalArgumentException ex) {
            return ex;
        }
    }

    /**
     * The two wildcard spellings do <em>not</em> produce the same message —
     * <code>&#42;/&#42;</code> is rejected as a wildcard {@code type} and {@code application/*} as a
     * wildcard {@code subtype}. The handler keys on the prefix they share, which is why this asserts
     * the prefix on both rather than either message in full.
     */
    @Test
    void springRejectsBothWildcardSpellingsTheWayTheHandlerExpects() {
        assertThat(wildcardRejection("*/*").getMessage())
                .as("the handler matches on this text; if it moves, the wildcard case silently goes "
                        + "back to being a 500 with a stack trace")
                .contains("Content-Type cannot contain wildcard");
        assertThat(wildcardRejection("application/*").getMessage())
                .as("application/* is rejected for its subtype, with different wording — the matched "
                        + "prefix has to cover both spellings or half the fault stays open")
                .contains("Content-Type cannot contain wildcard");
    }

    @Test
    void bothWildcardContentTypeRejectionsBecome415() throws Exception {
        for (String contentType : new String[] {"*/*", "application/*"}) {
            ProblemDetail problem = handler.handleIllegalArgument(wildcardRejection(contentType));

            assertThat(problem.getStatus())
                    .as("%s", contentType)
                    .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value());
            assertThat(problem.getProperties()).containsEntry("code", "UNSUPPORTED_MEDIA_TYPE");
        }
    }

    @Test
    void aWildcardContentTypeRejectionIsLoggedWithoutAStackTrace() throws Exception {
        handler.handleIllegalArgument(wildcardRejection("*/*"));

        assertThat(captured.list)
                .as("an anonymous caller able to write ERROR-level stack traces on demand is the "
                        + "whole operational cost of D2 — ~189 log lines per request, measured")
                .noneMatch(event -> event.getLevel() == Level.ERROR);
        assertThat(captured.list)
                .as("a client mistake is still worth one line, so an operator can see it happening")
                .anyMatch(event -> event.getLevel() == Level.WARN);
        assertThat(captured.list)
                .allSatisfy(event -> assertThat(event.getThrowableProxy())
                        .as("the stack trace is the log-flood primitive; the message alone is enough")
                        .isNull());
    }

    /**
     * The load-bearing one. Every {@code IllegalArgumentException} that is not the wildcard case is a
     * genuine fault, and must keep the fallback's behaviour exactly: 500, {@code INTERNAL}, and a
     * stack trace at ERROR for whoever has to debug it.
     */
    @Test
    void anUnrelatedIllegalArgumentKeepsTheInternalErrorContract() throws Exception {
        ProblemDetail problem = handler.handleIllegalArgument(
                new IllegalArgumentException("tenant id must not be null"));

        assertThat(problem.getStatus())
                .as("relabelling a real internal fault as a client error hides it from the caller "
                        + "and from the log at the same time")
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problem.getProperties()).containsEntry("code", "INTERNAL");
        assertThat(captured.list)
                .as("a genuine fault still has to arrive with its stack trace")
                .anyMatch(event -> event.getLevel() == Level.ERROR
                        && event.getThrowableProxy() != null);
    }

    /** A null message must not be mistaken for the wildcard case, nor blow up the matcher. */
    @Test
    void anIllegalArgumentWithNoMessageIsStillAnInternalError() throws Exception {
        ProblemDetail problem = handler.handleIllegalArgument(new IllegalArgumentException());

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problem.getProperties()).containsEntry("code", "INTERNAL");
    }
}
