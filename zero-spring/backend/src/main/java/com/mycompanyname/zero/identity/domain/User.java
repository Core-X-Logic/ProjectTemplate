package com.mycompanyname.zero.identity.domain;

import com.mycompanyname.zero.shared.domain.AbstractAuditedEntity;
import com.mycompanyname.zero.shared.domain.TrackChanges;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "users")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Filter(name = "hostFilter", condition = "tenant_id is null")
@SQLRestriction("deleted = false")
@TrackChanges
@Getter
@Setter
public class User extends AbstractAuditedEntity {

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "username", nullable = false, length = 64)
    private String username;

    @Column(name = "email", nullable = false, length = 256)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 256)
    private String passwordHash;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts;

    @Column(name = "lockout_end_at")
    private Instant lockoutEndAt;

    // --- profile / account fields ---

    @Column(name = "name", length = 64)
    private String name;

    @Column(name = "surname", length = 64)
    private String surname;

    @Column(name = "phone_number", length = 32)
    private String phoneNumber;

    @Column(name = "deleted", nullable = false)
    private boolean deleted = false;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "email_confirmed", nullable = false)
    private boolean emailConfirmed = false;

    @Column(name = "email_confirmation_code", length = 128)
    private String emailConfirmationCode;

    @Column(name = "password_reset_code", length = 128)
    private String passwordResetCode;

    @Column(name = "should_change_password", nullable = false)
    private boolean shouldChangePassword = false;

    @Column(name = "last_password_change_at")
    private Instant lastPasswordChangeAt;

    @Column(name = "profile_picture_id")
    private Long profilePictureId;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "user_organization_units",
            joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "organization_unit_id", nullable = false)
    private Set<Long> organizationUnitIds = new HashSet<>();
}
