package com.mycompanyname.zero.identity.auth;

import com.mycompanyname.zero.config.FieldEncryptionService;
import com.mycompanyname.zero.config.JwtProperties;
import com.mycompanyname.zero.config.TwoFactorProperties;
import com.mycompanyname.zero.identity.domain.RefreshToken;
import com.mycompanyname.zero.identity.domain.Role;
import com.mycompanyname.zero.identity.domain.TwoFactorChallenge;
import com.mycompanyname.zero.identity.domain.User;
import com.mycompanyname.zero.identity.repo.RefreshTokenRepository;
import com.mycompanyname.zero.identity.repo.TwoFactorChallengeRepository;
import com.mycompanyname.zero.identity.repo.UserRepository;
import com.mycompanyname.zero.identity.web.dto.LoginRequest;
import com.mycompanyname.zero.identity.web.dto.LoginResultDto;
import com.mycompanyname.zero.identity.web.dto.MeDto;
import com.mycompanyname.zero.identity.web.dto.RefreshRequest;
import com.mycompanyname.zero.identity.web.dto.TokenPairDto;
import com.mycompanyname.zero.identity.web.dto.TwoFactorChallengeDto;
import com.mycompanyname.zero.identity.web.dto.TwoFactorVerifyRequest;
import com.mycompanyname.zero.settings.SettingManager;
import com.mycompanyname.zero.shared.domain.DomainException;
import com.mycompanyname.zero.shared.domain.ErrorCode;
import com.mycompanyname.zero.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
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
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class AuthService {

    private static final int DEFAULT_MAX_FAILED_ATTEMPTS = 5;
    private static final long DEFAULT_LOCKOUT_DURATION_SECONDS = 300;
    private static final String SETTING_LOCKOUT_MAX_FAILED_ATTEMPTS = "App.Auth.LockoutMaxFailedAttempts";
    private static final String SETTING_LOCKOUT_DURATION_SECONDS = "App.Auth.LockoutDurationSeconds";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int REFRESH_TOKEN_BYTES = 32;
    private static final int CHALLENGE_TOKEN_BYTES = 32;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final JwtProperties jwtProperties;
    private final SettingManager settingManager;
    private final TwoFactorChallengeRepository twoFactorChallengeRepository;
    private final TwoFactorProperties twoFactorProperties;
    private final TotpService totpService;
    private final RecoveryCodeService recoveryCodeService;
    private final FieldEncryptionService fieldEncryptionService;
    private final Clock clock;
    /** Present only when zero.jwt.revocation.enabled is true; a no-op otherwise (PROD-R16). */
    private final ObjectProvider<TokenRevocationService> revocationServices;

    @Transactional(noRollbackFor = DomainException.class)
    public LoginResultDto login(LoginRequest request) {
        Long tenantId = TenantContext.getTenantId();
        String usernameOrEmail = request.usernameOrEmail().trim();
        User user = findUser(tenantId, usernameOrEmail)
                .orElseThrow(() -> new DomainException(ErrorCode.LOGIN_FAILED, "Invalid credentials"));
        if (!user.isActive()) {
            throw new DomainException(ErrorCode.LOGIN_FAILED, "Invalid credentials");
        }
        Instant now = Instant.now();
        if (user.getLockoutEndAt() != null) {
            if (user.getLockoutEndAt().isAfter(now)) {
                throw new DomainException(ErrorCode.ACCOUNT_LOCKED, "Account is temporarily locked");
            }
            // Lockout window elapsed: this (or a successful login) is the ONLY place the
            // failed-attempt counter resets. Locking must not reset it, otherwise every lockout
            // would grant a fresh batch of attempts.
            user.setLockoutEndAt(null);
            user.setFailedLoginAttempts(0);
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            int failed = user.getFailedLoginAttempts() + 1;
            user.setFailedLoginAttempts(failed);
            if (failed >= maxFailedAttempts(user.getTenantId())) {
                user.setLockoutEndAt(now.plus(lockoutDuration(user.getTenantId())));
            }
            userRepository.save(user);
            throw new DomainException(ErrorCode.LOGIN_FAILED, "Invalid credentials");
        }
        // THE 2FA GATE. The password is correct, but when 2FA is enabled NO session token is minted
        // here under any circumstance: the caller gets an opaque challenge instead and must redeem it
        // at /api/auth/two-factor/verify with a TOTP or recovery code. Non-2FA users take the
        // unchanged path and get exactly the token pair they got before 2FA existed.
        if (!user.isTwoFactorEnabled()) {
            // A correct password IS the full authentication for a non-2FA user, so the failed-attempt
            // counter and any residual lockout clear here.
            user.setFailedLoginAttempts(0);
            user.setLockoutEndAt(null);
            return LoginResultDto.authenticated(issueTokenPair(user));
        }
        // 2FA is enabled: the SECOND FACTOR IS STILL UNPROVEN, so the counter must NOT be reset here.
        // Resetting at first-factor success would let an attacker holding a leaked password but no
        // authenticator zero the lockout counter on every re-login and brute-force the 6-digit code
        // without bound (stack-review Finding 1). Only verifyTwoFactor success — the real full
        // authentication — clears it. The lockout CHECK above still runs, so a locked account cannot
        // even begin the first factor.
        return LoginResultDto.challenge(createTwoFactorChallenge(user));
    }

    /**
     * Second factor of login. Redeems a challenge minted by {@link #login} against a TOTP or recovery
     * code and, only on success, mints the session at the SAME {@link #issueTokenPair} site login uses.
     *
     * <p>Fail-closed and oracle-free: an unknown, expired, consumed or exhausted challenge, an inactive
     * or no-longer-enrolled user, a wrong code, and any internal error on the verify path (a decrypt
     * failure on a corrupted secret) all produce the identical generic {@code 401} with no hint as to
     * which of them it was. A wrong code decrements the challenge — invalidating it at zero — and counts
     * toward the same per-account lockout the password step uses; {@code noRollbackFor} commits those
     * mutations despite the 401.
     */
    @Transactional(noRollbackFor = DomainException.class)
    public TokenPairDto verifyTwoFactor(TwoFactorVerifyRequest request) {
        // FOR UPDATE: serialises the whole verify per challenge so concurrent redemptions cannot race
        // the attempts decrement or double-spend the challenge (stack-review Finding 2).
        TwoFactorChallenge challenge = twoFactorChallengeRepository
                .findByTokenHashForUpdate(sha256Hex(request.challengeToken()))
                .orElseThrow(AuthService::twoFactorFailed);
        Instant now = clock.instant();
        if (challenge.getConsumedAt() != null
                || challenge.getExpiresAt().isBefore(now)
                || challenge.getAttemptsRemaining() <= 0) {
            throw twoFactorFailed();
        }
        User user = userRepository.findById(challenge.getUserId())
                .filter(User::isActive)
                .filter(User::isTwoFactorEnabled)
                .orElse(null);
        if (user == null) {
            // The challenge points at a user who can no longer complete 2FA. Burn it, fail closed.
            challenge.setConsumedAt(now);
            twoFactorChallengeRepository.save(challenge);
            throw twoFactorFailed();
        }
        if (user.getLockoutEndAt() != null && user.getLockoutEndAt().isAfter(now)) {
            throw twoFactorFailed();
        }
        if (verifyTwoFactorCode(user, request.code())) {
            challenge.setConsumedAt(now);
            twoFactorChallengeRepository.save(challenge);
            user.setFailedLoginAttempts(0);
            user.setLockoutEndAt(null);
            userRepository.save(user);
            return issueTokenPair(user);
        }
        int remaining = challenge.getAttemptsRemaining() - 1;
        challenge.setAttemptsRemaining(remaining);
        if (remaining <= 0) {
            challenge.setConsumedAt(now);
        }
        twoFactorChallengeRepository.save(challenge);
        registerFailedTwoFactorAttempt(user, now);
        throw twoFactorFailed();
    }

    @Transactional(noRollbackFor = DomainException.class)
    public TokenPairDto refresh(RefreshRequest request) {
        String tokenHash = sha256Hex(request.refreshToken());
        RefreshToken stored = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new DomainException(ErrorCode.UNAUTHORIZED, "Invalid refresh token"));
        if (stored.isRevoked()) {
            // Reuse of an already-rotated token: treat as theft and revoke the whole family.
            // noRollbackFor ensures this cascade revocation is committed despite the 401.
            refreshTokenRepository.revokeAllByUserId(stored.getUserId());
            throw new DomainException(ErrorCode.UNAUTHORIZED, "Refresh token is no longer valid");
        }
        if (stored.getExpiresAt().isBefore(Instant.now())) {
            throw new DomainException(ErrorCode.UNAUTHORIZED, "Refresh token is no longer valid");
        }
        User user = userRepository.findById(stored.getUserId())
                .filter(User::isActive)
                .orElseThrow(() -> new DomainException(ErrorCode.UNAUTHORIZED, "User is not active"));
        // Atomic rotation: only the caller that flips revoked=false->true wins; a concurrent
        // presenter of the same token loses the race and is treated as reuse.
        if (refreshTokenRepository.revokeIfActive(stored.getId()) == 0) {
            refreshTokenRepository.revokeAllByUserId(stored.getUserId());
            throw new DomainException(ErrorCode.UNAUTHORIZED, "Refresh token is no longer valid");
        }
        return issueTokenPair(user);
    }

    /**
     * Revokes a refresh token, but only the caller's own.
     *
     * <p>R-39. The endpoint is reachable by every authenticated principal (it is not in the
     * {@code permitAll} set, and nothing narrower claims it), so authentication alone said nothing
     * about <em>whose</em> token was being presented: holding the opaque string was the entire
     * authorization. Anyone who obtained another user's refresh token — a proxy log, a shared
     * device, a copied support ticket — could end that user's session. Not a leak, a cross-user
     * availability attack. This is also the rigour {@link #refresh} already applies: there, a
     * presented token that is not in a valid state for its own family gets the whole family
     * revoked; logout did not share it.
     *
     * <p><b>Why a foreign token still answers 204.</b> An unknown token already returned 204, and
     * the two answers are deliberately kept identical. Replying 403/404 for a token that exists but
     * belongs to someone else would turn logout into an existence oracle: the difference in status
     * confirms "this string IS a live refresh token", which is precisely what an attacker holding a
     * candidate string wants to learn. The caller loses nothing by the ambiguity — logout is
     * idempotent from its point of view and it has no legitimate need to distinguish a token it
     * does not own from one that never existed. The mismatch is instead surfaced where an operator
     * can act on it: one WARN line, carrying neither the token nor its hash.
     */
    public void logout(String refreshToken) {
        Long callerUserId = CurrentUser.userId();
        if (callerUserId == null) {
            throw new DomainException(ErrorCode.UNAUTHORIZED, "Authentication required");
        }
        // PROD-R16: revoke the presented ACCESS token too, not only the refresh token. Without this,
        // logout leaves the bearer token usable until its ~15-minute expiry — the exact window this
        // closes. Self-only (the jti is the caller's own, taken from its authenticated context) and
        // best-effort; no-op when revocation is disabled.
        revocationServices.ifAvailable(service ->
                service.revokeAccessToken(CurrentUser.jti(), CurrentUser.expiresAt()));
        refreshTokenRepository.findByTokenHash(sha256Hex(refreshToken))
                .ifPresent(token -> {
                    if (!Objects.equals(token.getUserId(), callerUserId)) {
                        log.warn("Rejected logout: the presented refresh token belongs to another user "
                                + "(caller userId={})", callerUserId);
                        return;
                    }
                    token.setRevoked(true);
                    refreshTokenRepository.save(token);
                });
    }

    @Transactional(readOnly = true)
    public MeDto me() {
        Long userId = CurrentUser.userId();
        if (userId == null) {
            throw new DomainException(ErrorCode.UNAUTHORIZED, "Authentication required");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new DomainException(ErrorCode.UNAUTHORIZED, "User no longer exists"));
        Set<String> roles = new LinkedHashSet<>();
        Set<String> permissions = new LinkedHashSet<>();
        for (Role role : user.getRoles()) {
            roles.add(role.getName());
            permissions.addAll(role.getPermissions());
        }
        return new MeDto(user.getId(), user.getUsername(), user.getEmail(), user.getTenantId(),
                user.isShouldChangePassword(), roles, permissions, user.isTwoFactorEnabled());
    }

    private int maxFailedAttempts(Long tenantId) {
        return intSetting(SETTING_LOCKOUT_MAX_FAILED_ATTEMPTS, tenantId, DEFAULT_MAX_FAILED_ATTEMPTS);
    }

    private Duration lockoutDuration(Long tenantId) {
        return Duration.ofSeconds(longSetting(SETTING_LOCKOUT_DURATION_SECONDS, tenantId, DEFAULT_LOCKOUT_DURATION_SECONDS));
    }

    private int intSetting(String name, Long tenantId, int fallback) {
        String value = settingManager.getOrDefault(name, tenantId, null);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private long longSetting(String name, Long tenantId, long fallback) {
        String value = settingManager.getOrDefault(name, tenantId, null);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private TokenPairDto issueTokenPair(User user) {
        Set<String> authorities = authoritiesOf(user);
        String accessToken = jwtService.issueAccessToken(user, authorities);
        String rawRefreshToken = createRefreshToken(user.getId());
        return new TokenPairDto(accessToken, rawRefreshToken, jwtProperties.getAccessTokenTtl().toSeconds());
    }

    /**
     * Mints a pre-login challenge: a random opaque token handed back once, stored only as its SHA-256
     * hash (the refresh-token pattern), bounded by {@code challenge-ttl} and {@code max-attempts}.
     */
    private TwoFactorChallengeDto createTwoFactorChallenge(User user) {
        byte[] randomBytes = new byte[CHALLENGE_TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(randomBytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        TwoFactorChallenge challenge = new TwoFactorChallenge();
        challenge.setUserId(user.getId());
        challenge.setTokenHash(sha256Hex(raw));
        challenge.setExpiresAt(clock.instant().plus(twoFactorProperties.getChallengeTtl()));
        challenge.setAttemptsRemaining(twoFactorProperties.getMaxAttempts());
        twoFactorChallengeRepository.save(challenge);
        return new TwoFactorChallengeDto(raw, twoFactorProperties.getChallengeTtl().toSeconds());
    }

    /**
     * Tries {@code code} as a TOTP first, then as a recovery code. Fail-closed throughout: a decrypt
     * failure on a corrupted secret fails the TOTP path silently (never a 500, never an oracle) and the
     * recovery-code path is still attempted; any unexpected error there is a false, not a leak.
     */
    private boolean verifyTwoFactorCode(User user, String code) {
        try {
            String secret = fieldEncryptionService.decrypt(user.getTwoFactorSecret());
            if (secret != null && totpService.verify(secret, code)) {
                return true;
            }
        } catch (RuntimeException ex) {
            log.warn("2FA TOTP path errored for userId={}; failing that path closed", user.getId());
        }
        try {
            return recoveryCodeService.consumeIfValid(user.getId(), code);
        } catch (RuntimeException ex) {
            log.warn("2FA recovery-code path errored for userId={}; failing closed", user.getId());
            return false;
        }
    }

    /** Counts a wrong second factor toward the same per-account lockout the password step uses. */
    private void registerFailedTwoFactorAttempt(User user, Instant now) {
        int failed = user.getFailedLoginAttempts() + 1;
        user.setFailedLoginAttempts(failed);
        if (failed >= maxFailedAttempts(user.getTenantId())) {
            user.setLockoutEndAt(now.plus(lockoutDuration(user.getTenantId())));
        }
        userRepository.save(user);
    }

    /** The one generic 2FA rejection: every failure mode returns this identical 401, no oracle. */
    private static DomainException twoFactorFailed() {
        return new DomainException(ErrorCode.LOGIN_FAILED, "Two-factor authentication failed");
    }

    private String createRefreshToken(Long userId) {
        byte[] randomBytes = new byte[REFRESH_TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(randomBytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        RefreshToken token = new RefreshToken();
        token.setUserId(userId);
        token.setTokenHash(sha256Hex(raw));
        token.setExpiresAt(Instant.now().plus(jwtProperties.getRefreshTokenTtl()));
        token.setRevoked(false);
        refreshTokenRepository.save(token);
        return raw;
    }

    private Optional<User> findUser(Long tenantId, String usernameOrEmail) {
        if (tenantId == null) {
            return userRepository.findByUsernameIgnoreCaseAndTenantIdIsNull(usernameOrEmail)
                    .or(() -> userRepository.findByEmailIgnoreCaseAndTenantIdIsNull(usernameOrEmail));
        }
        return userRepository.findByTenantIdAndUsernameIgnoreCase(tenantId, usernameOrEmail)
                .or(() -> userRepository.findByTenantIdAndEmailIgnoreCase(tenantId, usernameOrEmail));
    }

    private Set<String> authoritiesOf(User user) {
        Set<String> authorities = new HashSet<>();
        for (Role role : user.getRoles()) {
            authorities.addAll(role.getPermissions());
            authorities.add("ROLE_" + role.getName());
        }
        return authorities;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
