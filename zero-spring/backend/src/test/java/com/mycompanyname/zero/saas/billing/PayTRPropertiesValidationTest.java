package com.mycompanyname.zero.saas.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompanyname.zero.saas.billing.credentials.ManagedBillingProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two halves of the PayTR on/off switch, unit-level — the {@code BillingPropertiesValidationTest}
 * pattern applied to the second provider:
 *
 * <ul>
 *   <li>{@code enabled=true} with unusable credentials REFUSES BOOT naming the property and the
 *       environment variable. The unresolved-placeholder case is the load-bearing one:
 *       {@code ${PAYTR_MERCHANT_KEY}} binds as a literal string with no error (measured
 *       project-wide trap), and a boot that survived it would verify every notification hash
 *       against the placeholder text — 400 to PayTR, read there as failed deliveries, while buyers
 *       HAVE been charged.</li>
 *   <li>The provider bean registers UNCONDITIONALLY (ADR-0020) — the surface still answers 404 on
 *       a fresh clone ({@code PayTRDisabledSurfaceIT} proves that over the wire on the default
 *       context), but the answer comes from {@code BillingProviderAvailability}, not from a
 *       missing bean.</li>
 * </ul>
 */
class PayTRPropertiesValidationTest {

    // --- boot validation ---

    @Test
    @DisplayName("enabled + blank merchant-key refuses boot, naming the property and the env var")
    void enabledWithBlankMerchantKeyRefusesBoot() {
        assertThatThrownBy(() -> BillingPayTRSecretValidator.validate(
                properties(true, "123456", " ", "salt_x")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zero.billing.paytr.merchant-key")
                .hasMessageContaining("PAYTR_MERCHANT_KEY");
    }

    @Test
    @DisplayName("enabled + blank merchant-salt refuses boot")
    void enabledWithBlankMerchantSaltRefusesBoot() {
        assertThatThrownBy(() -> BillingPayTRSecretValidator.validate(
                properties(true, "123456", "key_x", "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zero.billing.paytr.merchant-salt")
                .hasMessageContaining("PAYTR_MERCHANT_SALT");
    }

    @Test
    @DisplayName("enabled + blank merchant-id refuses boot")
    void enabledWithBlankMerchantIdRefusesBoot() {
        assertThatThrownBy(() -> BillingPayTRSecretValidator.validate(
                properties(true, null, "key_x", "salt_x")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zero.billing.paytr.merchant-id")
                .hasMessageContaining("PAYTR_MERCHANT_ID");
    }

    @Test
    @DisplayName("enabled + unresolved ${...} placeholder refuses boot — it binds as a literal, silently")
    void enabledWithUnresolvedPlaceholderRefusesBoot() {
        assertThatThrownBy(() -> BillingPayTRSecretValidator.validate(
                properties(true, "123456", "${PAYTR_MERCHANT_KEY}", "salt_x")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zero.billing.paytr.merchant-key");

        assertThatThrownBy(() -> BillingPayTRSecretValidator.validate(
                properties(true, "123456", "key_x", "${PAYTR_MERCHANT_SALT}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zero.billing.paytr.merchant-salt");
    }

    @Test
    @DisplayName("enabled with usable credentials boots")
    void enabledWithUsableCredentialsBoots() {
        assertThatCode(() -> BillingPayTRSecretValidator.validate(
                properties(true, "123456", "key_x", "salt_x")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("disabled validates nothing — a fresh clone must boot with no PayTR account")
    void disabledValidatesNothing() {
        assertThatCode(() -> BillingPayTRSecretValidator.validate(
                properties(false, null, null, null)))
                .doesNotThrowAnyException();
    }

    // --- bean registration (unconditional since ADR-0020) ---

    @Test
    @DisplayName("enabled=false STILL registers the provider bean — availability decides, not registration (ADR-0020)")
    void disabledStillRegistersTheProviderBean() {
        contextRunner(false).run(context ->
                assertThat(context).hasSingleBean(PayTRBillingProvider.class));
    }

    @Test
    @DisplayName("enabled=true registers the PayTR provider under id 'paytr'")
    void enabledRegistersThePayTRProvider() {
        contextRunner(true).run(context -> {
            assertThat(context).hasSingleBean(PayTRBillingProvider.class);
            assertThat(context.getBean(BillingProvider.class).id()).isEqualTo("paytr");
            assertThat(context.getBean(BillingProvider.class).successAckBody())
                    .as("the PayTR settlement contract is the literal body OK")
                    .isEqualTo("OK");
        });
    }

    private static ApplicationContextRunner contextRunner(boolean enabled) {
        return new ApplicationContextRunner()
                .withPropertyValues("zero.billing.paytr.enabled=" + enabled)
                .withBean(BillingPayTRProperties.class,
                        () -> properties(enabled, "123456", "key_x", "salt_x"))
                .withBean(ObjectMapper.class)
                .withBean(ManagedBillingProperties.class,
                        PayTRPropertiesValidationTest::passthroughManagedProperties)
                .withUserConfiguration(BillingPayTRConfig.class);
    }

    /** Passthrough view — registration semantics only; resolution is the admin IT's subject. */
    private static ManagedBillingProperties passthroughManagedProperties() {
        ManagedBillingProperties managed = Mockito.mock(ManagedBillingProperties.class);
        Mockito.when(managed.paytr(Mockito.any())).thenAnswer(call -> call.getArgument(0));
        return managed;
    }

    private static BillingPayTRProperties properties(boolean enabled, String merchantId,
                                                     String merchantKey, String merchantSalt) {
        BillingPayTRProperties properties = new BillingPayTRProperties();
        properties.setEnabled(enabled);
        properties.setMerchantId(merchantId);
        properties.setMerchantKey(merchantKey);
        properties.setMerchantSalt(merchantSalt);
        properties.setTestMode(true);
        return properties;
    }
}
