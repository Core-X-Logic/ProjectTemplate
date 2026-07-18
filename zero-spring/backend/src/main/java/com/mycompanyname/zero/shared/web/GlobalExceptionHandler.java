package com.mycompanyname.zero.shared.web;

import com.mycompanyname.zero.shared.domain.DomainException;
import com.mycompanyname.zero.shared.domain.ErrorCode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

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
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) throws Exception {
        if (ex instanceof AccessDeniedException || ex instanceof AuthenticationException) {
            throw ex;
        }
        log.error("Unhandled exception", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred");
        problem.setTitle(ErrorCode.INTERNAL.name());
        problem.setProperty("code", ErrorCode.INTERNAL.name());
        return problem;
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
