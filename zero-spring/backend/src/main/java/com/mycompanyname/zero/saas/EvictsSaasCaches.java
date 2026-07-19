package com.mycompanyname.zero.saas;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a mutation that can change a resolved feature value <em>or</em> a tenant's subscription
 * validity — which in practice means every subscription mutation, because assigning a package
 * changes the inherited edition features as well as the status.
 *
 * <p>A composed annotation rather than two literal {@code @CacheEvict}s at each call site: both
 * caches must always be dropped together, and forgetting one of them is precisely the stale-cache
 * failure this design guards against (see ARCHITECTURE-RULES.md — "Feature ve abonelik cache'i
 * yazmadan sonra bayat kalmamalı").
 *
 * <p>Eviction is coarse ({@code allEntries}) on purpose. A finer key-level invalidation would have
 * to enumerate every tenant subscribed to an edition and every feature name involved; getting that
 * enumeration wrong fails silently, whereas dropping the cache only costs a rebuild.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Caching(evict = {
        @CacheEvict(cacheNames = SaasCaches.FEATURES, allEntries = true),
        @CacheEvict(cacheNames = SaasCaches.SUBSCRIPTION_VALIDITY, allEntries = true)
})
public @interface EvictsSaasCaches {
}
