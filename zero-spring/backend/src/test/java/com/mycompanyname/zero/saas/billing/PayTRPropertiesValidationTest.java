package com.mycompanyname.zero.saas.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 *   <li>{@code enabled=false} registers NO provider bean at all — the surface then answers 404
 *       ({@code PayTRDisabledSurfaceIT} proves that over the wire on the default context), and a
 *       fresh clone boots with no PayTR account.</li>
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

    // --- conditional bean registration ---

    @Test
    @DisplayName("enabled=false registers no PayTR provider bean")
    void disabledRegistersNoProviderBean() {
        contextRunner(false).run(context ->
                assertThat(context).doesNotHaveBean(BillingProvider.class));
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
                .withUserConfiguration(BillingPayTRConfig.class);
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
