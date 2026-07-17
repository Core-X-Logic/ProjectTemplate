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

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Unauthenticated self-service account flows: forgot/reset password and email confirmation. These
 * operations are inherently cross-tenant (driven by a secret code or an explicit tenant name), so the
 * Hibernate tenant/host filter is disabled per call before issuing lookups.
 *
 * <p>Password strength ({@link PasswordPolicyValidator}) and the account fields it mutates are owned
 * by the identity module; email delivery is delegated to the notification module.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class AccountService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int CODE_BYTES = 32;

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicyValidator passwordPolicyValidator;
    private final PasswordHistoryService passwordHistoryService;
    private final EmailSender emailSender;
    private final EmailTemplateService emailTemplateService;
    private final MessageSource messageSource;

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
        String code = randomCode();
        user.setPasswordResetCode(code);
        userRepository.save(user);
        String body = emailTemplateService.passwordReset(user.getUsername(), code);
        emailSender.send(user.getEmail(), subject("Email.PasswordReset.Subject"), body);
    }

    /**
     * Applies a new password given a valid reset code, enforcing the password policy and clearing the
     * consumed code.
     */
    public void resetPassword(String resetCode, String newPassword) {
        disableTenantFilters();
        User user = findSingle("select u from User u where u.passwordResetCode = :code", resetCode)
                .orElseThrow(() -> DomainException.validation("Invalid or expired reset code"));
        // Same policy + history semantics as the authenticated change-password flow, resolved against
        // the target user's tenant (there is no authenticated caller during an anonymous reset).
        PasswordPolicy policy = passwordPolicyValidator.resolvePolicy(user.getTenantId(), null);
        passwordPolicyValidator.validate(policy, newPassword);
        passwordHistoryService.checkNotRecentlyUsed(user.getId(), newPassword, policy.historyCount());
        String previousHash = user.getPasswordHash();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordResetCode(null);
        user.setShouldChangePassword(false);
        user.setLastPasswordChangeAt(Instant.now());
        userRepository.save(user);
        passwordHistoryService.record(user.getId(), previousHash);
    }

    /**
     * Confirms an email address given a valid confirmation code.
     */
    public void confirmEmail(String code) {
        disableTenantFilters();
        User user = findSingle("select u from User u where u.emailConfirmationCode = :code", code)
                .orElseThrow(() -> DomainException.validation("Invalid confirmation code"));
        user.setEmailConfirmed(true);
        user.setEmailConfirmationCode(null);
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

    private Optional<User> findSingle(String jpql, String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return entityManager.createQuery(jpql, User.class)
                .setParameter("code", code)
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

    private static String randomCode() {
        byte[] bytes = new byte[CODE_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
