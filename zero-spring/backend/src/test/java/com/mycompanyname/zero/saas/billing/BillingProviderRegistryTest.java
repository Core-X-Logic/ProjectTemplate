package com.mycompanyname.zero.saas.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The multi-provider registry contract (P2'-A). The duplicate-id refusal is the load-bearing rule:
 * with two beans answering to one id, which verification code a webhook runs would depend on bean
 * iteration order — i.e. on nothing anybody chose — so it must be a refused boot, never a coin
 * toss. Negative evidence: with the {@code putIfAbsent}-and-throw in the registry constructor
 * replaced by a plain {@code put} (last-wins, the naive spelling), {@link
 * #duplicateProviderIdsRefuseStartup} goes red on "expected IllegalStateException, nothing thrown".
 */
class BillingProviderRegistryTest {

    @Test
    @DisplayName("two providers claiming the same id refuse startup, naming the id and both classes")
    void duplicateProviderIdsRefuseStartup() {
        assertThatThrownBy(() -> new BillingProviderRegistry(
                List.of(new FakeProvider("dup"), new OtherFakeProvider("dup"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'dup'")
                .hasMessageContaining(FakeProvider.class.getName())
                .hasMessageContaining(OtherFakeProvider.class.getName());
    }

    @Test
    @DisplayName("distinct ids register and resolve independently")
    void distinctIdsResolveIndependently() {
        FakeProvider stripe = new FakeProvider("stripe");
        OtherFakeProvider paytr = new OtherFakeProvider("paytr");
        BillingProviderRegistry registry = new BillingProviderRegistry(List.of(stripe, paytr));

        assertThat(registry.find("stripe")).containsSame(stripe);
        assertThat(registry.find("paytr")).containsSame(paytr);
        assertThat(registry.find("iyzico")).isEmpty();
        assertThat(registry.ids()).containsExactly("stripe", "paytr");
        assertThat(registry.isEmpty()).isFalse();
        assertThat(registry.single())
                .as("with two providers there is no 'the' provider — a checkout must choose")
                .isEmpty();
    }

    @Test
    @DisplayName("a single registered provider is the default a checkout may omit")
    void singleProviderIsTheDefault() {
        FakeProvider only = new FakeProvider("paytr");
        BillingProviderRegistry registry = new BillingProviderRegistry(List.of(only));

        assertThat(registry.single()).containsSame(only);
    }

    @Test
    @DisplayName("an empty registry is the fresh-clone state: every lookup misses, nothing throws")
    void emptyRegistryMissesQuietly() {
        BillingProviderRegistry registry = new BillingProviderRegistry(List.of());

        assertThat(registry.isEmpty()).isTrue();
        assertThat(registry.find("stripe")).isEmpty();
        assertThat(registry.single()).isEmpty();
    }

    /** Two distinct classes on purpose: the duplicate-id message must name both sides. */
    private static class FakeProvider implements BillingProvider {
        private final String id;

        FakeProvider(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public BillingEvent verifyAndParse(String payload, String signatureHeader) {
            throw new UnsupportedOperationException("registry test fake");
        }

        @Override
        public CheckoutSession createCheckoutSession(CheckoutRequest request) {
            throw new UnsupportedOperationException("registry test fake");
        }
    }

    private static final class OtherFakeProvider extends FakeProvider {
        OtherFakeProvider(String id) {
            super(id);
        }
    }
}
