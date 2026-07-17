package com.mycompanyname.zero.identity.password;

import com.mycompanyname.zero.identity.auth.CurrentUser;
import com.mycompanyname.zero.settings.SettingManager;
import com.mycompanyname.zero.shared.domain.DomainException;
import com.mycompanyname.zero.shared.domain.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Enforces "no reuse of the last N passwords" and records password changes (CONTRACT-phase2 §4.4).
 *
 * <p>{@code N} corresponds to the {@code App.Password.HistoryCount} setting (default
 * {@link PasswordPolicy#DEFAULT_HISTORY_COUNT}). Every path that sets a password
 * (profile change, account reset, admin create/set) must:
 * <ol>
 *   <li>call {@link #checkNotRecentlyUsed(Long, String, int)} with the new raw password, then</li>
 *   <li>after persisting the new hash, call {@link #record(Long, String)} with the replaced (or
 *       initial) hash so the most recent N entries are retained.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Transactional
public class PasswordHistoryService {

    private final PasswordHistoryRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final SettingManager settingManager;

    /**
     * Rejects a new password that matches any of the last {@code n} stored hashes.
     *
     * @param userId      the user whose history is inspected
     * @param rawPassword the candidate raw password
     * @param n           number of most-recent hashes to compare against (0 disables the check)
     */
    @Transactional(readOnly = true)
    public void checkNotRecentlyUsed(Long userId, String rawPassword, int n) {
        if (n <= 0 || rawPassword == null || userId == null) {
            return;
        }
        List<PasswordHistory> recent = repository.findByUserIdOrderByCreatedAtDesc(userId);
        int limit = Math.min(n, recent.size());
        for (int i = 0; i < limit; i++) {
            if (passwordEncoder.matches(rawPassword, recent.get(i).getPasswordHash())) {
                throw new DomainException(ErrorCode.VALIDATION,
                        "Password was used recently; choose a different one");
            }
        }
    }

    /**
     * Records an (already encoded) password hash and prunes entries beyond the most recent N (with N
     * resolved from {@code App.Password.HistoryCount} for the current tenant/user context).
     */
    public void record(Long userId, String passwordHash) {
        if (userId == null || passwordHash == null) {
            return;
        }
        PasswordHistory entry = new PasswordHistory();
        entry.setUserId(userId);
        entry.setPasswordHash(passwordHash);
        repository.save(entry);
        prune(userId, resolveHistoryCount());
    }

    /** Resolves {@code App.Password.HistoryCount} for the current authenticated context. */
    public int resolveHistoryCount() {
        String value = settingManager.getOrDefault(
                PasswordPolicyValidator.SETTING_HISTORY_COUNT, CurrentUser.tenantId(), CurrentUser.userId());
        if (value == null || value.isBlank()) {
            return PasswordPolicy.DEFAULT_HISTORY_COUNT;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return PasswordPolicy.DEFAULT_HISTORY_COUNT;
        }
    }

    private void prune(Long userId, int historyCount) {
        if (historyCount < 0) {
            return;
        }
        List<PasswordHistory> all = repository.findByUserIdOrderByCreatedAtDesc(userId);
        if (all.size() > historyCount) {
            repository.deleteAll(all.subList(historyCount, all.size()));
        }
    }
}
