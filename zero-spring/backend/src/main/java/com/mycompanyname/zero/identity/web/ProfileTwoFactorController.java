package com.mycompanyname.zero.identity.web;

import com.mycompanyname.zero.identity.user.TwoFactorService;
import com.mycompanyname.zero.identity.web.dto.RecoveryCodesDto;
import com.mycompanyname.zero.identity.web.dto.TwoFactorEnableRequest;
import com.mycompanyname.zero.identity.web.dto.TwoFactorPasswordRequest;
import com.mycompanyname.zero.identity.web.dto.TwoFactorSetupDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service 2FA management for the authenticated caller, sibling of {@link ProfileController} under
 * {@code /api/profile/two-factor}. Every endpoint is {@code isAuthenticated()} and self-only — the
 * target user is always the JWT subject, never a request parameter — so no permission is needed and
 * one user can never manage another's 2FA. NOT subscription-exempt: like {@code /api/profile/*} these
 * are ordinary authenticated operations, so an expired tenant is gated the same way it is elsewhere.
 */
@RestController
@RequestMapping("/api/profile/two-factor")
@RequiredArgsConstructor
public class ProfileTwoFactorController {

    private final TwoFactorService twoFactorService;

    /** Provisions a pending TOTP secret and returns it + the otpauth URI once. Does not enable 2FA. */
    @PostMapping("/setup")
    @PreAuthorize("isAuthenticated()")
    public TwoFactorSetupDto setup() {
        return twoFactorService.setup();
    }

    /** Confirms the pending secret with a live code, switches 2FA on, and returns recovery codes once. */
    @PostMapping("/enable")
    @PreAuthorize("isAuthenticated()")
    public RecoveryCodesDto enable(@Valid @RequestBody TwoFactorEnableRequest request) {
        return twoFactorService.enable(request.code());
    }

    /** Turns 2FA off after re-verifying the current password; clears the secret and recovery codes. */
    @PostMapping("/disable")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(@Valid @RequestBody TwoFactorPasswordRequest request) {
        twoFactorService.disable(request.password());
    }

    /** Replaces the recovery-code set after re-verifying the current password; returns them once. */
    @PostMapping("/recovery-codes/regenerate")
    @PreAuthorize("isAuthenticated()")
    public RecoveryCodesDto regenerateRecoveryCodes(@Valid @RequestBody TwoFactorPasswordRequest request) {
        return twoFactorService.regenerateRecoveryCodes(request.password());
    }
}
