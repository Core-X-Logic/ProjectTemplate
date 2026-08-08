package com.mycompanyname.zero.saas.billing.credentials;

import com.mycompanyname.zero.saas.billing.BillingIyzicoProperties;
import com.mycompanyname.zero.saas.billing.BillingPayTRProperties;
import com.mycompanyname.zero.saas.billing.BillingProvider;
import com.mycompanyname.zero.saas.billing.BillingProviderRegistry;
import com.mycompanyname.zero.saas.billing.BillingStripeProperties;
import com.mycompanyname.zero.saas.billing.IyzicoBillingProvider;
import com.mycompanyname.zero.saas.billing.PayTRBillingProvider;
import com.mycompanyname.zero.saas.billing.StripeBillingProvider;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Decides which registered {@link BillingProvider}s are actually AVAILABLE, now that provider beans
 * are registered unconditionally (ADR-0020). Before managed credentials, "registered" and "enabled"
 * were the same fact ({@code @ConditionalOnProperty}); with the portal able to enable a provider at
 * runtime the two separate, and this class is the single place holding the new rules:
 *
 * <ul>
 *   <li><b>{@link #surfaceExists}</b> — the webhook/callback/reconciliation question. True when the
 *       environment enables the provider OR stored credentials exist, {@code enabled} flag
 *       IRRESPECTIVE: a payment that started on a provider must be allowed to finish on it, so
 *       disabling a provider closes NEW checkouts only, never the webhook surface. False for both
 *       → the surface answers 404, exactly the fresh-clone behaviour the disabled-surface ITs
 *       pin.</li>
 *   <li><b>{@link #checkoutEnabled}</b> — the new-checkout question. True when the environment
 *       enables the provider (boot-validated credentials) OR the stored row is enabled AND carries
 *       credentials (write-time-validated).</li>
 *   <li><b>{@link #checkoutCandidates}</b> — the failover order: checkout-enabled providers sorted
 *       by stored {@code display_order} (environment-only providers sort LAST, ties broken by
 *       registry insertion order so the result is deterministic).</li>
 * </ul>
 *
 * <p>Environment enablement is answered from the three known properties beans by provider id; a
 * provider id outside the shipped three is environment-DISABLED by definition and participates
 * through stored credentials only.
 */
@Component
@RequiredArgsConstructor
public class BillingProviderAvailability {

    private final BillingCredentialsResolver resolver;
    private final BillingStripeProperties stripeProperties;
    private final BillingPayTRProperties paytrProperties;
    private final BillingIyzicoProperties iyzicoProperties;

    /** Webhook/callback/reconciliation availability — see the class contract. */
    public boolean surfaceExists(String providerId) {
        return environmentEnabled(providerId) || resolver.hasStoredCredentials(providerId);
    }

    /** New-checkout availability — see the class contract. */
    public boolean checkoutEnabled(String providerId) {
        return environmentEnabled(providerId) || resolver.isEnabledByStore(providerId);
    }

    /** Checkout-enabled providers in failover order (stored order first, environment-only last). */
    public List<BillingProvider> checkoutCandidates(BillingProviderRegistry registry) {
        record Candidate(BillingProvider provider, int order, int registryPosition) {
        }
        List<Candidate> candidates = new ArrayList<>();
        int position = 0;
        for (String id : registry.ids()) {
            position++;
            if (!checkoutEnabled(id)) {
                continue;
            }
            int order = resolver.displayOrder(id).orElse(Integer.MAX_VALUE);
            candidates.add(new Candidate(registry.find(id).orElseThrow(), order, position));
        }
        candidates.sort(Comparator.comparingInt(Candidate::order)
                .thenComparingInt(Candidate::registryPosition));
        return candidates.stream().map(Candidate::provider).toList();
    }

    public boolean environmentEnabled(String providerId) {
        return switch (providerId) {
            case StripeBillingProvider.PROVIDER_ID -> stripeProperties.isEnabled();
            case PayTRBillingProvider.PROVIDER_ID -> paytrProperties.isEnabled();
            case IyzicoBillingProvider.PROVIDER_ID -> iyzicoProperties.isEnabled();
            default -> false;
        };
    }
}
