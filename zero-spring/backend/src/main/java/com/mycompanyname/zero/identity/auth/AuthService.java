package com.mycompanyname.zero.identity.auth;

import com.mycompanyname.zero.config.JwtProperties;
import com.mycompanyname.zero.identity.domain.RefreshToken;
import com.mycompanyname.zero.identity.domain.Role;
import com.mycompanyname.zero.identity.domain.User;
import com.mycompanyname.zero.identity.repo.RefreshTokenRepository;
import com.mycompanyname.zero.identity.repo.UserRepository;
import com.mycompanyname.zero.identity.web.dto.LoginRequest;
import com.mycompanyname.zero.identity.web.dto.MeDto;
import com.mycompanyname.zero.identity.web.dto.RefreshRequest;
import com.mycompanyname.zero.identity.web.dto.TokenPairDto;
import com.mycompanyname.zero.settings.SettingManager;
import com.mycompanyname.zero.shared.domain.DomainException;
import com.mycompanyname.zero.shared.domain.ErrorCode;
import com.mycompanyname.zero.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional
public class AuthService {

    private static final int DEFAULT_MAX_FAILED_ATTEMPTS = 5;
    private static final long DEFAULT_LOCKOUT_DURATION_SECONDS = 300;
    private static final String SETTING_LOCKOUT_MAX_FAILED_ATTEMPTS = "App.Auth.LockoutMaxFailedAttempts";
    private static final String SETTING_LOCKOUT_DURATION_SECONDS = "App.Auth.LockoutDurationSeconds";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int REFRESH_TOKEN_BYTES = 32;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final JwtProperties jwtProperties;
    private final SettingManager settingManager;

    @Transactional(noRollbackFor = DomainException.class)
    public TokenPairDto login(LoginRequest request) {
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
        user.setFailedLoginAttempts(0);
        user.setLockoutEndAt(null);
        return issueTokenPair(user);
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

    public void logout(String refreshToken) {
        refreshTokenRepository.findByTokenHash(sha256Hex(refreshToken))
                .ifPresent(token -> {
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
                user.isShouldChangePassword(), roles, permissions);
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
