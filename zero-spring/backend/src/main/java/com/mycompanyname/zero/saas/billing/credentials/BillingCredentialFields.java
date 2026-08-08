package com.mycompanyname.zero.saas.billing.credentials;

import com.mycompanyname.zero.saas.billing.IyzicoBillingProvider;
import com.mycompanyname.zero.saas.billing.PayTRBillingProvider;
import com.mycompanyname.zero.saas.billing.StripeBillingProvider;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The credential vocabulary of each managed provider (ADR-0020): which JSON fields a stored
 * credential set may carry, which of them make the set COMPLETE enough to enable new checkouts,
 * and which field the masked status hint is derived from.
 *
 * <p>The field names deliberately mirror the {@code Billing*Properties} getter names
 * ({@code merchantId}, {@code secretKey}, ...), because the managed properties wrappers resolve
 * BY THESE NAMES at call time ({@code ManagedPayTRProperties} and friends) — renaming one side
 * without the other would silently fall back to the environment value.
 *
 * <p>Required sets restate the {@code Billing*SecretValidator} rules for the DB path: enabling a
 * provider with an incomplete credential set must be refused LOUDLY at write time, exactly as
 * enabling by environment refuses boot — never discovered by the first buyer.
 *
 * <p>The hint field is the least secret identifier the provider has (PayTR merchant number, iyzico
 * API key, Stripe publishable key), so the "configured, ends in 1234" badge never exposes four
 * characters of a signing secret when an identifier exists; the Stripe secret key is the last
 * resort because a Stripe row may carry no publishable key at all.
 */
public final class BillingCredentialFields {

    private static final Map<String, Set<String>> ALLOWED = Map.of(
            PayTRBillingProvider.PROVIDER_ID,
            Set.of("merchantId", "merchantKey", "merchantSalt", "testMode"),
            IyzicoBillingProvider.PROVIDER_ID,
            Set.of("apiKey", "secretKey", "baseUrl", "callbackUrl"),
            StripeBillingProvider.PROVIDER_ID,
            Set.of("secretKey", "webhookSecret", "publishableKey"));

    private static final Map<String, Set<String>> REQUIRED = Map.of(
            PayTRBillingProvider.PROVIDER_ID, Set.of("merchantId", "merchantKey", "merchantSalt"),
            IyzicoBillingProvider.PROVIDER_ID, Set.of("apiKey", "secretKey"),
            StripeBillingProvider.PROVIDER_ID, Set.of("secretKey", "webhookSecret"));

    private static final Map<String, List<String>> HINT_FIELDS = Map.of(
            PayTRBillingProvider.PROVIDER_ID, List.of("merchantId"),
            IyzicoBillingProvider.PROVIDER_ID, List.of("apiKey"),
            StripeBillingProvider.PROVIDER_ID, List.of("publishableKey", "secretKey"));

    private BillingCredentialFields() {
    }

    /** Whether this provider id has a managed-credential vocabulary at all. */
    public static boolean isManaged(String providerId) {
        return ALLOWED.containsKey(providerId);
    }

    public static Set<String> allowed(String providerId) {
        return ALLOWED.getOrDefault(providerId, Set.of());
    }

    public static Set<String> required(String providerId) {
        return REQUIRED.getOrDefault(providerId, Set.of());
    }

    public static List<String> hintFields(String providerId) {
        return HINT_FIELDS.getOrDefault(providerId, List.of());
    }
}
