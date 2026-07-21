package com.mycompanyname.zero.identity.auth;

import com.mycompanyname.zero.config.TwoFactorProperties;
import com.mycompanyname.zero.identity.domain.TwoFactorRecoveryCode;
import com.mycompanyname.zero.identity.repo.TwoFactorRecoveryCodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * Single-use 2FA recovery codes. Storage mirrors {@code PasswordHistory}: only BCrypt hashes are kept
 * (the same {@code BCryptPasswordEncoder(12)} bean the rest of the app uses), and a candidate is
 * checked by iterating {@code matches()} over the user's UNCONSUMED rows. A match is retired with a
 * GUARDED conditional UPDATE ({@code consumeIfUnconsumed}), so under concurrency exactly one caller can
 * win a given code — a code mints a session at most once, replay and double-spend are both blocked,
 * while its siblings stay valid.
 *
 * <p>Codes are shown to the user exactly once (at {@code enable} / regeneration); the plaintext is
 * never stored, logged, or recoverable.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class RecoveryCodeService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    /** Crockford-ish alphabet: no 0/O/1/I so a code read off a screen is unambiguous. */
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int CHARS_PER_CODE = 10;

    private final TwoFactorRecoveryCodeRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final TwoFactorProperties properties;
    private final Clock clock;

    /**
     * Replaces the user's entire recovery-code set with a fresh batch and returns the plaintext ONCE.
     * Used by both {@code enable} (first issuance) and regeneration, so the old set never coexists with
     * the new one.
     */
    public List<String> replaceForUser(Long userId) {
        repository.deleteByUserId(userId);
        List<String> plaintext = new ArrayList<>();
        for (int i = 0; i < properties.getRecoveryCodeCount(); i++) {
            String code = newCode();
            plaintext.add(code);
            TwoFactorRecoveryCode entity = new TwoFactorRecoveryCode();
            entity.setUserId(userId);
            entity.setCodeHash(passwordEncoder.encode(code));
            repository.save(entity);
        }
        return plaintext;
    }

    /** Removes every recovery code for the user (called when 2FA is disabled). */
    public void deleteForUser(Long userId) {
        repository.deleteByUserId(userId);
    }

    /**
     * Consumes a matching unconsumed recovery code and returns true; returns false if none matches or
     * if a concurrent request already consumed the matched code. The match is retired with a guarded
     * conditional UPDATE, so two threads presenting the same code cannot both succeed (no double-spend):
     * only the one whose UPDATE actually flips {@code consumed_at} (affected rows == 1) authenticates.
     * Because each code's BCrypt hash is unique, at most one row matches, so losing that race is a hard
     * failure — the code is spent, and this caller must not authenticate on it.
     */
    public boolean consumeIfValid(Long userId, String candidate) {
        if (userId == null || candidate == null || candidate.isBlank()) {
            return false;
        }
        String normalized = candidate.trim();
        for (TwoFactorRecoveryCode code : repository.findByUserIdAndConsumedAtIsNull(userId)) {
            if (passwordEncoder.matches(normalized, code.getCodeHash())) {
                return repository.consumeIfUnconsumed(code.getId(), clock.instant()) == 1;
            }
        }
        return false;
    }

    private static String newCode() {
        StringBuilder builder = new StringBuilder(CHARS_PER_CODE + 1);
        for (int i = 0; i < CHARS_PER_CODE; i++) {
            if (i == CHARS_PER_CODE / 2) {
                builder.append('-');
            }
            builder.append(ALPHABET[SECURE_RANDOM.nextInt(ALPHABET.length)]);
        }
        return builder.toString();
    }
}
