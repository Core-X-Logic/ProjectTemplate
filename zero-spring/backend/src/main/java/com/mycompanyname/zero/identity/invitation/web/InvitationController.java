package com.mycompanyname.zero.identity.invitation.web;

import com.mycompanyname.zero.identity.domain.AppPermissions;
import com.mycompanyname.zero.identity.invitation.InvitationService;
import com.mycompanyname.zero.identity.invitation.web.dto.InvitationDto;
import com.mycompanyname.zero.identity.invitation.web.dto.InviteUserRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin side of the invitation flow. Every verb is {@code users.create} on purpose — an
 * invitation is nothing but a deferred user creation, so no new permission is minted (which would
 * cost the 5-file registration and a role-template change for a boundary nobody asked for).
 */
@RestController
@RequestMapping("/api/invitations")
@RequiredArgsConstructor
public class InvitationController {

    private final InvitationService invitationService;

    @GetMapping
    @PreAuthorize("hasAuthority('" + AppPermissions.USERS_CREATE + "')")
    public Page<InvitationDto> list(Pageable pageable) {
        return invitationService.list(pageable);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + AppPermissions.USERS_CREATE + "')")
    @ResponseStatus(HttpStatus.CREATED)
    public InvitationDto invite(@Valid @RequestBody InviteUserRequest request) {
        return invitationService.invite(request);
    }

    @PostMapping("/{id}/resend")
    @PreAuthorize("hasAuthority('" + AppPermissions.USERS_CREATE + "')")
    public InvitationDto resend(@PathVariable Long id) {
        return invitationService.resend(id);
    }

    @PostMapping("/{id}/revoke")
    @PreAuthorize("hasAuthority('" + AppPermissions.USERS_CREATE + "')")
    public InvitationDto revoke(@PathVariable Long id) {
        return invitationService.revoke(id);
    }
}
