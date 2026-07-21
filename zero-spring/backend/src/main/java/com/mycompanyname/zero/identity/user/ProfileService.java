package com.mycompanyname.zero.identity.user;

import com.mycompanyname.zero.identity.auth.CurrentUser;
import com.mycompanyname.zero.identity.auth.TokenRevocationService;
import com.mycompanyname.zero.identity.domain.Role;
import com.mycompanyname.zero.identity.domain.User;
import com.mycompanyname.zero.identity.password.PasswordHistoryService;
import com.mycompanyname.zero.identity.password.PasswordPolicyValidator;
import com.mycompanyname.zero.identity.repo.UserRepository;
import com.mycompanyname.zero.identity.web.dto.ChangePasswordRequest;
import com.mycompanyname.zero.identity.web.dto.ProfileDto;
import com.mycompanyname.zero.identity.web.dto.UpdateProfileRequest;
import com.mycompanyname.zero.notification.email.EmailSender;
import com.mycompanyname.zero.notification.email.EmailTemplateService;
import com.mycompanyname.zero.shared.domain.DomainException;
import com.mycompanyname.zero.shared.domain.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Self-service profile management for the currently authenticated user. Operates strictly on the
 * caller's own account (resolved from the JWT subject), never on an arbitrary id.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ProfileService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicyValidator passwordPolicyValidator;
    private final PasswordHistoryService passwordHistoryService;
    private final EmailSender emailSender;
    private final EmailTemplateService emailTemplateService;
    private final MessageSource messageSource;
    /** Present only when zero.jwt.revocation.enabled is true; a no-op otherwise (PROD-R16). */
    private final ObjectProvider<TokenRevocationService> revocationServices;

    @Transactional(readOnly = true)
    public ProfileDto getProfile() {
        return toDto(currentUser());
    }

    public ProfileDto updateProfile(UpdateProfileRequest request) {
        User user = currentUser();
        if (request.name() != null) {
            user.setName(request.name());
        }
        if (request.surname() != null) {
            user.setSurname(request.surname());
        }
        if (request.phoneNumber() != null) {
            user.setPhoneNumber(request.phoneNumber());
        }
        if (request.email() != null
                && !request.email().isBlank()
                && !request.email().equalsIgnoreCase(user.getEmail())) {
            // Changing the email invalidates confirmation; a fresh code is issued and a confirmation
            // message is dispatched to the new address.
            String code = newCode();
            user.setEmail(request.email());
            user.setEmailConfirmed(false);
            user.setEmailConfirmationCode(code);
            sendEmailConfirmation(user, code);
        }
        return toDto(userRepository.save(user));
    }

    public void changePassword(ChangePasswordRequest request) {
        User user = currentUser();
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new DomainException(ErrorCode.VALIDATION, "Current password is incorrect");
        }
        // (a) enforce the settings-backed password policy on the new password
        passwordPolicyValidator.validate(request.newPassword());
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new DomainException(ErrorCode.VALIDATION,
                    "New password must be different from the current password");
        }
        // (b) reject reuse of one of the last N passwords
        int historyCount = passwordHistoryService.resolveHistoryCount();
        passwordHistoryService.checkNotRecentlyUsed(user.getId(), request.newPassword(), historyCount);
        // (c) on success: retire the old hash into history, then rotate the password
        String previousHash = user.getPasswordHash();
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setLastPasswordChangeAt(Instant.now());
        user.setShouldChangePassword(false);
        userRepository.save(user);
        passwordHistoryService.record(user.getId(), previousHash);
        // PROD-R16: a credential change kills every outstanding access token for this user (including
        // the one making this request), so a stolen-then-changed password cannot keep a live session.
        // Best-effort; no-op when revocation is disabled.
        revocationServices.ifAvailable(service -> service.revokeAllForUser(user.getId()));
    }

    private void sendEmailConfirmation(User user, String code) {
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            return;
        }
        String body = emailTemplateService.emailConfirmation(displayName(user), code);
        emailSender.send(user.getEmail(), subject("Email.Confirmation.Subject"), body);
    }

    private String subject(String key) {
        return messageSource.getMessage(key, null, key, LocaleContextHolder.getLocale());
    }

    private static String displayName(User user) {
        return user.getName() != null && !user.getName().isBlank() ? user.getName() : user.getUsername();
    }

    private User currentUser() {
        Long userId = CurrentUser.userId();
        if (userId == null) {
            throw new DomainException(ErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return userRepository.findById(userId)
                .orElseThrow(() -> new DomainException(ErrorCode.UNAUTHORIZED, "User no longer exists"));
    }

    private ProfileDto toDto(User user) {
        Set<String> roles = new LinkedHashSet<>();
        for (Role role : user.getRoles()) {
            roles.add(role.getName());
        }
        return new ProfileDto(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getName(),
                user.getSurname(),
                user.getPhoneNumber(),
                user.isEmailConfirmed(),
                user.getTenantId(),
                roles);
    }

    private static String newCode() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
