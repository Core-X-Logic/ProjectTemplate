package com.mycompanyname.zero.saas.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declarative feature gate: the annotated method (or every method of the annotated type) is only
 * reachable while <em>all</em> named features resolve to {@code true} for the calling tenant.
 * Otherwise the call fails with {@code DomainException(SUBSCRIPTION_INVALID-adjacent FORBIDDEN)},
 * which the global handler renders as 403.
 *
 * <p>Feature names come from {@link SaasFeatures}. Resolution follows the usual chain
 * (tenant override &rarr; edition &rarr; definition default) and is cached, so the check costs
 * nothing on the hot path. Host requests carry no tenant and therefore always see the definition
 * default — a host administrator is never locked out by a tenant's package.
 *
 * <p>Numeric limits are <em>not</em> expressible here: they need the actual usage count, so they
 * stay programmatic via {@link FeatureChecker#intValue(String)} (0 = unlimited).
 *
 * <p>The source system declared an equivalent attribute but never used it (grep: 0 occurrences);
 * this is the first place the gate is actually wired up.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface RequiresFeature {

    /** Feature names that must all be enabled; see {@link SaasFeatures}. */
    String[] value();
}
