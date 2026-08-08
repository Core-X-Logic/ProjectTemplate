package com.mycompanyname.zero.saas.billing.web.dto;

import java.util.List;

/**
 * One billing provider's managed-credential status, as the host portal sees it (ADR-0020). This is
 * a WRITE-ONLY credential surface's read half: no field of this record ever carries a stored or
 * environment credential VALUE — {@code BillingProviderCredentialsAdminIT} pins that the raw
 * values appear in no response body.
 *
 * @param provider         {@code BillingProvider#id()} ("paytr", "iyzico", "stripe")
 * @param enabled          whether NEW checkouts may run through this provider right now
 *                         (environment-enabled or store-enabled — the effective answer)
 * @param configured       whether usable credentials exist at all (environment or stored); a
 *                         configured-but-disabled provider still serves its webhook surface
 * @param source           where the effective credentials come from: {@code db}, {@code env} or
 *                         {@code none}
 * @param maskedHint       last four characters of the provider's least-secret identifier
 *                         (PayTR merchant number, iyzico API key, Stripe publishable key),
 *                         {@code ****}-prefixed; {@code null} when nothing is configured
 * @param displayOrder     stored failover position (lower tries first); {@code null} when the
 *                         provider has no stored row yet
 * @param configuredFields names of the credential fields present in the STORED set (names only,
 *                         never values) — what the portal renders its per-field "set" badges from
 */
public record ProviderStatusDto(
        String provider,
        boolean enabled,
        boolean configured,
        String source,
        String maskedHint,
        Integer displayOrder,
        List<String> configuredFields) {
}
