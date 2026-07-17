package com.mycompanyname.zero.shared.domain;

public class DomainException extends RuntimeException {

    private final ErrorCode code;

    public DomainException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }

    public static DomainException of(ErrorCode code, String message) {
        return new DomainException(code, message);
    }

    public static DomainException notFound(String message) {
        return new DomainException(ErrorCode.NOT_FOUND, message);
    }

    public static DomainException validation(String message) {
        return new DomainException(ErrorCode.VALIDATION, message);
    }

    public static DomainException unauthorized(String message) {
        return new DomainException(ErrorCode.UNAUTHORIZED, message);
    }

    public static DomainException forbidden(String message) {
        return new DomainException(ErrorCode.FORBIDDEN, message);
    }

    public static DomainException conflict(String message) {
        return new DomainException(ErrorCode.CONFLICT, message);
    }

    public static DomainException tenantUnknown(String message) {
        return new DomainException(ErrorCode.TENANT_UNKNOWN, message);
    }

    public static DomainException loginFailed(String message) {
        return new DomainException(ErrorCode.LOGIN_FAILED, message);
    }

    public static DomainException accountLocked(String message) {
        return new DomainException(ErrorCode.ACCOUNT_LOCKED, message);
    }
}
