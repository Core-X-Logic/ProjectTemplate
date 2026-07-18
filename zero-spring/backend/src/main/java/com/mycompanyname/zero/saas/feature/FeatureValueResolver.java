package com.mycompanyname.zero.saas.feature;

import com.mycompanyname.zero.saas.api.FeatureChecker;
import com.mycompanyname.zero.saas.edition.EditionFeature;
import com.mycompanyname.zero.saas.edition.EditionFeatureRepository;
import com.mycompanyname.zero.saas.subscription.Subscription;
import com.mycompanyname.zero.saas.subscription.SubscriptionRepository;
import com.mycompanyname.zero.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Resolves the effective value of a feature through the chain
 * {@code tenant_features} (host override) &rarr; {@code edition_features} (the tenant's edition)
 * &rarr; {@link FeatureDefinition#defaultValue()}.
 *
 * <p>Like {@code SettingManager} this class is <em>pure</em>: the tenant is passed in explicitly (or
 * read from {@link TenantContext} for the convenience overloads), so it never depends on the
 * identity module.
 *
 * <p>Slice A resolves straight from the database on every call; the Redis cache with eviction on
 * feature/edition writes is Slice B (F5-R2).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FeatureValueResolver implements FeatureChecker {

    private final TenantFeatureRepository tenantFeatureRepository;
    private final EditionFeatureRepository editionFeatureRepository;
    private final SubscriptionRepository subscriptionRepository;

    @Override
    public String value(String featureName) {
        return valueFor(TenantContext.getTenantId(), featureName);
    }

    @Override
    public boolean isEnabled(String featureName) {
        return Boolean.parseBoolean(value(featureName));
    }

    @Override
    public int intValue(String featureName) {
        return intValueFor(TenantContext.getTenantId(), featureName);
    }

    @Override
    public String valueFor(Long tenantId, String featureName) {
        FeatureDefinition definition = FeatureDefinitions.require(featureName);
        String override = tenantOverride(tenantId, featureName);
        if (override != null) {
            return override;
        }
        String fromEdition = editionValue(tenantId, featureName);
        if (fromEdition != null) {
            return fromEdition;
        }
        return definition.defaultValue();
    }

    /**
     * Numeric value for an explicit tenant. Unparsable values degrade to {@code 0}, which for
     * limit-style features such as {@code app.maxUserCount} means <em>unlimited</em> (source
     * semantics preserved).
     */
    public int intValueFor(Long tenantId, String featureName) {
        String raw = valueFor(tenantId, featureName);
        try {
            return raw == null ? 0 : Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** The tenant-level override, or {@code null} when the tenant does not override this feature. */
    public String tenantOverride(Long tenantId, String featureName) {
        if (tenantId == null) {
            return null;
        }
        return tenantFeatureRepository.findByTenantIdAndFeatureName(tenantId, featureName)
                .map(TenantFeature::getValue)
                .filter(value -> !value.isBlank())
                .orElse(null);
    }

    /** The value inherited from the tenant's edition, or {@code null} when unset / no subscription. */
    public String editionValue(Long tenantId, String featureName) {
        if (tenantId == null) {
            return null;
        }
        Optional<Subscription> subscription = subscriptionRepository.findByTenantId(tenantId);
        if (subscription.isEmpty()) {
            return null;
        }
        return editionFeatureRepository
                .findByEditionIdAndFeatureName(subscription.get().getEditionId(), featureName)
                .map(EditionFeature::getValue)
                .filter(value -> !value.isBlank())
                .orElse(null);
    }
}
