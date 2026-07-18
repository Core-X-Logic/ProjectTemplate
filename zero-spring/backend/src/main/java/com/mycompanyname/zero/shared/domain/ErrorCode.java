package com.mycompanyname.zero.shared.domain;

public enum ErrorCode {
    NOT_FOUND,
    VALIDATION,
    UNAUTHORIZED,
    FORBIDDEN,
    CONFLICT,
    TENANT_UNKNOWN,
    /** The tenant's subscription does not currently permit access to business endpoints (403). */
    SUBSCRIPTION_INVALID,
    LOGIN_FAILED,
    ACCOUNT_LOCKED,
    /** Too many requests from one client IP or against one username (429, PROD-R6). */
    TOO_MANY_REQUESTS,
    /**
     * The endpoint exists but does not accept the request's HTTP method (405, C3). Unhandled, this
     * used to surface as a 500 with a stack trace at ERROR — see {@code GlobalExceptionHandler}.
     */
    METHOD_NOT_ALLOWED,
    /** The endpoint does not accept the request body's {@code Content-Type} (415, C3). */
    UNSUPPORTED_MEDIA_TYPE,
    /**
     * The endpoint cannot produce any representation the request's {@code Accept} header allows
     * (406, C3). Found while verifying the 405/415 fix: this one is reachable anonymously on
     * {@code /api/localization/**}, which is not rate limited.
     */
    NOT_ACCEPTABLE,
    /**
     * A request body on a throttled unauthenticated endpoint exceeded the size the rate limiter can
     * inspect (413, B2). Refused rather than forwarded: an uninspectable body is one whose username
     * bucket cannot be charged, and that was a bypass.
     */
    PAYLOAD_TOO_LARGE,
    INTERNAL
}
