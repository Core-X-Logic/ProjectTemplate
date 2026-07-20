package com.mycompanyname.zero.saas.billing;

/**
 * Test-source bridge to {@link PayTRBillingProvider}'s package-private pure helpers, for test
 * fixtures living OUTSIDE the {@code saas.billing} package ({@code PayTRTestProviderConfig}). The
 * helpers stay package-private in production on purpose — they are formula internals, not SPI — and
 * this hook is the one sanctioned way a fixture reaches them.
 */
public final class PayTRBillingProviderTestHook {

    private PayTRBillingProviderTestHook() {
    }

    /** See {@code PayTRBillingProvider#newMerchantOid}: alphanumeric, ≤64, unique per call. */
    public static String newMerchantOid(long paymentId) {
        return PayTRBillingProvider.newMerchantOid(paymentId);
    }
}
