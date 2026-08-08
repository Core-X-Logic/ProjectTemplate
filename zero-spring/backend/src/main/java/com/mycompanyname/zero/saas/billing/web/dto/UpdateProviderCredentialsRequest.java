package com.mycompanyname.zero.saas.billing.web.dto;

import java.util.Map;

/**
 * Write-only credential update for one provider (ADR-0020). MERGE semantics, so the portal's
 * masked form round-trips nothing: a field that is absent or blank means "keep the stored value" —
 * never "clear it" (clearing everything is {@code DELETE .../credentials}). Values are encrypted
 * at rest as one ciphertext and never appear in any response.
 *
 * @param enabled     whether NEW checkouts may use this provider; {@code null} keeps the stored
 *                    flag. Enabling validates completeness: the provider's required fields must be
 *                    present after the merge, the write-time restatement of the
 *                    {@code Billing*SecretValidator} boot rule.
 * @param credentials field-name → value; allowed names are the provider's vocabulary
 *                    ({@code BillingCredentialFields}), an unknown name is a 400
 */
public record UpdateProviderCredentialsRequest(
        Boolean enabled,
        Map<String, String> credentials) {
}
