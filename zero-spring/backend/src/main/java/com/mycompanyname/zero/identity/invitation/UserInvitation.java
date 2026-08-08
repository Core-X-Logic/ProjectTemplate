package com.mycompanyname.zero.identity.invitation;

import com.mycompanyname.zero.shared.domain.AbstractAuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Filter;

import java.time.Instant;

/**
 * A pending account, keyed by a single-use invitation token.
 *
 * <p>{@code tokenHash} is the SHA-256 of the raw token; the raw token exists only in the invitation
 * e-mail. Same pattern V14 applies to the reset/confirmation codes (R-44) — an invitation token
 * converts into a NEW account, so a leaked table dump must not be convertible into sessions.
 *
 * <p>Role names are stored as one comma-joined string rather than a join table: a
 * {@code user_invitation_roles} table would carry no {@code tenant_id} and therefore be born
 * policy-less (the R-47 class). Names are resolved against live roles at accept time.
 *
 * <p>No {@code @TrackChanges}: the only mutable secret-adjacent field is {@code tokenHash}, and a
 * property-level change history of it would double the places a hash lives for zero product value.
 */
@Entity
@Table(name = "user_invitations")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Filter(name = "hostFilter", condition = "tenant_id is null")
@Getter
@Setter
public class UserInvitation extends AbstractAuditedEntity {

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "username", nullable = false, length = 64)
    private String username;

    @Column(name = "email", nullable = false, length = 256)
    private String email;

    /** Comma-joined role names; commas are rejected in role names at invite time. */
    @Column(name = "role_names", nullable = false, length = 1024)
    private String roleNames;

    @Column(name = "token_hash", nullable = false, length = 128)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InvitationStatus status = InvitationStatus.PENDING;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "invited_by_user_id", nullable = false)
    private Long invitedByUserId;

    @Column(name = "accepted_user_id")
    private Long acceptedUserId;
}
