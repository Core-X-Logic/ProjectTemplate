package com.mycompanyname.zero.saas.billing.credentials;

import com.mycompanyname.zero.saas.billing.BillingIyzicoProperties;
import com.mycompanyname.zero.saas.billing.BillingPayTRProperties;
import com.mycompanyname.zero.saas.billing.BillingProviderRegistry;
import com.mycompanyname.zero.saas.billing.BillingStripeProperties;
import com.mycompanyname.zero.saas.billing.IyzicoBillingProvider;
import com.mycompanyname.zero.saas.billing.PayTRBillingProvider;
import com.mycompanyname.zero.saas.billing.StripeBillingProvider;
import com.mycompanyname.zero.saas.billing.web.dto.ProviderStatusDto;
import com.mycompanyname.zero.saas.billing.web.dto.UpdateProviderCredentialsRequest;
import com.mycompanyname.zero.saas.billing.web.dto.UpdateProviderOrderRequest;
import com.mycompanyname.zero.shared.domain.DomainException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single WRITER of {@code billing_provider_credentials}, and the masked reader the host portal
 * consumes (ADR-0020). Everything here is host-side administration behind
 * {@code billing.credentials.manage}; nothing here ever returns a credential VALUE — status reads
 * answer booleans, field NAMES and a masked hint, which is the whole reason these credentials do
 * not live in the generic settings surface (whose host GET round-trips values to the UI).
 *
 * <p><b>Merge, don't overwrite.</b> An update's absent/blank fields keep their stored values, so
 * the portal can submit its form with untouched password inputs left empty — "blank means keep" is
 * the write-only input pattern's server half. Clearing a provider is {@link #deleteCredentials},
 * which drops the whole row and returns the provider to its environment-configured behaviour.
 *
 * <p><b>Enabling validates completeness.</b> {@code enabled=true} with a merged set missing the
 * provider's required fields is refused with a 400 naming the MISSING FIELD NAMES (ours, safe) —
 * the write-time restatement of the {@code Billing*SecretValidator} boot refusal, so a
 * portal-enabled provider is usable by construction.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class BillingProviderAdminService {

    private static final String SOURCE_DB = "db";
    private static final String SOURCE_ENV = "env";
    private static final String SOURCE_NONE = "none";

    private final BillingProviderRegistry providerRegistry;
    private final BillingProviderCredentialsRepository repository;
    private final BillingCredentialsResolver resolver;
    private final BillingProviderAvailability availability;
    private final BillingStripeProperties stripeProperties;
    private final BillingPayTRProperties paytrProperties;
    private final BillingIyzicoProperties iyzicoProperties;

    /** Every managed provider's status, in effective failover order. */
    @Transactional(readOnly = true)
    public List<ProviderStatusDto> status() {
        record Row(ProviderStatusDto dto, int order, int registryPosition) {
        }
        List<Row> rows = new ArrayList<>();
        int position = 0;
        for (String id : providerRegistry.ids()) {
            position++;
            if (!BillingCredentialFields.isManaged(id)) {
                continue;
            }
            rows.add(new Row(statusOf(id),
                    resolver.displayOrder(id).orElse(Integer.MAX_VALUE), position));
        }
        rows.sort(Comparator.comparingInt(Row::order).thenComparingInt(Row::registryPosition));
        return rows.stream().map(Row::dto).toList();
    }

    public ProviderStatusDto updateCredentials(String providerId,
                                               UpdateProviderCredentialsRequest request) {
        requireManagedProvider(providerId);

        Map<String, String> merged = new LinkedHashMap<>(resolver.storedValues(providerId));
        if (request.credentials() != null) {
            Set<String> allowed = BillingCredentialFields.allowed(providerId);
            for (Map.Entry<String, String> entry : request.credentials().entrySet()) {
                if (!allowed.contains(entry.getKey())) {
                    // The submitted name is deliberately not echoed (house rule); the allowed
                    // vocabulary is ours and safe to state.
                    throw DomainException.validation(
                            "Unsupported credential field; allowed fields for this provider: "
                                    + new TreeSet<>(allowed));
                }
                if (entry.getValue() != null && !entry.getValue().isBlank()) {
                    merged.put(entry.getKey(), entry.getValue());
                }
            }
        }

        BillingProviderCredentials row = repository.findByProvider(providerId)
                .orElseGet(() -> {
                    BillingProviderCredentials created = new BillingProviderCredentials();
                    created.setProvider(providerId);
                    created.setDisplayOrder(nextDisplayOrder());
                    return created;
                });
        boolean enabled = request.enabled() != null ? request.enabled() : row.isEnabled();
        if (enabled) {
            Set<String> missing = new LinkedHashSet<>();
            for (String required : BillingCredentialFields.required(providerId)) {
                String value = merged.get(required);
                if (value == null || value.isBlank()) {
                    missing.add(required);
                }
            }
            if (!missing.isEmpty()) {
                throw DomainException.validation("Cannot enable this provider: required credential "
                        + "field(s) missing: " + missing);
            }
        }
        row.setEnabled(enabled);
        row.setCredentialsSecret(merged.isEmpty() ? null : resolver.encryptFromMap(merged));
        repository.save(row);
        // Names and booleans only — never a value (ClientErrorLogBudget discipline applies to the
        // success log too: a credential in a log line is a credential at rest, unencrypted).
        log.info("Billing credentials updated for provider {}: enabled={}, storedFields={}",
                providerId, enabled, new TreeSet<>(merged.keySet()));
        return statusOf(providerId);
    }

    /** Drops the stored row entirely; the provider returns to environment behaviour. Idempotent. */
    public void deleteCredentials(String providerId) {
        requireManagedProvider(providerId);
        repository.findByProvider(providerId).ifPresent(row -> {
            repository.delete(row);
            log.info("Billing credentials deleted for provider {}; environment configuration (if "
                    + "any) is effective again", providerId);
        });
    }

    /** Applies the submitted failover order; unnamed providers keep their stored positions. */
    public List<ProviderStatusDto> updateOrder(UpdateProviderOrderRequest request) {
        List<String> order = request.order();
        if (new LinkedHashSet<>(order).size() != order.size()) {
            throw DomainException.validation("The provider order must not name a provider twice");
        }
        order.forEach(this::requireManagedProvider);
        for (int position = 0; position < order.size(); position++) {
            String providerId = order.get(position);
            BillingProviderCredentials row = repository.findByProvider(providerId)
                    .orElseGet(() -> {
                        // An order-only row: no credentials, not enabled — it exists solely to
                        // remember the operator's preference for when credentials arrive.
                        BillingProviderCredentials created = new BillingProviderCredentials();
                        created.setProvider(providerId);
                        return created;
                    });
            row.setDisplayOrder(position);
            repository.save(row);
        }
        log.info("Billing provider failover order updated: {}", order);
        return status();
    }

    private ProviderStatusDto statusOf(String providerId) {
        Map<String, String> stored = resolver.storedValues(providerId);
        boolean storedCredentials = !stored.isEmpty();
        boolean envEnabled = availability.environmentEnabled(providerId);
        String source = storedCredentials ? SOURCE_DB : envEnabled ? SOURCE_ENV : SOURCE_NONE;
        String hintValue = null;
        for (String hintField : BillingCredentialFields.hintFields(providerId)) {
            hintValue = storedCredentials ? stored.get(hintField) : environmentValue(providerId, hintField);
            if (hintValue != null && !hintValue.isBlank()) {
                break;
            }
        }
        return new ProviderStatusDto(
                providerId,
                availability.checkoutEnabled(providerId),
                availability.surfaceExists(providerId),
                source,
                mask(hintValue),
                resolver.displayOrder(providerId).orElse(null),
                stored.keySet().stream().sorted().toList());
    }

    /**
     * The environment value of a HINT field only — reachable values are the non-secret identifiers
     * {@code BillingCredentialFields#hintFields} names (plus the Stripe secret-key last resort,
     * masked like everything else).
     */
    private String environmentValue(String providerId, String field) {
        return switch (providerId + ":" + field) {
            case PayTRBillingProvider.PROVIDER_ID + ":merchantId" -> paytrProperties.getMerchantId();
            case IyzicoBillingProvider.PROVIDER_ID + ":apiKey" -> iyzicoProperties.getApiKey();
            case StripeBillingProvider.PROVIDER_ID + ":publishableKey" ->
                    stripeProperties.getPublishableKey();
            case StripeBillingProvider.PROVIDER_ID + ":secretKey" -> stripeProperties.getSecretKey();
            default -> null;
        };
    }

    /** {@code ****} + last four; short values mask entirely. Never returns the input. */
    private static String mask(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() <= 4 ? "****" : "****" + value.substring(value.length() - 4);
    }

    /** New credential rows join the END of the failover order rather than position 0. */
    private int nextDisplayOrder() {
        return repository.findAll().stream()
                .mapToInt(BillingProviderCredentials::getDisplayOrder)
                .max()
                .orElse(-1) + 1;
    }

    /**
     * 404 for a provider id outside the managed vocabulary — phrased like the other billing
     * misses, without echoing the submitted id; the registry's ids are configuration facts.
     */
    private void requireManagedProvider(String providerId) {
        if (providerRegistry.find(providerId).isEmpty()
                || !BillingCredentialFields.isManaged(providerId)) {
            throw DomainException.notFound("Unknown billing provider; managed providers: "
                    + providerRegistry.ids());
        }
    }
}
