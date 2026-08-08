package com.mycompanyname.zero.identity.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The single definition of the mailed-secret pattern shared by the password-reset and
 * email-confirmation flows (R-44, V14): 32 random bytes, base64url, mailed once; the database
 * stores only the SHA-256 hex next to an expiry. Both {@code AccountService} (reset + confirm)
 * and {@code ProfileService} (email change re-issues a confirmation code) mint and hash through
 * this class, so the two writers cannot drift apart — drifting apart is exactly how the plaintext
 * era survived unnoticed.
 *
 * <p>Public on purpose: it holds no secret and no state, and {@code PasswordPolicyIT} uses
 * {@link #sha256(String)} to assert at the database floor that what V14 stores IS the digest of
 * the mailed code, never the code itself.
 */
public final class AccountRecoveryCodes {

    /**
     * 1h — a recovery window, not a parking slot: whoever asked for the reset is at their mailbox
     * now, and every extra hour only widens the window a DB dump would enjoy (R-44).
     */
    public static final Duration RESET_CODE_VALIDITY = Duration.ofHours(1);

    /**
     * 72h — deliberately looser than the reset code: the account already exists, the code only
     * flips {@code email_confirmed}, and a registration made before a weekend should survive it.
     * Same upper end as the invitation token's window (V15).
     */
    public static final Duration CONFIRMATION_CODE_VALIDITY = Duration.ofHours(72);

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int CODE_BYTES = 32;

    private AccountRecoveryCodes() {
    }

    /** A fresh raw code — mailed once, never persisted. */
    public static String newCode() {
        byte[] bytes = new byte[CODE_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 hex of a raw code — the only shape a code ever takes in the database (R-44). */
    public static String sha256(String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // Every JVM ships SHA-256 (JCA requirement); reaching this line is a broken runtime.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
