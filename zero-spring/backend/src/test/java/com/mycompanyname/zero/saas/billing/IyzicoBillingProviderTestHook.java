package com.mycompanyname.zero.saas.billing;

/**
 * Test-source bridge to {@link IyzicoBillingProvider}'s package-private pure helpers, for test
 * fixtures living OUTSIDE the {@code saas.billing} package ({@code IyzicoTestProviderConfig},
 * {@code IyzicoWebhookIT}, {@code BillingReconciliationJobIT}) — the
 * {@link PayTRBillingProviderTestHook} pattern for the third provider. The helpers stay
 * package-private in production on purpose; this hook is the one sanctioned way a fixture reaches
 * them.
 */
public final class IyzicoBillingProviderTestHook {

    private IyzicoBillingProviderTestHook() {
    }

    /**
     * See {@code IyzicoBillingProvider#mapRetrieve}: the activation predicate. Exposed so the
     * recording test provider cans retrieve answers through the REAL decision logic (a fraud-review
     * fixture is then {@code fraudStatus=0} run through production code, not a hand-built record).
     */
    public static ProviderPaymentConfirmation mapRetrieve(String status, String paymentStatus,
                                                          Integer fraudStatus, String paymentId,
                                                          String errorMessage) {
        return IyzicoBillingProvider.mapRetrieve(status, paymentStatus, fraudStatus, paymentId,
                errorMessage);
    }
}
