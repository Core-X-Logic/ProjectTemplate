package com.mycompanyname.zero.identity.auth.web;

import com.mycompanyname.zero.identity.auth.ImpersonationService;
import com.mycompanyname.zero.identity.auth.web.dto.ImpersonateAuthRequest;
import com.mycompanyname.zero.identity.auth.web.dto.ImpersonateRequest;
import com.mycompanyname.zero.identity.auth.web.dto.ImpersonationTokenDto;
import com.mycompanyname.zero.identity.domain.AppPermissions;
import com.mycompanyname.zero.identity.web.dto.TokenPairDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Impersonation endpoints, kept separate from {@code AuthController}.
 * All three require an authenticated caller (enforced by the security filter chain); {@code /impersonate}
 * additionally requires the {@code users.impersonate} permission.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class ImpersonationController {

    private final ImpersonationService impersonationService;

    @PostMapping("/impersonate")
    @PreAuthorize("hasAuthority('" + AppPermissions.USERS_IMPERSONATE + "')")
    public ImpersonationTokenDto impersonate(@Valid @RequestBody ImpersonateRequest request) {
        return impersonationService.start(request);
    }

    /**
     * Redeems an impersonation ticket. No permission beyond authentication, because the protection
     * is the secret itself: a 32-byte {@code SecureRandom} ticket with a 30-second TTL that is
     * single-use, and whose redemption is refused unless the calling principal is the actor the
     * ticket was minted for. A permission here would guard nothing the ticket does not already
     * guard, while wrongly implying the ticket alone is insufficient.
     */
    @PostMapping("/impersonate/authenticate")
    @PreAuthorize("isAuthenticated()")
    public TokenPairDto authenticate(@Valid @RequestBody ImpersonateAuthRequest request) {
        return impersonationService.authenticate(request);
    }

    /**
     * Returns the caller to their original identity. Takes no input at all: the impersonator is read
     * from the {@code act} claim of the caller's own signed JWT, which the caller cannot forge or
     * choose. Only someone already impersonating has that claim, so authentication is the whole
     * check.
     */
    @PostMapping("/back-to-impersonator")
    @PreAuthorize("isAuthenticated()")
    public TokenPairDto backToImpersonator() {
        return impersonationService.backToImpersonator();
    }
}
