package com.mycompanyname.zero.identity.invitation.web;

import com.mycompanyname.zero.identity.invitation.InvitationService;
import com.mycompanyname.zero.identity.invitation.web.dto.AcceptInvitationRequest;
import com.mycompanyname.zero.identity.invitation.web.dto.InvitationInfoDto;
import com.mycompanyname.zero.shared.web.EndpointPolicy;
import com.mycompanyname.zero.shared.web.EndpointPolicy.Exposure;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Anonymous side of the invitation flow, sitting under {@code /api/account} with the other
 * credential-free self-service endpoints: the invitee has no session — the mailed token IS the
 * credential. Both routes are permitAll in {@code SecurityConfig} (exact paths), throttled via
 * {@code zero.ratelimit.paths}, and subscription-exempt through the existing
 * {@code /api/account/**} entry (an expired subscription must not strand a half-created account).
 */
@RestController
@RequestMapping("/api/account")
@RequiredArgsConstructor
public class InvitationAcceptController {

    private final InvitationService invitationService;

    /**
     * What the accept screen shows before asking for a password — most importantly the username the
     * admin fixed. Unlocked by the token alone; an unusable token answers the single non-oracle 400.
     */
    @GetMapping("/invitation")
    @EndpointPolicy({Exposure.ANONYMOUS, Exposure.SUBSCRIPTION_EXEMPT})
    public InvitationInfoDto invitationInfo(@RequestParam("token") String token) {
        return invitationService.invitationInfo(token);
    }

    /**
     * 204 both on creation and on the deliberate no-op (already accepted / user already exists):
     * the screen's next step — go sign in — is identical, and distinguishing them would hand a
     * token-replaying caller an oracle.
     */
    @PostMapping("/accept-invitation")
    @EndpointPolicy({Exposure.ANONYMOUS, Exposure.SUBSCRIPTION_EXEMPT})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void acceptInvitation(@Valid @RequestBody AcceptInvitationRequest request) {
        invitationService.accept(request);
    }
}
