package com.mycompanyname.zero.identity.invitation;

import com.mycompanyname.zero.identity.auth.CurrentUser;
import com.mycompanyname.zero.identity.domain.Role;
import com.mycompanyname.zero.identity.domain.User;
import com.mycompanyname.zero.identity.invitation.web.dto.AcceptInvitationRequest;
import com.mycompanyname.zero.identity.invitation.web.dto.InvitationDto;
import com.mycompanyname.zero.identity.invitation.web.dto.InvitationInfoDto;
import com.mycompanyname.zero.identity.invitation.web.dto.InviteUserRequest;
import com.mycompanyname.zero.identity.password.PasswordHistoryService;
import com.mycompanyname.zero.identity.password.PasswordPolicy;
import com.mycompanyname.zero.identity.password.PasswordPolicyValidator;
import com.mycompanyname.zero.identity.repo.RoleRepository;
import com.mycompanyname.zero.identity.repo.UserRepository;
import com.mycompanyname.zero.notification.email.EmailSender;
import com.mycompanyname.zero.notification.email.EmailTemplateService;
import com.mycompanyname.zero.saas.api.FeatureChecker;
import com.mycompanyname.zero.saas.api.SaasFeatures;
import com.mycompanyname.zero.shared.domain.DomainException;
import com.mycompanyname.zero.shared.domain.ErrorCode;
import com.mycompanyname.zero.shared.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Session;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * User invitation flow: an admin holding {@code users.create} mails a single-use, expiring
 * token; the invitee follows the link, sees the username the admin fixed, chooses a password, and
 * the account is created active on accept.
 *
 * <p><b>Two exposure modes, one class.</b> The admin surface (invite/list/resend/revoke) runs in an
 * authenticated tenant (or host) context and leans on the normal tenant discipline: explicit
 * tenant-scoped queries plus the aspect-enabled Hibernate filters. The accept surface is anonymous
 * — no {@link TenantContext} — so, exactly like {@code AccountService}, the token-driven lookups
 * disable the tenant/host filters per call and run under the aspect's {@code app.is_host='on'} GUC
 * (which the V15 policy passes on purpose).
 *
 * <p><b>The token.</b> 32 random bytes, base64url, mailed once; only its SHA-256 is stored — the
 * same pattern V14 applies to the reset/confirmation codes (RISK-REGISTER R-44). Single use is
 * enforced by {@link UserInvitationRepository#transition} (guarded UPDATE, affected-rows==1),
 * never by a read-then-write.
 *
 * <p><b>Seats.</b> {@code app.maxUserCount} is re-checked AT ACCEPT TIME against the invitation's
 * tenant: pending invitations hold no seat, so the invite-time state proves nothing about the
 * accept-time state.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class InvitationService {

    /** 72h — the upper end of the agreed 24-72h window, so an overnight invite survives a weekend start. */
    static final Duration INVITATION_VALIDITY = Duration.ofHours(72);

    /**
     * One non-oracle refusal for every unusable-token shape (unknown, expired, revoked): naming the
     * exact reason would tell a token-guessing caller which tokens EXIST.
     */
    static final String INVALID_INVITATION_MESSAGE = "Invalid or expired invitation";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private final UserInvitationRepository invitationRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicyValidator passwordPolicyValidator;
    private final PasswordHistoryService passwordHistoryService;
    private final FeatureChecker featureChecker;
    private final EmailSender emailSender;
    private final EmailTemplateService emailTemplateService;
    private final MessageSource messageSource;
    private final Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    // -------------------------------------------------------------------------------------------
    // Admin surface (authenticated, users.create)
    // -------------------------------------------------------------------------------------------

    public InvitationDto invite(InviteUserRequest request) {
        Long tenantId = TenantContext.getTenantId();
        String username = request.username().trim();
        String email = request.email().trim();

        requireIdentityFree(tenantId, username, email);
        // Fail at invite time on a role that does not exist — the invitee cannot fix a typo the
        // admin made, so accept time is too late to learn about it.
        Set<Role> roles = resolveRoles(tenantId, request.roleNames());

        UserInvitation invitation = new UserInvitation();
        invitation.setTenantId(tenantId);
        invitation.setUsername(username);
        invitation.setEmail(email);
        invitation.setRoleNames(joinRoleNames(roles));
        String token = randomToken();
        invitation.setTokenHash(sha256(token));
        invitation.setStatus(InvitationStatus.PENDING);
        invitation.setExpiresAt(clock.instant().plus(INVITATION_VALIDITY));
        invitation.setInvitedByUserId(CurrentUser.userId());
        try {
            // Flushed here so a concurrent duplicate hits the partial unique index INSIDE this
            // method and can be answered 409 — deferred to commit it would surface after the mail
            // was already sent.
            invitationRepository.saveAndFlush(invitation);
        } catch (DataIntegrityViolationException e) {
            throw new DomainException(ErrorCode.CONFLICT,
                    "A pending invitation already exists for this username or email");
        }
        sendInvitationEmail(invitation, token);
        return toDto(invitation);
    }

    @Transactional(readOnly = true)
    public Page<InvitationDto> list(Pageable pageable) {
        Long tenantId = TenantContext.getTenantId();
        Page<UserInvitation> page = tenantId == null
                ? invitationRepository.findAllByTenantIdIsNull(pageable)
                : invitationRepository.findAllByTenantId(tenantId, pageable);
        return page.map(this::toDto);
    }

    /**
     * Re-issues the token of a PENDING invitation (typically an expired one) and extends its
     * validity. The previous token stops working by construction: the stored hash is overwritten.
     */
    public InvitationDto resend(Long id) {
        UserInvitation invitation = getInCurrentTenantOrThrow(id);
        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new DomainException(ErrorCode.CONFLICT,
                    "Only a pending invitation can be re-sent");
        }
        String token = randomToken();
        invitation.setTokenHash(sha256(token));
        invitation.setExpiresAt(clock.instant().plus(INVITATION_VALIDITY));
        invitationRepository.save(invitation);
        sendInvitationEmail(invitation, token);
        return toDto(invitation);
    }

    public InvitationDto revoke(Long id) {
        UserInvitation invitation = getInCurrentTenantOrThrow(id);
        if (invitationRepository.transition(id, InvitationStatus.PENDING, InvitationStatus.REVOKED) != 1) {
            throw new DomainException(ErrorCode.CONFLICT,
                    "Only a pending invitation can be revoked");
        }
        // The bulk update bypassed the persistence context; refresh before rendering the DTO so the
        // caller sees the REVOKED it just caused.
        entityManager.refresh(invitation);
        return toDto(invitation);
    }

    // -------------------------------------------------------------------------------------------
    // Anonymous surface (token is the credential)
    // -------------------------------------------------------------------------------------------

    /**
     * What the accept screen renders before asking for a password. PENDING answers the username and
     * e-mail; ACCEPTED answers just the status so the screen can point at sign-in; everything else
     * is the one non-oracle 400.
     */
    @Transactional(readOnly = true)
    public InvitationInfoDto invitationInfo(String token) {
        disableTenantFilters();
        UserInvitation invitation = findUsable(token);
        if (invitation.getStatus() == InvitationStatus.ACCEPTED) {
            return new InvitationInfoDto(null, null, InvitationStatus.ACCEPTED.name());
        }
        return new InvitationInfoDto(
                invitation.getUsername(), invitation.getEmail(), invitation.getStatus().name());
    }

    /**
     * Creates the invited account. Returns without error — a no-op — when the invitation was
     * already accepted, or when a live user already owns the invited e-mail (the admin created the
     * account manually between invite and accept): in both shapes "the account exists" is the
     * outcome the invitee wanted, and the screen sends them to sign-in.
     */
    public void accept(AcceptInvitationRequest request) {
        disableTenantFilters();
        UserInvitation invitation = findUsable(request.token());
        if (invitation.getStatus() == InvitationStatus.ACCEPTED) {
            return;
        }
        Long tenantId = invitation.getTenantId();

        Optional<User> existingByEmail = findByEmail(tenantId, invitation.getEmail());
        if (existingByEmail.isPresent()) {
            if (claim(invitation) == 1) {
                linkAcceptedUser(invitation, existingByEmail.get());
            }
            return;
        }

        enforceMaxUserCount(tenantId);

        PasswordPolicy policy = passwordPolicyValidator.resolvePolicy(tenantId, null);
        passwordPolicyValidator.validate(policy, request.password());

        // Same session discipline as claim(): the password machinery above is a nested @Service
        // and re-armed the host filter, which would make this existence check blind to every
        // tenant user (a real conflict would then surface as the unique index's 500, not a 409).
        disableTenantFilters();
        boolean usernameTaken = tenantId == null
                ? userRepository.existsByUsernameIgnoreCaseAndTenantIdIsNull(invitation.getUsername())
                : userRepository.existsByTenantIdAndUsernameIgnoreCase(tenantId, invitation.getUsername());
        if (usernameTaken) {
            throw new DomainException(ErrorCode.CONFLICT,
                    "Username already exists: " + invitation.getUsername());
        }

        // Claim BEFORE creating the user: of two concurrent accepts exactly one sees 1 row here,
        // and a claim whose user creation later fails rolls back with it (same transaction).
        if (claim(invitation) != 1) {
            throw DomainException.validation(INVALID_INVITATION_MESSAGE);
        }

        User user = new User();
        user.setTenantId(tenantId);
        user.setUsername(invitation.getUsername());
        user.setEmail(invitation.getEmail());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setActive(true);
        // The invitation itself travelled to this address and its token came back — that IS the
        // confirmation the email-confirmation flow exists to obtain, so the separate
        // App.UserManagement.IsEmailConfirmationRequired round-trip is deliberately skipped here.
        user.setEmailConfirmed(true);
        user.setShouldChangePassword(false);
        user.setLastPasswordChangeAt(clock.instant());
        user.getRoles().addAll(resolveRoles(tenantId, splitRoleNames(invitation.getRoleNames())));
        User saved = userRepository.save(user);
        passwordHistoryService.record(saved.getId(), saved.getPasswordHash());
        linkAcceptedUser(invitation, saved);
        log.info("Invitation {} accepted; user {} created", invitation.getId(), saved.getId());
    }

    // -------------------------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------------------------

    /**
     * Resolves a raw token to an invitation that is PENDING-and-unexpired or ACCEPTED. Unknown,
     * revoked and expired all collapse into {@link #INVALID_INVITATION_MESSAGE}.
     */
    private UserInvitation findUsable(String token) {
        if (token == null || token.isBlank()) {
            throw DomainException.validation(INVALID_INVITATION_MESSAGE);
        }
        UserInvitation invitation = invitationRepository.findByTokenHash(sha256(token.trim()))
                .orElseThrow(() -> DomainException.validation(INVALID_INVITATION_MESSAGE));
        if (invitation.getStatus() == InvitationStatus.REVOKED) {
            throw DomainException.validation(INVALID_INVITATION_MESSAGE);
        }
        if (invitation.getStatus() == InvitationStatus.PENDING
                && clock.instant().isAfter(invitation.getExpiresAt())) {
            throw DomainException.validation(INVALID_INVITATION_MESSAGE);
        }
        return invitation;
    }

    private void requireIdentityFree(Long tenantId, String username, String email) {
        boolean usernameTaken = tenantId == null
                ? userRepository.existsByUsernameIgnoreCaseAndTenantIdIsNull(username)
                : userRepository.existsByTenantIdAndUsernameIgnoreCase(tenantId, username);
        if (usernameTaken) {
            throw new DomainException(ErrorCode.CONFLICT, "Username already exists: " + username);
        }
        if (findByEmail(tenantId, email).isPresent()) {
            throw new DomainException(ErrorCode.CONFLICT, "A user with this email already exists");
        }
        boolean pendingForEmail = tenantId == null
                ? invitationRepository.existsByTenantIdIsNullAndEmailIgnoreCaseAndStatus(
                        email, InvitationStatus.PENDING)
                : invitationRepository.existsByTenantIdAndEmailIgnoreCaseAndStatus(
                        tenantId, email, InvitationStatus.PENDING);
        boolean pendingForUsername = tenantId == null
                ? invitationRepository.existsByTenantIdIsNullAndUsernameIgnoreCaseAndStatus(
                        username, InvitationStatus.PENDING)
                : invitationRepository.existsByTenantIdAndUsernameIgnoreCaseAndStatus(
                        tenantId, username, InvitationStatus.PENDING);
        if (pendingForEmail || pendingForUsername) {
            throw new DomainException(ErrorCode.CONFLICT,
                    "A pending invitation already exists for this username or email");
        }
    }

    /**
     * The PENDING→ACCEPTED transition of the anonymous accept flow.
     *
     * <p>⚠️ The filters are re-disabled HERE, immediately before the statement, not only at the
     * method entry — and that placement is load-bearing, measured red without it (in the project
     * this flow was ported from): every nested {@code @Service} call on the way (the feature
     * checker, the password machinery) passes through {@code HibernateTenantFilterAspect}, which
     * unconditionally RE-ENABLES the filter for its own decision on the shared session and never
     * switches it back off. Hibernate 6 applies enabled filters to HQL bulk mutations too, so a
     * claim issued after any nested service call silently ran as {@code ... and tenant_id is null}
     * and answered 0 rows for every tenant invitation — "Invalid or expired invitation" for a
     * perfectly valid token ({@code InvitationFlowIT} caught it; the SELECT half never failed
     * because it runs before the first nested call).
     */
    private int claim(UserInvitation invitation) {
        disableTenantFilters();
        return invitationRepository.transition(
                invitation.getId(), InvitationStatus.PENDING, InvitationStatus.ACCEPTED);
    }

    /** Same session discipline as {@link #claim}: a nested service ran in between (history record). */
    private void linkAcceptedUser(UserInvitation invitation, User user) {
        disableTenantFilters();
        invitationRepository.linkAcceptedUser(invitation.getId(), user.getId());
    }

    private Optional<User> findByEmail(Long tenantId, String email) {
        return tenantId == null
                ? userRepository.findByEmailIgnoreCaseAndTenantIdIsNull(email)
                : userRepository.findByTenantIdAndEmailIgnoreCase(tenantId, email);
    }

    /**
     * Accept-time twin of {@code UserService.enforceMaxUserCount}, resolved for an EXPLICIT tenant:
     * the anonymous accept request carries no {@link TenantContext}, so the context-bound
     * {@code FeatureChecker.intValue} would price the wrong (host) scope.
     */
    private void enforceMaxUserCount(Long tenantId) {
        if (tenantId == null) {
            return;
        }
        int maxUserCount = parseIntOrZero(featureChecker.valueFor(tenantId, SaasFeatures.MAX_USER_COUNT));
        if (maxUserCount <= 0) {
            return;
        }
        // The feature checker is a nested @Service: the tenancy aspect just RE-ARMED the host
        // filter on this session (anonymous request → hostFilter), which would conjoin
        // `tenant_id is null` onto the count below and report 0 live users for every tenant —
        // i.e. no limit would ever bite. Measured red in InvitationFlowIT's seat test.
        disableTenantFilters();
        long currentUserCount = userRepository.countByTenantId(tenantId);
        if (currentUserCount >= maxUserCount) {
            throw new DomainException(ErrorCode.VALIDATION,
                    "The tenant has reached the maximum number of users allowed by its package ("
                            + maxUserCount + ")");
        }
    }

    private static int parseIntOrZero(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Set<Role> resolveRoles(Long tenantId, Set<String> roleNames) {
        Set<Role> roles = new LinkedHashSet<>();
        if (roleNames == null) {
            return roles;
        }
        for (String roleName : roleNames) {
            Role role = (tenantId == null
                    ? roleRepository.findByNameIgnoreCaseAndTenantIdIsNull(roleName)
                    : roleRepository.findByTenantIdAndNameIgnoreCase(tenantId, roleName))
                    .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Role not found: " + roleName));
            roles.add(role);
        }
        return roles;
    }

    /** Comma-joined canonical names; a comma inside a name would corrupt the encoding, so it is refused. */
    private static String joinRoleNames(Set<Role> roles) {
        StringBuilder joined = new StringBuilder();
        for (Role role : roles) {
            if (role.getName().contains(",")) {
                throw DomainException.validation(
                        "Role names containing ',' cannot be used in an invitation: " + role.getName());
            }
            if (!joined.isEmpty()) {
                joined.append(',');
            }
            joined.append(role.getName());
        }
        return joined.toString();
    }

    private static Set<String> splitRoleNames(String joined) {
        Set<String> names = new LinkedHashSet<>();
        if (joined != null && !joined.isBlank()) {
            Arrays.stream(joined.split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(names::add);
        }
        return names;
    }

    private UserInvitation getInCurrentTenantOrThrow(Long id) {
        Long tenantId = TenantContext.getTenantId();
        return invitationRepository.findById(id)
                .filter(invitation -> Objects.equals(invitation.getTenantId(), tenantId))
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Invitation not found: " + id));
    }

    private void sendInvitationEmail(UserInvitation invitation, String token) {
        String body = emailTemplateService.invitation(
                invitation.getUsername(), token, INVITATION_VALIDITY.toHours());
        emailSender.send(invitation.getEmail(), subject("Email.Invitation.Subject"), body);
    }

    private InvitationDto toDto(UserInvitation invitation) {
        return new InvitationDto(
                invitation.getId(),
                invitation.getUsername(),
                invitation.getEmail(),
                splitRoleNames(invitation.getRoleNames()),
                invitation.getStatus().name(),
                invitation.getExpiresAt(),
                invitation.getCreatedAt());
    }

    /** Same per-call discipline as {@code AccountService}: token lookups are inherently cross-tenant. */
    private void disableTenantFilters() {
        Session session = entityManager.unwrap(Session.class);
        session.disableFilter("tenantFilter");
        session.disableFilter("hostFilter");
    }

    private String subject(String key) {
        return messageSource.getMessage(key, null, key, LocaleContextHolder.getLocale());
    }

    private static String randomToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // Every JVM ships SHA-256 (JCA requirement); reaching this line is a broken runtime.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
