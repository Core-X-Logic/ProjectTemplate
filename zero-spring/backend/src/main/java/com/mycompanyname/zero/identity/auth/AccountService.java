package com.mycompanyname.zero.identity.auth;

import com.mycompanyname.zero.identity.domain.User;
import com.mycompanyname.zero.identity.password.PasswordHistoryService;
import com.mycompanyname.zero.identity.password.PasswordPolicy;
import com.mycompanyname.zero.identity.password.PasswordPolicyValidator;
import com.mycompanyname.zero.identity.repo.UserRepository;
import com.mycompanyname.zero.notification.email.EmailSender;
import com.mycompanyname.zero.notification.email.EmailTemplateService;
import com.mycompanyname.zero.shared.domain.DomainException;
import com.mycompanyname.zero.tenancy.TenantRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Session;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Unauthenticated self-service account flows: forgot/reset password and email confirmation. These
 * operations are inherently cross-tenant (driven by a secret code or an explicit tenant name), so the
 * Hibernate tenant/host filter is disabled per call before issuing lookups.
 *
 * <p><b>The codes (R-44, V14).</b> Both secrets follow the invitation-token pattern: 32 random
 * bytes, base64url, mailed once; only the SHA-256 hex is stored, next to an expiry. A code is
 * usable only while its hash matches AND its expiry is in the future — expiry is derived at read
 * time from {@link Clock}, never persisted as a state (a scheduled writer would be a
 * {@code @Component} without a GUC and would silently see 0 rows on the policed {@code users}
 * table — the R-46 class). Legacy plaintext codes issued before V14 match nothing (their hash was
 * never stored) and are therefore invalid by construction: fail-closed, no migration data surgery.
 *
 * <p>Password strength ({@link PasswordPolicyValidator}) and the account fields it mutates are owned
 * by the identity module; email delivery is delegated to the notification module.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class AccountService {

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicyValidator passwordPolicyValidator;
    private final PasswordHistoryService passwordHistoryService;
    private final EmailSender emailSender;
    private final EmailTemplateService emailTemplateService;
    private final MessageSource messageSource;
    private final Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Sends a password reset code to the matching account. Always completes without revealing whether
     * the account exists (enumeration-safe), so the controller can return 204 unconditionally.
     */
    public void forgotPassword(String usernameOrEmail, String tenant) {
        disableTenantFilters();
        Long tenantId = resolveTenantId(tenant);
        Optional<User> match = findByUsernameOrEmail(tenantId, usernameOrEmail.trim());
        if (match.isEmpty()) {
            log.info("Password reset requested for an unknown account; responding without disclosure");
            return;
        }
        User user = match.get();
        String code = AccountRecoveryCodes.newCode();
        // R-44: only the hash is persisted; the raw code lives exclusively in the mail below.
        user.setPasswordResetCodeHash(AccountRecoveryCodes.sha256(code));
        user.setPasswordResetCodeExpiresAt(clock.instant().plus(AccountRecoveryCodes.RESET_CODE_VALIDITY));
        userRepository.save(user);
        String body = emailTemplateService.passwordReset(user.getUsername(), code);
        emailSender.send(user.getEmail(), subject("Email.PasswordReset.Subject"), body);
    }

    /**
     * Applies a new password given a valid, unexpired reset code, enforcing the password policy and
     * clearing the consumed code.
     */
    public void resetPassword(String resetCode, String newPassword) {
        disableTenantFilters();
        // Message text is a contract, not prose: the reset screen tells a code rejection apart from
        // a password rejection by detail prefix ("Invalid or expired…" vs "Password…"), and
        // PasswordPolicyIT pins the exact string. Change it only together with both.
        // Unknown and expired collapse into the same message on purpose — telling them apart would
        // confirm to a code-guessing caller which codes EXIST.
        User user = findByCodeHash("select u from User u where u.passwordResetCodeHash = :hash", resetCode)
                .filter(candidate -> isUsable(candidate.getPasswordResetCodeExpiresAt()))
                .orElseThrow(() -> DomainException.validation("Invalid or expired reset code"));
        // Same policy + history semantics as the authenticated change-password flow, resolved against
        // the target user's tenant (there is no authenticated caller during an anonymous reset).
        PasswordPolicy policy = passwordPolicyValidator.resolvePolicy(user.getTenantId(), null);
        passwordPolicyValidator.validate(policy, newPassword);
        passwordHistoryService.checkNotRecentlyUsed(user.getId(), newPassword, policy.historyCount());
        String previousHash = user.getPasswordHash();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordResetCodeHash(null);
        user.setPasswordResetCodeExpiresAt(null);
        user.setShouldChangePassword(false);
        user.setLastPasswordChangeAt(clock.instant());
        userRepository.save(user);
        passwordHistoryService.record(user.getId(), previousHash);
    }

    /**
     * Confirms an email address given a valid, unexpired confirmation code.
     */
    public void confirmEmail(String code) {
        disableTenantFilters();
        User user = findByCodeHash("select u from User u where u.emailConfirmationCodeHash = :hash", code)
                .filter(candidate -> isUsable(candidate.getEmailConfirmationCodeExpiresAt()))
                .orElseThrow(() -> DomainException.validation("Invalid confirmation code"));
        user.setEmailConfirmed(true);
        user.setEmailConfirmationCodeHash(null);
        user.setEmailConfirmationCodeExpiresAt(null);
        userRepository.save(user);
    }

    private Long resolveTenantId(String tenant) {
        if (tenant == null || tenant.isBlank()) {
            return null;
        }
        return tenantRepository.findByNameIgnoreCase(tenant.trim())
                .map(t -> t.getId())
                .orElse(null);
    }

    private Optional<User> findByUsernameOrEmail(Long tenantId, String value) {
        if (tenantId == null) {
            return userRepository.findByUsernameIgnoreCaseAndTenantIdIsNull(value)
                    .or(() -> userRepository.findByEmailIgnoreCaseAndTenantIdIsNull(value));
        }
        return userRepository.findByTenantIdAndUsernameIgnoreCase(tenantId, value)
                .or(() -> userRepository.findByTenantIdAndEmailIgnoreCase(tenantId, value));
    }

    /** True while the expiry exists and lies in the future. A null expiry refuses (fail-closed). */
    private boolean isUsable(Instant expiresAt) {
        return expiresAt != null && clock.instant().isBefore(expiresAt);
    }

    /** Resolves a RAW mailed code to its owner by stored SHA-256 (R-44); blank input matches nothing. */
    private Optional<User> findByCodeHash(String jpql, String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            return Optional.empty();
        }
        return entityManager.createQuery(jpql, User.class)
                .setParameter("hash", AccountRecoveryCodes.sha256(rawCode.trim()))
                .setMaxResults(1)
                .getResultList()
                .stream()
                .findFirst();
    }

    /**
     * Disables the tenant/host Hibernate filters that the tenancy aspect enables for @Service methods,
     * so code/name-driven lookups can resolve users across any tenant.
     */
    private void disableTenantFilters() {
        Session session = entityManager.unwrap(Session.class);
        session.disableFilter("tenantFilter");
        session.disableFilter("hostFilter");
    }

    private String subject(String key) {
        return messageSource.getMessage(key, null, key, LocaleContextHolder.getLocale());
    }

}
