package com.mycompanyname.zero.shared.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.hibernate.query.sqm.PathElementException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.query.QueryUtils;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.data.util.TypeInformation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E1/E2, at the handler. {@code ClientErrorLogBudgetIT} proves the fault and the fix over HTTP; this
 * pins the rule and — the point of the class — the three places it must <em>not</em> reach.
 *
 * <p>The fault: {@code GET /api/notifications?sort=;drop} answered 500 with a 233-frame stack trace at
 * {@code ERROR}, 29,554 bytes of log, driven by any authenticated user with no permissions at all, on
 * every paged endpoint. Spring Data validates the property name — so this was never injection — it
 * just reports the rejection as an exception with no HTTP status attached, and the fallback assumed
 * that meant server fault.
 *
 * <p>The rule that replaced it needs <em>both</em> halves to be true: the exception has to look like a
 * sort-resolution failure, <em>and</em> the request has to have asked for a sort. Either half alone is
 * unsound, and the last four tests are the evidence for that.
 */
class GlobalExceptionHandlerSortTest {

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

    private static MockHttpServletRequest sortedBy(String... sort) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/notifications");
        request.setParameter("sort", sort);
        return request;
    }

    private void assertQuietBadRequest(ProblemDetail problem) {
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getProperties()).containsEntry("code", "VALIDATION");
        assertThat(captured.list)
                .as("the stack trace is the entire cost — 29,554 bytes per request, driven by a "
                        + "caller holding no permissions")
                .noneMatch(event -> event.getLevel() == Level.ERROR);
        assertThat(captured.list)
                .as("a client mistake is still worth one line, so an operator can see it happening")
                .anyMatch(event -> event.getLevel() == Level.WARN);
        assertThat(captured.list)
                .allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }

    private void assertStillAnInternalError(ProblemDetail problem) {
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problem.getProperties()).containsEntry("code", "INTERNAL");
        assertThat(captured.list)
                .as("a genuine fault has to keep arriving with its stack trace, or this fix has "
                        + "become a way of hiding exactly what it exists to expose")
                .anyMatch(event -> event.getLevel() == Level.ERROR
                        && event.getThrowableProxy() != null);
    }

    /**
     * The exception Spring Data actually throws for a punctuated sort property, produced by the code
     * that actually throws it. {@code QueryUtils.checkSortExpression} builds this one with <em>no
     * cause</em>, so its message is the only thing the handler can key on — and constructing that
     * message by hand here would defeat the purpose of the test.
     */
    private static InvalidDataAccessApiUsageException unsafeSortRejection(String property) {
        try {
            QueryUtils.applySorting("select u from User u", Sort.by(property));
            throw new AssertionError("QueryUtils accepted the sort property '" + property + "'; the "
                    + "E1 fault has changed shape and handleSortResolutionFailure needs revisiting");
        } catch (InvalidDataAccessApiUsageException ex) {
            return ex;
        }
    }

    /**
     * Shape 1 of 3 — a derived query ({@code findAllByTenantId}) rejects the property itself and
     * throws this unwrapped. The reported trigger.
     */
    @Test
    void aDerivedQuerySortRejectionIsABadRequest() throws Exception {
        ProblemDetail problem = handler.handleSortResolutionFailure(
                new PropertyReferenceException(";drop", TypeInformation.of(String.class), List.of()),
                sortedBy(";drop"));

        assertQuietBadRequest(problem);
    }

    /**
     * Shape 2 of 3 — a {@code @Query} method with a punctuated property. Spring Data refuses it before
     * Hibernate sees it, with a bare {@code InvalidDataAccessApiUsageException}.
     *
     * <p>Asserting the message fragment separately is deliberate: it is what the handler matches on, so
     * if a Spring Data upgrade rewords it this test says so directly instead of leaving a puzzling
     * 500 in production.
     */
    @Test
    void anUnsafeSortExpressionOnAnAnnotatedQueryIsABadRequest() throws Exception {
        InvalidDataAccessApiUsageException rejection = unsafeSortRejection(";drop");

        assertThat(rejection.getMessage())
                .as("the handler keys on this text; if it moves, the @Query sort case silently goes "
                        + "back to being a 500 with a stack trace")
                .contains("must only contain property references");
        assertThat(rejection.getCause())
                .as("no cause to inspect is precisely why the match has to be on the message")
                .isNull();

        assertQuietBadRequest(handler.handleSortResolutionFailure(rejection, sortedBy(";drop")));
    }

    /**
     * Shape 3 of 3, and the one a narrower fix would have missed. A property that is merely unknown
     * carries no punctuation, so {@code checkSortExpression} passes it straight through into the JPQL
     * and Hibernate rejects it several frames later. Live: {@code
     * GET /api/users?search=a&sort=nosuchprop} answered 500.
     */
    @Test
    void anUnknownAttributeRejectedByHibernateIsABadRequest() throws Exception {
        InvalidDataAccessApiUsageException wrapped = new InvalidDataAccessApiUsageException(
                "could not prepare query",
                new IllegalArgumentException(new PathElementException(
                        "Could not resolve attribute 'nosuchprop' of 'User'")));

        assertQuietBadRequest(handler.handleSortResolutionFailure(wrapped, sortedBy("nosuchprop")));
    }

    /**
     * The first half of the rule, on its own. A {@code sort} the caller never sent cannot be the
     * caller's mistake — this is a {@code @Query} in this codebase naming an attribute that does not
     * exist, which is a bug here and has to stay a 500 with a trace.
     */
    @Test
    void theSameHibernateFailureWithoutAClientSortStaysAnInternalError() throws Exception {
        InvalidDataAccessApiUsageException wrapped = new InvalidDataAccessApiUsageException(
                "could not prepare query",
                new IllegalArgumentException(new PathElementException(
                        "Could not resolve attribute 'typo' of 'User'")));

        assertStillAnInternalError(handler.handleSortResolutionFailure(
                wrapped, new MockHttpServletRequest("GET", "/api/users")));
    }

    /** An empty {@code sort=} is not a request to sort, and Spring Data ignores it. */
    @Test
    void aBlankSortParameterDoesNotCountAsAskingToSort() throws Exception {
        assertStillAnInternalError(handler.handleSortResolutionFailure(
                new PropertyReferenceException("typo", TypeInformation.of(String.class), List.of()),
                sortedBy("   ")));
    }

    /**
     * The second half of the rule, and the reason the request check is not sufficient by itself.
     * {@code InvalidDataAccessApiUsageException} is also what Spring raises when <em>this</em> code
     * misuses the persistence API. Without this branch, appending {@code &sort=id} to any request would
     * let a caller suppress the {@code ERROR} record of a genuine server fault on demand.
     */
    @Test
    void anUnrelatedPersistenceMisuseStaysAnInternalErrorEvenWhenSortingWasRequested()
            throws Exception {
        ProblemDetail problem = handler.handleSortResolutionFailure(
                new InvalidDataAccessApiUsageException(
                        "Detached entity passed to persist"),
                sortedBy("id,asc"));

        assertStillAnInternalError(problem);
    }

    /**
     * E2. The {@code TypeMismatchException} family was previously handled only through its {@code
     * MethodArgumentTypeMismatchException} subclass — the spelling the reported trigger happened to
     * produce. The parent is thrown by {@code WebDataBinder} for the same reason and fell through to
     * the 500 fallback.
     */
    @Test
    void aPlainTypeMismatchIsABadRequest() throws Exception {
        ProblemDetail problem = handler.handleTypeMismatch(
                new TypeMismatchException("not-a-number", Long.class));

        assertQuietBadRequest(problem);
    }

    /**
     * The carve-out. {@code ConversionNotSupportedException} extends {@code TypeMismatchException} but
     * means no converter is registered for a type this application binds — a misconfiguration here,
     * not a bad request, and it keeps its 500 and its stack trace.
     */
    @Test
    void aMissingConverterIsStillAnInternalError() throws Exception {
        assertStillAnInternalError(handler.handleTypeMismatch(
                new ConversionNotSupportedException("value", GlobalExceptionHandlerSortTest.class, null)));
    }
}
