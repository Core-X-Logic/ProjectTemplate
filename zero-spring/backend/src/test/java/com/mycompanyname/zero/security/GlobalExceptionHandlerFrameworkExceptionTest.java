package com.mycompanyname.zero.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mycompanyname.zero.shared.web.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D3, at the handler. {@link FrameworkExceptionContractIT} proves the two live triggers over HTTP;
 * this pins the rule that replaced them, and the two things that rule must not break.
 *
 * <p>The rule: Spring's own MVC exceptions all implement {@link ErrorResponse}, which means each one
 * already knows the status it deserves. Enumerating them one at a time is what C3 and D2 did twice
 * and it kept leaving the next one open — so the fallback now asks the exception for its status
 * instead of assuming 500, and only exceptions that cannot answer that question get the {@code ERROR}
 * treatment.
 *
 * <p>The two things it must not break are the point of the last three tests: an unexpected exception
 * still has to arrive as a 500 with its stack trace, and Spring Security's exceptions still have to
 * leave this class untouched so the filter chain can write 401/403 itself.
 */
class GlobalExceptionHandlerFrameworkExceptionTest {

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

    private void assertNoStackTraceWasLogged() {
        assertThat(captured.list)
                .as("the stack trace is the whole cost — ~21 KB of log per request, driven by anyone")
                .noneMatch(event -> event.getLevel() == Level.ERROR);
        assertThat(captured.list)
                .allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }

    /**
     * T2. {@code NoResourceFoundException} has carried its own 404 since Spring 6.1; the fallback
     * discarded it and answered 500.
     */
    @Test
    void anUnresolvedResourceKeepsItsOwn404() throws Exception {
        ProblemDetail problem = handler.handleUnexpected(
                new NoResourceFoundException(HttpMethod.GET, "/api/localization"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(problem.getProperties()).containsEntry("code", "NOT_FOUND");
        assertNoStackTraceWasLogged();
    }

    /**
     * T1. {@code MultipartException} is the one framework exception in this family that does
     * <em>not</em> implement {@code ErrorResponse}, which is exactly why it needs its own handler and
     * why the generic rule alone would have left it open.
     */
    @Test
    void aMalformedMultipartRequestIsABadRequest() throws Exception {
        ProblemDetail problem = handler.handleMultipart(
                new MultipartException("Failed to parse multipart servlet request"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getProperties()).containsEntry("code", "VALIDATION");
        assertNoStackTraceWasLogged();
    }

    /**
     * The subclass that must not be flattened into its parent. {@code MaxUploadSizeExceededException}
     * <em>is</em> an {@code ErrorResponse} and means 413, not 400 — a size limit and a malformed
     * header are different things to tell a client.
     */
    @Test
    void anOversizedUploadKeepsIts413RatherThanInheritingThe400() throws Exception {
        ProblemDetail problem = handler.handleMultipart(new MaxUploadSizeExceededException(1024));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
        assertThat(problem.getProperties()).containsEntry("code", "PAYLOAD_TOO_LARGE");
        assertNoStackTraceWasLogged();
    }

    @Test
    void aMissingRequiredParameterIsABadRequest() throws Exception {
        ProblemDetail problem = handler.handleUnexpected(
                new MissingServletRequestParameterException("culture", "String"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getProperties()).containsEntry("code", "VALIDATION");
        assertNoStackTraceWasLogged();
    }

    /**
     * The generic case, stated directly: an arbitrary {@code ErrorResponse} nobody enumerated is
     * answered with its own status. This is the assertion that says the <em>class</em> is closed and
     * not merely the two triggers that were reported.
     */
    @Test
    void anyClientErrorResponseIsAnsweredWithItsOwnStatus() throws Exception {
        ProblemDetail problem = handler.handleUnexpected(
                new ErrorResponseException(HttpStatus.CONFLICT));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getProperties()).containsEntry("code", "CONFLICT");
        assertNoStackTraceWasLogged();
    }

    /**
     * The line the rule stops at. A framework exception carrying a <em>5xx</em> is a server fault
     * however well it describes itself — {@code MissingPathVariableException} means the mapping and
     * the method signature disagree, which is a bug in this codebase. Downgrading those to a quiet
     * WARN would use D3's fix to hide the very faults the fix exists to keep visible.
     */
    @Test
    void aServerSideErrorResponseStillArrivesWithItsStackTrace() throws Exception {
        ProblemDetail problem = handler.handleUnexpected(
                new ErrorResponseException(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problem.getProperties()).containsEntry("code", "INTERNAL");
        assertThat(captured.list)
                .as("a 5xx from the framework is still a fault someone has to debug")
                .anyMatch(event -> event.getLevel() == Level.ERROR
                        && event.getThrowableProxy() != null);
    }

    /** REGRESSION: the fallback still exists and still behaves exactly as it did. */
    @Test
    void agenuinelyUnexpectedExceptionIsStillA500WithAStackTrace() throws Exception {
        ProblemDetail problem = handler.handleUnexpected(
                new IllegalStateException("the connection pool is exhausted"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problem.getProperties()).containsEntry("code", "INTERNAL");
        assertThat(captured.list)
                .as("if broadening the handler had swallowed real faults, the fix would be a worse "
                        + "bug than the one it closes")
                .anyMatch(event -> event.getLevel() == Level.ERROR
                        && event.getThrowableProxy() != null);
    }

    /**
     * REGRESSION: security exceptions are rethrown, not handled. The instanceof check has to stay
     * ahead of the {@code ErrorResponse} branch — and it would still matter if it did not, since
     * neither Spring Security exception implements {@code ErrorResponse} and both would otherwise
     * become 500s where the filter chain used to write 401/403.
     */
    @Test
    void securityExceptionsAreStillRethrownForTheFilterChain() {
        assertThatThrownBy(() -> handler.handleUnexpected(new AccessDeniedException("denied")))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> handler.handleUnexpected(new BadCredentialsException("bad")))
                .isInstanceOf(BadCredentialsException.class);

        assertThat(captured.list)
                .as("these never reach this class's logging at all — Spring Security reports them")
                .isEmpty();
    }
}
