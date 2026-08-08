package com.mycompanyname.zero.saas.billing.credentials;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompanyname.zero.config.FieldEncryptionService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Resolves a provider's EFFECTIVE credential values: the stored, encrypted DB set when one exists,
 * the environment-bound {@code Billing*Properties} value otherwise (ADR-0020). This is the single
 * reading seam of {@code billing_provider_credentials}; the admin service is the single writing one.
 *
 * <p><b>Resolved at CALL time, never at construction.</b> Every lookup goes back to the repository,
 * so a credential saved through the portal is live on the very next checkout or webhook without a
 * restart. Deliberately NO cache: a checkout initiation performs a handful of these lookups, one
 * decrypt each — cheap — while a cache would hold decrypted secrets in memory beyond the request
 * that needed them (recorded as the alternative rejected in ADR-0020).
 *
 * <p><b>Fallback is per FIELD, not per row</b>: a stored set normally carries every field (the
 * write path merges instead of overwriting), but if a field is absent or blank the environment
 * value answers — which is also what makes "DB row deleted → environment behaviour returns" hold
 * with no special case.
 *
 * <p>A ciphertext that fails to decrypt (tampered row, rotated {@code zero.crypto.field-key})
 * propagates {@code FieldEncryptionService}'s loud failure to the caller — checkout answers 500 and
 * the log names the cause; silently falling back to the environment would run money through
 * credentials the operator believes replaced.
 */
@Component
@RequiredArgsConstructor
public class BillingCredentialsResolver {

    private static final TypeReference<Map<String, String>> CREDENTIAL_MAP =
            new TypeReference<>() {
            };

    private final BillingProviderCredentialsRepository repository;
    private final FieldEncryptionService fieldEncryptionService;
    private final ObjectMapper objectMapper;

    /**
     * The effective value of one credential field: the stored value when the provider has a stored
     * set carrying it non-blank, otherwise {@code environmentValue} unchanged.
     */
    public String effectiveValue(String providerId, String field, String environmentValue) {
        String stored = storedValues(providerId).get(field);
        return stored == null || stored.isBlank() ? environmentValue : stored;
    }

    /** Whether a stored credential set exists at all (an order-only row does not count). */
    public boolean hasStoredCredentials(String providerId) {
        return repository.findByProvider(providerId)
                .map(row -> row.getCredentialsSecret() != null)
                .orElse(false);
    }

    /** Whether the stored row enables NEW checkouts — requires stored credentials to exist. */
    public boolean isEnabledByStore(String providerId) {
        return repository.findByProvider(providerId)
                .map(row -> row.isEnabled() && row.getCredentialsSecret() != null)
                .orElse(false);
    }

    /** The stored failover position, empty when the provider has no row. */
    public Optional<Integer> displayOrder(String providerId) {
        return repository.findByProvider(providerId)
                .map(BillingProviderCredentials::getDisplayOrder);
    }

    /**
     * The decrypted stored field map, empty when no credentials are stored. Package-private: the
     * admin service needs it for merging and hints; everything outside this package goes through
     * {@link #effectiveValue} and never sees a whole plaintext set.
     */
    Map<String, String> storedValues(String providerId) {
        return repository.findByProvider(providerId)
                .map(BillingProviderCredentials::getCredentialsSecret)
                .map(this::decryptToMap)
                .orElseGet(LinkedHashMap::new);
    }

    /** Serializes and encrypts a field map — the admin service's write half of this seam. */
    String encryptFromMap(Map<String, String> values) {
        try {
            return fieldEncryptionService.encrypt(objectMapper.writeValueAsString(values));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize the billing credential set", ex);
        }
    }

    private Map<String, String> decryptToMap(String ciphertext) {
        String json = fieldEncryptionService.decrypt(ciphertext);
        try {
            return objectMapper.readValue(json, CREDENTIAL_MAP);
        } catch (JsonProcessingException ex) {
            // The ciphertext authenticated (GCM) but the plaintext is not the expected JSON —
            // only a code change can produce this; loud, like a decrypt failure.
            throw new IllegalStateException("Stored billing credentials are not a valid field map", ex);
        }
    }
}
