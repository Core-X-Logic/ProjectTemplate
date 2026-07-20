package com.mycompanyname.zero.saas.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two halves of the iyzico on/off switch, unit-level — the {@code PayTRPropertiesValidationTest}
 * pattern applied to the third provider:
 *
 * <ul>
 *   <li>{@code enabled=true} with unusable credentials REFUSES BOOT naming the property and the
 *       environment variable. The unresolved-placeholder case is the load-bearing one:
 *       {@code ${IYZICO_SECRET_KEY}} binds as a literal string with no error (measured project-wide
 *       trap), and a boot that survived it would verify every {@code X-IYZ-SIGNATURE-V3} against
 *       the placeholder text — 400 to every delivery, and iyzico's retry budget is only three
 *       redeliveries before the webhook is gone for good.</li>
 *   <li>{@code enabled=false} registers NO provider bean at all — both iyzico surfaces then answer
 *       404 ({@code IyzicoDisabledSurfaceIT} proves that over the wire on the default context),
 *       and a fresh clone boots with no iyzico account.</li>
 * </ul>
 */
class IyzicoPropertiesValidationTest {

    // --- boot validation ---

    @Test
    @DisplayName("enabled + blank api-key refuses boot, naming the property and the env var")
    void enabledWithBlankApiKeyRefusesBoot() {
        assertThatThrownBy(() -> BillingIyzicoSecretValidator.validate(
                properties(true, " ", "secret_x", "https://sandbox-api.iyzipay.com")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zero.billing.iyzico.api-key")
                .hasMessageContaining("IYZICO_API_KEY");
    }

    @Test
    @DisplayName("enabled + blank secret-key refuses boot")
    void enabledWithBlankSecretKeyRefusesBoot() {
        assertThatThrownBy(() -> BillingIyzicoSecretValidator.validate(
                properties(true, "api_x", "", "https://sandbox-api.iyzipay.com")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zero.billing.iyzico.secret-key")
                .hasMessageContaining("IYZICO_SECRET_KEY");
    }

    @Test
    @DisplayName("enabled + blank base-url refuses boot")
    void enabledWithBlankBaseUrlRefusesBoot() {
        assertThatThrownBy(() -> BillingIyzicoSecretValidator.validate(
                properties(true, "api_x", "secret_x", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zero.billing.iyzico.base-url")
                .hasMessageContaining("IYZICO_BASE_URL");
    }

    @Test
    @DisplayName("enabled + unresolved ${...} placeholder refuses boot — it binds as a literal, silently")
    void enabledWithUnresolvedPlaceholderRefusesBoot() {
        assertThatThrownBy(() -> BillingIyzicoSecretValidator.validate(
                properties(true, "api_x", "${IYZICO_SECRET_KEY}", "https://sandbox-api.iyzipay.com")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zero.billing.iyzico.secret-key");

        assertThatThrownBy(() -> BillingIyzicoSecretValidator.validate(
                properties(true, "${IYZICO_API_KEY}", "secret_x", "https://sandbox-api.iyzipay.com")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zero.billing.iyzico.api-key");
    }

    @Test
    @DisplayName("enabled with usable credentials boots")
    void enabledWithUsableCredentialsBoots() {
        assertThatCode(() -> BillingIyzicoSecretValidator.validate(
                properties(true, "api_x", "secret_x", "https://sandbox-api.iyzipay.com")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("disabled validates nothing — a fresh clone must boot with no iyzico account")
    void disabledValidatesNothing() {
        assertThatCode(() -> BillingIyzicoSecretValidator.validate(
                properties(false, null, null, null)))
                .doesNotThrowAnyException();
    }

    // --- conditional bean registration ---

    @Test
    @DisplayName("enabled=false registers no iyzico provider bean")
    void disabledRegistersNoProviderBean() {
        contextRunner(false).run(context ->
                assertThat(context).doesNotHaveBean(BillingProvider.class));
    }

    @Test
    @DisplayName("enabled=true registers the iyzico provider under id 'iyzico', query-capable")
    void enabledRegistersTheIyzicoProvider() {
        contextRunner(true).run(context -> {
            assertThat(context).hasSingleBean(IyzicoBillingProvider.class);
            assertThat(context.getBean(BillingProvider.class).id()).isEqualTo("iyzico");
            assertThat(context.getBean(BillingProvider.class).successAckBody())
                    .as("iyzico settles delivery on HTTP 200 alone — a bodyless ack")
                    .isNull();
            assertThat(context.getBean(BillingProvider.class).supportsQueryConfirmation())
                    .as("query support is what routes this provider through the "
                            + "retrieve-authoritative funnel and into the reconciliation job")
                    .isTrue();
        });
    }

    private static ApplicationContextRunner contextRunner(boolean enabled) {
        return new ApplicationContextRunner()
                .withPropertyValues("zero.billing.iyzico.enabled=" + enabled)
                .withBean(BillingIyzicoProperties.class,
                        () -> properties(enabled, "api_x", "secret_x", "https://sandbox-api.iyzipay.com"))
                .withBean(ObjectMapper.class)
                .withUserConfiguration(BillingIyzicoConfig.class);
    }

    private static BillingIyzicoProperties properties(boolean enabled, String apiKey,
                                                      String secretKey, String baseUrl) {
        BillingIyzicoProperties properties = new BillingIyzicoProperties();
        properties.setEnabled(enabled);
        properties.setApiKey(apiKey);
        properties.setSecretKey(secretKey);
        properties.setBaseUrl(baseUrl);
        return properties;
    }
}
