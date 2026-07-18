package com.mycompanyname.zero.saas.feature;

import com.mycompanyname.zero.saas.SaasCaches;
import com.mycompanyname.zero.saas.edition.EditionFeature;
import com.mycompanyname.zero.saas.edition.EditionFeatureRepository;
import com.mycompanyname.zero.saas.subscription.Subscription;
import com.mycompanyname.zero.saas.subscription.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Cached back end of {@link FeatureValueResolver}.
 *
 * <p>It exists as a separate bean for one reason: {@code @Cacheable} is proxy-based, so a cached
 * method that is only ever reached by self-invocation would never be cached. Routing every
 * resolution through this collaborator guarantees the cache is consulted no matter which of the
 * resolver's overloads the caller used.
 *
 * <p>Deliberately not a {@code @Service} and not {@code @Transactional}: that keeps the caching
 * interceptor the outermost advice, so a cache hit costs neither a transaction nor a Hibernate
 * filter activation.
 */
@Component
@RequiredArgsConstructor
public class FeatureValueLoader {

    private final TenantFeatureRepository tenantFeatureRepository;
    private final EditionFeatureRepository editionFeatureRepository;
    private final SubscriptionRepository subscriptionRepository;

    /**
     * Effective value of {@code featureName} for {@code tenantId}, resolved through
     * tenant override &rarr; edition &rarr; definition default.
     *
     * <p>Cached under {@code feature:{tenantId}:{name}} (F5-ARCHITECTURE §6). Unknown feature names
     * raise before anything is cached, so the registry stays authoritative.
     */
    @Cacheable(cacheNames = SaasCaches.FEATURES,
            key = "'feature:' + (#tenantId != null ? #tenantId : 'host') + ':' + #featureName")
    public String resolve(Long tenantId, String featureName) {
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
     * The tenant-level override, or {@code null} when the tenant does not override this feature.
     * Intentionally uncached: the admin editor must always see the raw, current level.
     */
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
