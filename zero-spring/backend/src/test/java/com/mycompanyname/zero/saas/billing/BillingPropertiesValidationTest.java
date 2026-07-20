package com.mycompanyname.zero.saas.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two halves of the billing on/off switch, unit-level:
 *
 * <ul>
 *   <li>{@code enabled=true} with unusable secrets REFUSES BOOT with a message naming the property
 *       and the environment variable ({@code JwtSecretValidator} pattern). The unresolved-placeholder
 *       case is the load-bearing one: {@code ${STRIPE_SECRET_KEY}} binds as a literal string with no
 *       error (measured project-wide trap), so without this guard a mis-deployed installation would
 *       come up green and verify webhook signatures against the placeholder text.</li>
 *   <li>{@code enabled=false} registers NO provider bean at all — the billing surface then answers
 *       404, and a fresh clone boots with no Stripe account.</li>
 * </ul>
 */
class BillingPropertiesValidationTest {

    // --- boot validation ---

    @Test
    @DisplayName("enabled + blank secret-key refuses boot, naming the property and the env var")
    void enabledWithBlankSecretKeyRefusesBoot() {
        assertThatThrownBy(() -> BillingStripeSecretValidator.validate(
                properties(true, " ", "whsec_x", "pk_x")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zero.billing.stripe.secret-key")
                .hasMessageContaining("STRIPE_SECRET_KEY");
    }

    @Test
    @DisplayName("enabled + blank webhook-secret refuses boot")
    void enabledWithBlankWebhookSecretRefusesBoot() {
        assertThatThrownBy(() -> BillingStripeSecretValidator.validate(
                properties(true, "sk_test_x", "", "pk_x")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zero.billing.stripe.webhook-secret")
                .hasMessageContaining("STRIPE_WEBHOOK_SECRET");
    }

    @Test
    @DisplayName("enabled + unresolved ${...} placeholder refuses boot — it binds as a literal, silently")
    void enabledWithUnresolvedPlaceholderRefusesBoot() {
        assertThatThrownBy(() -> BillingStripeSecretValidator.validate(
                properties(true, "${STRIPE_SECRET_KEY}", "whsec_x", "pk_x")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zero.billing.stripe.secret-key");

        assertThatThrownBy(() -> BillingStripeSecretValidator.validate(
                properties(true, "sk_test_x", "${STRIPE_WEBHOOK_SECRET}", "pk_x")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zero.billing.stripe.webhook-secret");
    }

    @Test
    @DisplayName("enabled with usable secrets boots; a blank publishable key is a warning, not a failure")
    void enabledWithUsableSecretsBoots() {
        assertThatCode(() -> BillingStripeSecretValidator.validate(
                properties(true, "sk_test_x", "whsec_x", "")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("disabled validates nothing — a fresh clone must boot with no Stripe account")
    void disabledValidatesNothing() {
        assertThatCode(() -> BillingStripeSecretValidator.validate(
                properties(false, null, null, null)))
                .doesNotThrowAnyException();
    }

    // --- conditional bean registration ---

    @Test
    @DisplayName("enabled=false registers no BillingProvider bean")
    void disabledRegistersNoProviderBean() {
        contextRunner(false).run(context ->
                assertThat(context).doesNotHaveBean(BillingProvider.class));
    }

    @Test
    @DisplayName("enabled=true registers the Stripe provider under id 'stripe'")
    void enabledRegistersTheStripeProvider() {
        contextRunner(true).run(context -> {
            assertThat(context).hasSingleBean(StripeBillingProvider.class);
            assertThat(context.getBean(BillingProvider.class).id()).isEqualTo("stripe");
        });
    }

    private static ApplicationContextRunner contextRunner(boolean enabled) {
        return new ApplicationContextRunner()
                .withPropertyValues("zero.billing.stripe.enabled=" + enabled)
                .withBean(BillingStripeProperties.class,
                        () -> properties(enabled, "sk_test_x", "whsec_x", "pk_x"))
                .withBean(ObjectMapper.class)
                .withUserConfiguration(BillingStripeConfig.class);
    }

    private static BillingStripeProperties properties(boolean enabled, String secretKey,
                                                      String webhookSecret, String publishableKey) {
        BillingStripeProperties properties = new BillingStripeProperties();
        properties.setEnabled(enabled);
        properties.setSecretKey(secretKey);
        properties.setWebhookSecret(webhookSecret);
        properties.setPublishableKey(publishableKey);
        return properties;
    }
}
