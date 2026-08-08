package com.mycompanyname.zero.identity.invitation.web.dto;

/**
 * What the anonymous accept screen may know, unlocked by the token itself (the token IS the
 * credential; there is no other caller identity to check). {@code status} is either
 * {@code PENDING} (show the password form) or {@code ACCEPTED} (offer the sign-in link) — every
 * other state answers 400 upstream with the single non-oracle message.
 */
public record InvitationInfoDto(
        String username,
        String email,
        String status) {
}
