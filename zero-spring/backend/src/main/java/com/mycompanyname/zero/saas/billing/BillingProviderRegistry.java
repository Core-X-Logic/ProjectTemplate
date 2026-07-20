package com.mycompanyname.zero.saas.billing;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The enabled {@link BillingProvider}s, keyed by {@link BillingProvider#id()} (P2'-A).
 *
 * <p>Replaces the single-provider {@code ObjectProvider<BillingProvider>} wiring: with PayTR next
 * to Stripe there can be several provider beans at once, and "the provider" becomes a lookup by the
 * id each webhook route names. An EMPTY registry is the normal state of a fresh clone — every
 * lookup then misses and the web layer answers 404, exactly as the single-provider wiring did.
 *
 * <p><b>Duplicate ids refuse boot.</b> Two beans answering to the same id would make the webhook
 * route's dispatch ambiguous — which verification code runs would depend on iteration order, i.e.
 * on nothing anybody chose. That must be a loud startup failure, never a coin toss on the first
 * webhook. (The {@code @Primary} test-double case — a recording provider deliberately REPLACING the
 * real bean under the same id — is resolved before this constructor runs, by
 * {@link BillingProviderRegistryConfig}; what reaches here as a duplicate is a genuine conflict.)
 */
public final class BillingProviderRegistry {

    /** Insertion-ordered so error messages and {@link #ids()} are deterministic. */
    private final Map<String, BillingProvider> providersById;

    public BillingProviderRegistry(Collection<? extends BillingProvider> providers) {
        Map<String, BillingProvider> byId = new LinkedHashMap<>();
        for (BillingProvider provider : providers) {
            BillingProvider previous = byId.putIfAbsent(provider.id(), provider);
            if (previous != null) {
                throw new IllegalStateException("Two billing providers claim the id '"
                        + provider.id() + "': " + previous.getClass().getName() + " and "
                        + provider.getClass().getName() + ". Webhook dispatch for that id would be "
                        + "ambiguous, so this is a refused boot, not a warning. Give one of them a "
                        + "distinct BillingProvider.id(), or mark the intended replacement @Primary.");
            }
        }
        this.providersById = Collections.unmodifiableMap(byId);
    }

    /** The provider registered under {@code id}, or empty — the caller decides what a miss means. */
    public Optional<BillingProvider> find(String id) {
        return Optional.ofNullable(providersById.get(id));
    }

    /**
     * The one enabled provider when exactly one exists, otherwise empty. Lets a checkout request
     * omit the provider id on the common single-provider installation without guessing among many.
     */
    public Optional<BillingProvider> single() {
        return providersById.size() == 1
                ? Optional.of(providersById.values().iterator().next())
                : Optional.empty();
    }

    /** Registered ids, insertion-ordered. Configuration facts, safe to name in an error detail. */
    public Set<String> ids() {
        return providersById.keySet();
    }

    /** {@code true} on an installation with billing off entirely — the fresh-clone state. */
    public boolean isEmpty() {
        return providersById.isEmpty();
    }
}
