package com.mycompanyname.zero.identity.web.dto;

import java.util.List;

/**
 * Returned ONCE by {@code enable} and by {@code recovery-codes/regenerate}: the plaintext recovery
 * codes. The server stores only their BCrypt hashes and can never show them again, so the frontend
 * must prompt the user to save them. Never logged.
 */
public record RecoveryCodesDto(
        List<String> recoveryCodes) {
}
