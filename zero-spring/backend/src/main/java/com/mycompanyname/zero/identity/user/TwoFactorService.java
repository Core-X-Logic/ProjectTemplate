package com.mycompanyname.zero.identity.user;

import com.mycompanyname.zero.config.FieldEncryptionService;
import com.mycompanyname.zero.identity.auth.CurrentUser;
import com.mycompanyname.zero.identity.auth.RecoveryCodeService;
import com.mycompanyname.zero.identity.auth.TokenRevocationService;
import com.mycompanyname.zero.identity.auth.TotpService;
import com.mycompanyname.zero.identity.domain.User;
import com.mycompanyname.zero.identity.repo.UserRepository;
import com.mycompanyname.zero.identity.web.dto.RecoveryCodesDto;
import com.mycompanyname.zero.identity.web.dto.TwoFactorSetupDto;
import com.mycompanyname.zero.shared.domain.DomainException;
import com.mycompanyname.zero.shared.domain.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Self-service 2FA management for the currently authenticated user. Strictly self-only: the target is
 * always {@link CurrentUser#userId()} (the JWT subject), never a caller-supplied id, so one user can
 * never touch another's 2FA — the same discipline as {@link ProfileService}.
 *
 * <p>The two sensitive, destructive operations — {@code disable} and recovery-code regeneration —
 * re-verify the current password first, exactly as {@code ProfileService.changePassword} does, so a
 * momentarily-unlocked session cannot silently strip a user's second factor.
 *
 * <p>Never logs the secret, provisioning URI, or recovery codes: only the state transitions, at WARN.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class TwoFactorService {

    private final UserRepository userRepository;
    private final TotpService totpService;
    private final RecoveryCodeService recoveryCodeService;
    private final FieldEncryptionService fieldEncryptionService;
    private final PasswordEncoder passwordEncoder;
    /** Present only when zero.jwt.revocation.enabled is true; a no-op otherwise (PROD-R16). */
    private final ObjectProvider<TokenRevocationService> revocationServices;

    @Value("${zero.email.app-name:Zero Platform}")
    private String issuer;

    /**
     * Provisions (or re-provisions, while still disabled) a TOTP secret and returns it plus the
     * {@code otpauth://} URI ONCE. 2FA is NOT yet active — the user must confirm a code via
     * {@link #enable}. Re-running while disabled is idempotent: it mints a fresh secret and discards
     * the previous pending one.
     */
    public TwoFactorSetupDto setup() {
        User user = currentUser();
        if (user.isTwoFactorEnabled()) {
            throw new DomainException(ErrorCode.CONFLICT,
                    "Two-factor authentication is already enabled; disable it before setting up again");
        }
        String secret = totpService.generateSecret();
        user.setTwoFactorSecret(fieldEncryptionService.encrypt(secret));
        userRepository.save(user);
        log.warn("2FA setup initiated for user {}", user.getId());
        String uri = totpService.provisioningUri(secret, label(user), issuer);
        return new TwoFactorSetupDto(secret, uri);
    }

    /**
     * Confirms the pending secret with a live code, switches 2FA on, and returns a fresh batch of
     * single-use recovery codes ONCE. Rejects if setup was never run or if already enabled.
     */
    public RecoveryCodesDto enable(String code) {
        User user = currentUser();
        if (user.isTwoFactorEnabled()) {
            throw new DomainException(ErrorCode.CONFLICT, "Two-factor authentication is already enabled");
        }
        if (user.getTwoFactorSecret() == null) {
            throw new DomainException(ErrorCode.VALIDATION,
                    "Two-factor setup has not been started; call setup first");
        }
        String secret = fieldEncryptionService.decrypt(user.getTwoFactorSecret());
        if (!totpService.verify(secret, code)) {
            throw new DomainException(ErrorCode.VALIDATION, "The authenticator code is not valid");
        }
        user.setTwoFactorEnabled(true);
        userRepository.save(user);
        List<String> codes = recoveryCodeService.replaceForUser(user.getId());
        log.warn("2FA enabled for user {}", user.getId());
        return new RecoveryCodesDto(codes);
    }

    /**
     * Turns 2FA off after re-verifying the current password: clears the enabled flag, nulls the secret
     * and deletes every recovery code. Idempotent — safe to call when already disabled (still requires
     * the password).
     */
    public void disable(String password) {
        User user = currentUser();
        requireCurrentPassword(user, password);
        user.setTwoFactorEnabled(false);
        user.setTwoFactorSecret(null);
        userRepository.save(user);
        recoveryCodeService.deleteForUser(user.getId());
        // PROD-R16: dropping the second factor is a credential change, so kill every outstanding
        // access token for this user. Best-effort; no-op when revocation is disabled.
        revocationServices.ifAvailable(service -> service.revokeAllForUser(user.getId()));
        log.warn("2FA disabled for user {}", user.getId());
    }

    /**
     * Replaces the recovery-code set after re-verifying the current password, returning the new codes
     * ONCE. Only meaningful while 2FA is enabled.
     */
    public RecoveryCodesDto regenerateRecoveryCodes(String password) {
        User user = currentUser();
        requireCurrentPassword(user, password);
        if (!user.isTwoFactorEnabled()) {
            throw new DomainException(ErrorCode.VALIDATION,
                    "Two-factor authentication is not enabled");
        }
        List<String> codes = recoveryCodeService.replaceForUser(user.getId());
        log.warn("2FA recovery codes regenerated for user {}", user.getId());
        return new RecoveryCodesDto(codes);
    }

    private void requireCurrentPassword(User user, String password) {
        if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new DomainException(ErrorCode.VALIDATION, "Current password is incorrect");
        }
    }

    private static String label(User user) {
        return user.getEmail() != null && !user.getEmail().isBlank()
                ? user.getEmail()
                : user.getUsername();
    }

    private User currentUser() {
        Long userId = CurrentUser.userId();
        if (userId == null) {
            throw new DomainException(ErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return userRepository.findById(userId)
                .orElseThrow(() -> new DomainException(ErrorCode.UNAUTHORIZED, "User no longer exists"));
    }
}
