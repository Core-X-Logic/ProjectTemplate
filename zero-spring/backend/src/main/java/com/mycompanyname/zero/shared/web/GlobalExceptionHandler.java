package com.mycompanyname.zero.shared.web;

import com.mycompanyname.zero.shared.domain.DomainException;
import com.mycompanyname.zero.shared.domain.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.query.sqm.PathElementException;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartException;

/**
 * <b>E2 — the sweep behind this class, recorded so the next round does not repeat it.</b>
 *
 * <p>C3, D2 and D3 each closed one exception that had fallen through {@link #handleUnexpected} and
 * become a 500 with a full stack trace at {@code ERROR}; E1 found another ({@code sort}). Rather than
 * fix that one and wait for the next report, every exception family reachable from client input was
 * enumerated and given an explicit verdict. The ones that stay 500 matter as much as the ones that
 * moved — a wrong 4xx hides a real fault, which is a worse bug than the one being fixed.
 *
 * <table border="1">
 *   <caption>Verdicts</caption>
 *   <tr><th>Exception</th><th>Verdict</th></tr>
 *   <tr><td>{@code PropertyReferenceException}</td>
 *       <td><b>400</b>, gated — {@link #handleSortResolutionFailure}. E1's trigger.</td></tr>
 *   <tr><td>{@code InvalidDataAccessApiUsageException}</td>
 *       <td><b>400 only when it is a sort failure the caller asked for</b>, otherwise unchanged 500.
 *       The class also means "this application misused the persistence API", which must stay loud.</td></tr>
 *   <tr><td>{@code TypeMismatchException} family</td>
 *       <td><b>400</b> — {@link #handleTypeMismatch}, less {@code ConversionNotSupportedException},
 *       which is a misconfiguration here and keeps its 500.</td></tr>
 *   <tr><td>{@code MultipartException}</td>
 *       <td><b>400</b> — {@link #handleMultipart}; {@code MaxUploadSizeExceededException} keeps 413.</td></tr>
 *   <tr><td>{@code DataIntegrityViolationException}</td>
 *       <td><b>409</b> already — {@link #handleDataIntegrityViolation}. Verified, unchanged.</td></tr>
 *   <tr><td>Everything implementing {@code ErrorResponse}</td>
 *       <td><b>Its own status</b> when 4xx — the D3 rule in {@link #handleUnexpected}. Covers Jackson's
 *       parse and mapping failures, which reach here wrapped in {@code HttpMessageNotReadableException}
 *       and never as themselves.</td></tr>
 *   <tr><td>{@code ConstraintViolationException} (jakarta)</td>
 *       <td><b>Not reachable, deliberately not handled.</b> Method validation needs {@code @Validated},
 *       which no bean here carries, and no entity declares a constraint — so nothing can throw it and a
 *       handler for it could not be tested. Bean validation runs on request DTOs, where it surfaces as
 *       {@code MethodArgumentNotValidException} and is already a 400 with field detail. If either is
 *       introduced, this row is the thing to revisit.</td></tr>
 *   <tr><td>Jackson used directly ({@code RateLimitFilter}, the tenant filters)</td>
 *       <td><b>Already contained</b> at the call site — the one call that parses client bytes,
 *       {@code RateLimitFilter.extractUsername}, catches {@code IOException} itself, so a malformed body
 *       never reaches this class from there.</td></tr>
 *   <tr><td>{@code HttpMessageNotWritableException}, {@code ConversionNotSupportedException},
 *       {@code MissingPathVariableException}, any {@code ErrorResponse} carrying 5xx</td>
 *       <td><b>Stays 500 with its stack trace.</b> Every one means this application is broken, not the
 *       request. Pinned by tests so a later round cannot quietly downgrade them.</td></tr>
 * </table>
 *
 * <p>Page size needs no handler: {@code spring.data.web.pageable.max-page-size} is set to 100, and
 * Spring Data clamps rather than throwing — {@code ?size=999999} was driven live and answered 200 with
 * a capped page. {@code ?page=abc} and {@code ?size=abc} likewise fall back to the default instead of
 * failing.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * The fragment {@code HttpHeaders.setContentType} puts in its {@code IllegalArgumentException}
     * when handed a wildcard media type. See {@link #handleIllegalArgument} for why the match is on
     * the message rather than on the exception type.
     */
    private static final String WILDCARD_CONTENT_TYPE_REJECTION = "Content-Type cannot contain wildcard";

    /**
     * The fragment Spring Data JPA's {@code QueryUtils.checkSortExpression} puts in the
     * {@code InvalidDataAccessApiUsageException} it raises for a sort property containing punctuation.
     * That exception carries no cause, so there is nothing else to key on. See {@link
     * #handleSortResolutionFailure}; {@code GlobalExceptionHandlerSortTest} provokes the real
     * {@code QueryUtils} so a rewording breaks the build rather than reopening the fault.
     */
    private static final String UNSAFE_SORT_REJECTION = "must only contain property references";

    /** Spring Data's {@code Pageable} sort parameter; the default name, and this app does not rename it. */
    private static final String SORT_PARAMETER = "sort";

    @ExceptionHandler(DomainException.class)
    public ProblemDetail handleDomainException(DomainException ex) {
        HttpStatus status = mapStatus(ex.getCode());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setTitle(ex.getCode().name());
        problem.setProperty("code", ex.getCode().name());
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        problem.setTitle(ErrorCode.VALIDATION.name());
        problem.setProperty("code", ErrorCode.VALIDATION.name());
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fieldError ->
                fieldErrors.put(fieldError.getField(), fieldError.getDefaultMessage()));
        problem.setProperty("fieldErrors", fieldErrors);
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleNotReadable(HttpMessageNotReadableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed request body");
        problem.setTitle(ErrorCode.VALIDATION.name());
        problem.setProperty("code", ErrorCode.VALIDATION.name());
        return problem;
    }

    /**
     * C3. Without this, a wrong HTTP method fell through to {@link #handleUnexpected} and became a
     * {@code 500} with a full stack trace at {@code ERROR}. Live: {@code PUT /api/auth/login}
     * answered {@code 500 {"code":"INTERNAL"}}; {@code PATCH} and {@code DELETE} did the same.
     *
     * <p>The status was the smaller half of the problem. These are unauthenticated endpoints, so
     * anyone at all could write a stack trace into the log per request, for as long as they cared to
     * — noise that buries real faults, and under prod's JSON logging a cheap way to spend the log
     * budget. A client using the wrong verb is a client mistake: one line at {@code WARN}, no trace.
     *
     * <p>Returned as a {@link ResponseEntity} solely to carry {@code Allow}, which RFC 9110 requires
     * on a 405 and a bare {@code ProblemDetail} return cannot set.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.warn("Rejected {} on an endpoint that does not support it", ex.getMethod());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.METHOD_NOT_ALLOWED,
                "Request method " + ex.getMethod() + " is not supported by this endpoint");
        problem.setTitle(ErrorCode.METHOD_NOT_ALLOWED.name());
        problem.setProperty("code", ErrorCode.METHOD_NOT_ALLOWED.name());

        HttpHeaders headers = new HttpHeaders();
        Set<HttpMethod> supported = ex.getSupportedHttpMethods();
        if (supported != null && !supported.isEmpty()) {
            headers.setAllow(supported);
        }
        return new ResponseEntity<>(problem, headers, HttpStatus.METHOD_NOT_ALLOWED);
    }

    /**
     * C3, the same fault through the other door: {@code Content-Type: text/plain} or
     * {@code application/x-www-form-urlencoded} on a JSON endpoint answered {@code 500} with a stack
     * trace instead of {@code 415}.
     *
     * <p>The detail deliberately does not echo the submitted {@code Content-Type}. It adds nothing a
     * client does not already know, and an unparseable one arrives here as {@code null} — the actual
     * value goes to the log, where it is useful.
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ProblemDetail handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        log.warn("Rejected an unsupported Content-Type: {}", ex.getContentType());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "The request Content-Type is not supported by this endpoint");
        problem.setTitle(ErrorCode.UNSUPPORTED_MEDIA_TYPE.name());
        problem.setProperty("code", ErrorCode.UNSUPPORTED_MEDIA_TYPE.name());
        return problem;
    }

    /**
     * C3, and the reason the fix is not just the two exceptions that were reported. Verifying the
     * 405/415 handlers turned this up: {@code Accept: application/xml} produced the identical
     * outcome — {@code 500 {"code":"INTERNAL"}} with a stack trace at {@code ERROR} — and it is
     * strictly the worse of the three. {@code GET /api/localization/languages} is anonymous <em>and
     * absent from</em> {@code zero.ratelimit.paths}, so unlike the login endpoints there is no
     * throttle bounding how fast the loop can run. Closing only the reported spellings would have
     * left C3's own threat statement true, defeated by one header.
     *
     * <p>Returning a {@link ProblemDetail} to a client that has just said it accepts nothing this
     * server can produce looks circular, but is not: Spring presets the content type to
     * {@code application/problem+json} for a {@code ProblemDetail} return value and skips
     * negotiation, so the response is written rather than failing a second time.
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ProblemDetail handleMediaTypeNotAcceptable(HttpMediaTypeNotAcceptableException ex) {
        log.warn("No representation matches the request's Accept header; supported: {}",
                ex.getSupportedMediaTypes());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_ACCEPTABLE,
                "This endpoint cannot produce any of the media types the request accepts");
        problem.setTitle(ErrorCode.NOT_ACCEPTABLE.name());
        problem.setProperty("code", ErrorCode.NOT_ACCEPTABLE.name());
        return problem;
    }

    /**
     * D2. The wildcard {@code Content-Type} case, and nothing else.
     *
     * <p><code>Content-Type: &#42;/&#42;</code> parses as a valid media type but
     * {@code HttpHeaders.setContentType} refuses it, and {@code ServletServerHttpRequest.getHeaders()}
     * calls that while the {@code @RequestBody} argument is being resolved — before the handler runs.
     * So {@code HttpMediaTypeNotSupportedException} is never thrown, {@link
     * #handleMediaTypeNotSupported} never sees it, and the {@code Exception} fallback answered 500
     * with a full stack trace at {@code ERROR}: measured at ~189 log lines per request, driven by
     * anyone, with no credentials, as fast as they care to ask.
     *
     * <p>{@code RateLimitFilter} refuses this at the edge too, but only on the five paths in
     * {@code zero.ratelimit.paths}. The fault is in the argument resolver, which every
     * {@code @RequestBody} endpoint goes through — live with a valid token, {@code POST /api/users}
     * answered 500 twelve times out of twelve, unthrottled. This handler is what closes the rest.
     *
     * <p><b>Why it matches on the message.</b> A plain
     * {@code @ExceptionHandler(IllegalArgumentException.class)} returning 415 would be a worse bug
     * than the one it fixes: {@code IllegalArgumentException} is what a service throws when an
     * invariant fails, so a blanket handler would quietly relabel real internal faults from every
     * controller in the application as client errors — 415 to the caller, no {@code ERROR}, no stack
     * trace to debug from. Everything that is not this exact case is therefore handed straight back
     * to {@link #handleUnexpected} with its behaviour unchanged.
     *
     * <p>The message match is the narrowest signal available, and it fails <em>safe</em>: should a
     * future Spring release reword it, the wildcard case reverts to the 500 it produces today rather
     * than opening anything. {@code GlobalExceptionHandlerIllegalArgumentTest} builds its input by
     * provoking the real {@code HttpHeaders.setContentType}, so that rewording breaks the build
     * instead of going unnoticed.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) throws Exception {
        String message = ex.getMessage();
        if (message == null || !message.contains(WILDCARD_CONTENT_TYPE_REJECTION)) {
            return handleUnexpected(ex);
        }
        log.warn("Rejected a wildcard Content-Type before argument resolution: {}", message);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "The request Content-Type is not supported by this endpoint");
        problem.setTitle(ErrorCode.UNSUPPORTED_MEDIA_TYPE.name());
        problem.setProperty("code", ErrorCode.UNSUPPORTED_MEDIA_TYPE.name());
        return problem;
    }

    /**
     * D3/T1, and the one framework exception the {@link ErrorResponse} rule in {@link
     * #handleUnexpected} cannot reach: {@code MultipartException} extends {@code
     * NestedRuntimeException} and carries no status of its own.
     *
     * <p>It is also the cheapest of the three triggers to fire. {@code
     * StandardServletMultipartResolver} calls a request multipart on the {@code Content-Type} prefix
     * alone, and {@code DispatcherServlet.checkMultipart} runs it <em>before</em> handler mapping — so
     * a header with no {@code boundary} crashes on paths that have nothing to do with uploads and
     * never reaches a controller that could have rejected it. Live: {@code GET /actuator/health} with
     * {@code Content-Type: multipart/form-data} answered 500 with a 181-line stack trace, an empty
     * body, and no credentials. That path must stay {@code permitAll} for the kubelet probe and is
     * absent from {@code zero.ratelimit.paths}, so nothing throttles it: 30 serial requests wrote
     * 629 KB of log in 1.3 s, about 21 KB apiece, which is ~42 GB/day from one client.
     *
     * <p>{@code MaxUploadSizeExceededException} is a subclass and <em>is</em> an {@code
     * ErrorResponse}, meaning 413 rather than 400 — a size limit and a malformed header are different
     * facts about the request — so it is handed back to the general rule instead of being flattened
     * into this one.
     */
    @ExceptionHandler(MultipartException.class)
    public ProblemDetail handleMultipart(MultipartException ex) throws Exception {
        if (ex instanceof ErrorResponse) {
            return handleUnexpected(ex);
        }
        log.warn("Rejected a malformed multipart request: {}", ex.getMessage());
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION,
                "The request could not be processed as submitted");
    }

    /**
     * D3, third trigger — found by driving the fix for the first two rather than reported.
     * {@code GET /api/users/not-a-number} answered {@code 500 {"code":"INTERNAL"}}: a path variable
     * that will not convert to {@code Long} is a client mistake, and every authenticated user of every
     * tenant can produce one on any {@code @PathVariable}/{@code @RequestParam} endpoint in the
     * application.
     *
     * <p>Needs naming explicitly because {@code TypeMismatchException} is not an {@code ErrorResponse}
     * and so cannot be reached by the general rule in {@link #handleUnexpected}.
     *
     * <p><b>E2 — why the whole family, less one.</b> This handler was originally bound to {@code
     * MethodArgumentTypeMismatchException} alone, which is the subclass the reported trigger happened
     * to produce. That is the enumeration mistake D3 was written to stop making: the parent is thrown
     * by {@code WebDataBinder} for the same reason (a value the binder cannot convert) and would have
     * fallen through to the 500 fallback. The carve-out is the one sibling that is genuinely <em>not</em>
     * a client fault — {@code ConversionNotSupportedException} means no converter is registered for a
     * type this application binds, i.e. this application is misconfigured, and it has to keep its 500
     * and its stack trace. Handling the family wholesale without that carve-out would be wrong in the
     * same way a blanket {@code IllegalArgumentException} handler is (see {@link #handleIllegalArgument}).
     *
     * <p>The rejected value is logged but deliberately kept out of the response body — it is client
     * input, the client already has it, and reflecting it buys nothing.
     */
    @ExceptionHandler(TypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(TypeMismatchException ex) throws Exception {
        if (ex instanceof ConversionNotSupportedException) {
            return handleUnexpected(ex);
        }
        String name = ex instanceof MethodArgumentTypeMismatchException mismatch
                ? mismatch.getName()
                : ex.getPropertyName();
        log.warn("Rejected an unconvertible value for '{}': {}", name, ex.getValue());
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION,
                "A request parameter has a value of the wrong type");
    }

    /**
     * E1/E2. {@code GET /api/notifications?sort=;drop} answered {@code 500 {"code":"INTERNAL"}} with a
     * 233-frame stack trace at {@code ERROR} — 29,554 bytes of log per request, and reachable by any
     * authenticated user holding <em>zero</em> permissions, on every paged endpoint in the application.
     * It is the same defect D3 closed for Spring MVC, one layer down: Spring Data validates the sort
     * property (so this is not an injection), it simply reports the rejection as an exception that
     * carries no HTTP status, and the fallback therefore called it a server fault.
     *
     * <p><b>Three shapes, one cause.</b> Enumerating the reported one would have left the other two,
     * so all three were driven against the live repositories first:
     * <ul>
     *   <li>derived queries ({@code findAllByTenantId}) throw {@code PropertyReferenceException}
     *       directly — <em>"No property ';drop' found for type 'User'"</em>;</li>
     *   <li>{@code @Query} methods with a punctuated property throw {@code
     *       InvalidDataAccessApiUsageException} from {@code QueryUtils.checkSortExpression}, with no
     *       cause at all — hence {@link #UNSAFE_SORT_REJECTION};</li>
     *   <li>{@code @Query} methods with a merely unknown property get as far as Hibernate, which
     *       raises {@code PathElementException} (<em>"Could not resolve attribute 'nosuchprop'"</em>)
     *       wrapped in {@code InvalidDataAccessApiUsageException}. Live: {@code
     *       GET /api/users?search=a&sort=nosuchprop}. Keying only on the first two would have left this
     *       one a 500.</li>
     * </ul>
     *
     * <p><b>Why the {@code sort} parameter is checked as well as the exception.</b>
     * {@code InvalidDataAccessApiUsageException} is not inherently a client error — it is also what
     * Spring raises when <em>this</em> code misuses the persistence API, and downgrading those to a
     * quiet 400 would use this fix to hide the faults it exists to expose. The exception signature
     * alone is not enough either: a typo in a {@code @Query} produces the same {@code
     * PathElementException} and is a bug here, not in the caller. So the rule is the narrowest true
     * statement available — <em>a sort that the client did not ask for cannot be the client's
     * fault</em> — and anything failing either half is handed back to {@link #handleUnexpected} with
     * its 500 and its stack trace intact.
     *
     * <p>Requiring both halves also closes the obvious abuse of the request-side check on its own:
     * appending {@code &sort=id} to an unrelated failing request would otherwise let a caller suppress
     * the {@code ERROR} record of a genuine server fault.
     *
     * <p>The property the client asked for goes to the log, not to the response body — the same
     * convention as {@link #handleTypeMismatch} and {@link #handleMediaTypeNotSupported}.
     */
    @ExceptionHandler({PropertyReferenceException.class, InvalidDataAccessApiUsageException.class})
    public ProblemDetail handleSortResolutionFailure(Exception ex, HttpServletRequest request)
            throws Exception {
        if (!clientAskedToSort(request) || !isSortResolutionFailure(ex)) {
            return handleUnexpected(ex);
        }
        log.warn("Rejected an unsortable property in sort={}: {}",
                Arrays.toString(request.getParameterValues(SORT_PARAMETER)), ex.getMessage());
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION,
                "The requested sort property is not valid for this resource");
    }

    /**
     * True only if the request itself carried a non-blank {@code sort}. A {@code Pageable} default
     * supplied by this application is not the caller's doing, so a failure to resolve it stays a 500.
     */
    private static boolean clientAskedToSort(HttpServletRequest request) {
        String[] values = request.getParameterValues(SORT_PARAMETER);
        if (values == null) {
            return false;
        }
        return Arrays.stream(values).anyMatch(value -> value != null && !value.isBlank());
    }

    /**
     * Walks the cause chain because only one of the three shapes arrives unwrapped. The message match
     * is last and narrowest: it covers the one case Spring Data reports with no cause to inspect, and
     * it fails <em>safe</em> — a future rewording sends that case back to the 500 it produces today
     * rather than opening anything.
     */
    private static boolean isSortResolutionFailure(Throwable ex) {
        for (Throwable cause = ex; cause != null && cause != cause.getCause(); cause = cause.getCause()) {
            if (cause instanceof PropertyReferenceException || cause instanceof PathElementException) {
                return true;
            }
            String message = cause.getMessage();
            if (message != null && message.contains(UNSAFE_SORT_REJECTION)) {
                return true;
            }
        }
        return false;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The request conflicts with existing data");
        problem.setTitle(ErrorCode.CONFLICT.name());
        problem.setProperty("code", ErrorCode.CONFLICT.name());
        return problem;
    }

    /**
     * Last-resort fallback: unexpected exceptions become a generic 500 ProblemDetail without
     * leaking internals. Security exceptions are rethrown so the Spring Security filter chain
     * keeps producing the proper 401/403 responses.
     *
     * <p><b>D3 — why this method also asks about {@link ErrorResponse}.</b> C3 and D2 each closed a
     * framework exception that had fallen through to here and become a 500 with ~180 frames at
     * {@code ERROR}: first 405, then 415, then 406, then the wildcard {@code Content-Type}. Each fix
     * was correct and each one left the next one open, because the defect was never any particular
     * exception — it was that this fallback sits behind <em>all</em> of Spring's MVC exceptions, which
     * already know their own status and do not need a stack trace. Two more triggers duly turned up
     * ({@code MultipartException}, {@code NoResourceFoundException}), and enumerating those two would
     * have left the third ({@code MethodArgumentTypeMismatchException}) and every future one open.
     *
     * <p>So the rule is stated once instead: Spring marks every such exception with {@code
     * ErrorResponse}, so ask the exception for its status rather than assuming 500. A plain 404 is
     * answered as a 404 with one {@code WARN} line, and this fallback keeps only what genuinely
     * cannot answer the question.
     *
     * <p><b>The 4xx condition is load-bearing.</b> An {@code ErrorResponse} carrying a 5xx is a server
     * fault however well it describes itself — {@code MissingPathVariableException} means a mapping
     * and a method signature disagree, which is a bug here, not in the caller. Downgrading those to a
     * quiet WARN would use this fix to hide exactly the faults it exists to keep visible.
     *
     * <p>The security rethrow stays first. Neither Spring Security exception implements {@code
     * ErrorResponse}, so the ordering is belt-and-braces rather than strictly required — but a future
     * release making {@code AccessDeniedException} an {@code ErrorResponse} would otherwise silently
     * take 403 reporting away from the filter chain, and that is not a failure worth risking on a
     * guess about someone else's roadmap.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) throws Exception {
        if (ex instanceof AccessDeniedException || ex instanceof AuthenticationException) {
            throw ex;
        }
        if (ex instanceof ErrorResponse errorResponse) {
            HttpStatusCode statusCode = errorResponse.getStatusCode();
            HttpStatus status = HttpStatus.resolve(statusCode.value());
            if (status != null && status.is4xxClientError()) {
                log.warn("Rejected a request with {} ({}): {}",
                        status.value(), ex.getClass().getSimpleName(), ex.getMessage());
                return problem(status, codeFor(status), detailFor(status));
            }
        }
        log.error("Unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL,
                "An unexpected error occurred");
    }

    private ProblemDetail problem(HttpStatus status, ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(code.name());
        problem.setProperty("code", code.name());
        return problem;
    }

    /**
     * The inverse of {@link #mapStatus}, for exceptions that arrive already knowing their status.
     * An unrecognised 4xx is reported as {@code VALIDATION} rather than {@code INTERNAL}: the caller
     * has been told it is their request at fault, and the body must not contradict the status line.
     */
    private static ErrorCode codeFor(HttpStatus status) {
        return switch (status) {
            case NOT_FOUND -> ErrorCode.NOT_FOUND;
            case UNAUTHORIZED -> ErrorCode.UNAUTHORIZED;
            case FORBIDDEN -> ErrorCode.FORBIDDEN;
            case CONFLICT -> ErrorCode.CONFLICT;
            case METHOD_NOT_ALLOWED -> ErrorCode.METHOD_NOT_ALLOWED;
            case NOT_ACCEPTABLE -> ErrorCode.NOT_ACCEPTABLE;
            case UNSUPPORTED_MEDIA_TYPE -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case PAYLOAD_TOO_LARGE -> ErrorCode.PAYLOAD_TOO_LARGE;
            case TOO_MANY_REQUESTS -> ErrorCode.TOO_MANY_REQUESTS;
            default -> ErrorCode.VALIDATION;
        };
    }

    /**
     * Fixed text per status. Spring's own {@code ProblemDetail} detail echoes the offending path or
     * parameter back to the caller; this does not, for the reason given on {@link
     * #handleMediaTypeNotSupported} — the client already knows what it sent, and the log is where
     * that belongs.
     */
    private static String detailFor(HttpStatus status) {
        return switch (status) {
            case NOT_FOUND -> "The requested resource does not exist";
            case BAD_REQUEST -> "The request could not be processed as submitted";
            case PAYLOAD_TOO_LARGE -> "The request payload is larger than this endpoint accepts";
            default -> status.getReasonPhrase();
        };
    }

    private HttpStatus mapStatus(ErrorCode code) {
        return switch (code) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case UNAUTHORIZED, LOGIN_FAILED, ACCOUNT_LOCKED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN, SUBSCRIPTION_INVALID -> HttpStatus.FORBIDDEN;
            case CONFLICT -> HttpStatus.CONFLICT;
            case VALIDATION, TENANT_UNKNOWN -> HttpStatus.BAD_REQUEST;
            case TOO_MANY_REQUESTS -> HttpStatus.TOO_MANY_REQUESTS;
            case METHOD_NOT_ALLOWED -> HttpStatus.METHOD_NOT_ALLOWED;
            case UNSUPPORTED_MEDIA_TYPE -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case NOT_ACCEPTABLE -> HttpStatus.NOT_ACCEPTABLE;
            // RateLimitFilter writes this one itself (it rejects before the request ever reaches a
            // controller), but the mapping belongs here so the code is not a special case.
            case PAYLOAD_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case INTERNAL -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
