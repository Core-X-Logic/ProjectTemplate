package com.mycompanyname.zero.saas.billing.credentials;

import com.mycompanyname.zero.shared.domain.AbstractAuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One provider's operator-managed billing credentials (V16, ADR-0020). Host-global on purpose: a
 * provider account belongs to the INSTALLATION, never to a tenant, so there is no {@code tenant_id}
 * and no RLS policy — the compensating control is that the only surface touching this table sits
 * behind the {@code Side.HOST} permission {@code billing.credentials.manage} (the ADR-0015 shape).
 *
 * <p><b>{@code credentialsSecret} is a single ciphertext, never a column per field.</b> The value is
 * {@code FieldEncryptionService.encrypt(JSON{field -> value})}: AES-256-GCM under
 * {@code zero.crypto.field-key}, the same key and service the TOTP secret uses. Encryption rather
 * than hashing because these values must be SENT BACK to the provider's API — recoverability is the
 * requirement, which a hash by definition refuses (ADR-0020). The field name deliberately contains
 * "secret" AND the class name contains "credentials", so {@code AuditSupport#isSensitive} masks it
 * as {@code ***} in every entity-history row without any extra wiring (the
 * {@code User#twoFactorSecret} naming rule).
 *
 * <p><b>{@code enabled} gates NEW checkouts only.</b> The webhook and reconciliation surfaces stay
 * open for any provider whose credentials exist, enabled or not: a payment that STARTED on a
 * provider must be allowed to FINISH on it ({@code BillingProviderAvailability}).
 */
@Entity
@Table(name = "billing_provider_credentials")
@Getter
@Setter
public class BillingProviderCredentials extends AbstractAuditedEntity {

    /** {@code BillingProvider#id()} this row configures ("paytr", "iyzico", "stripe"). Unique. */
    @Column(name = "provider", nullable = false, length = 32)
    private String provider;

    /** Whether NEW checkouts may run through this provider off these credentials. */
    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    /** Failover position at checkout initiation — lower tries first (ADR-0020). */
    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    /**
     * {@code base64([IV][ciphertext][tag])} of the credential JSON, or {@code null} for a row that
     * only carries an order preference so far. Never exposed by any endpoint — reads answer masked
     * hints only ({@code BillingProviderAdminService}).
     */
    @Column(name = "credentials_secret")
    private String credentialsSecret;
}
