package com.mycompanyname.zero.saas.billing;

import com.mycompanyname.zero.shared.domain.AbstractAuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One attempt to collect money for one edition period. Created {@code NOT_PAID} when checkout
 * starts; moved to {@code PAID} ONLY by the provider webhook — never by a browser redirect
 * (ADR-0014: server-authoritative activation, the first of the two measured source-system bugs
 * this slice closes).
 *
 * <p>{@code amount}/{@code currency}/{@code period} are a snapshot of the edition price at checkout
 * time (ADR-0012): a later catalogue edit changes nothing about what this buyer pays.
 *
 * <p>Host-scoped per ADR-0015 — no tenant {@code @Filter}, explicit {@code tenant_id} column; the
 * compensating control is the {@code Side.HOST} permission on every billing endpoint plus the
 * negative authorization test ({@code CheckoutEndpointIT}).
 */
@Entity
@Table(name = "payments")
@Getter
@Setter
public class Payment extends AbstractAuditedEntity {

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "subscription_id", nullable = false)
    private Long subscriptionId;

    /** The edition the tenant is buying; {@code null} would mean "renew the current package". */
    @Column(name = "target_edition_id")
    private Long targetEditionId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "period", length = 16)
    private String period;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PaymentStatus status;

    /** Provider checkout session id; unique, and the key the completion webhook looks up. */
    @Column(name = "external_session_id", length = 255)
    private String externalSessionId;

    @Column(name = "external_payment_id", length = 255)
    private String externalPaymentId;

    @Column(name = "paid_at")
    private Instant paidAt;
}
