package com.mycompanyname.zero.shared.domain;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an entity type whose create/update/delete operations are recorded as entity change history
 * by the audit module.
 *
 * <p><strong>Why this lives in {@code shared} and not in {@code audit}:</strong> the entities that
 * need it ({@code User}, {@code Role}, {@code OrganizationUnit}, {@code Tenant}) belong to the
 * {@code identity} and {@code tenancy} modules, neither of which may depend on {@code audit}.
 * Annotating them with an {@code audit}-owned type fails the Spring Modulith verification with
 * {@code Module 'identity' depends on module 'audit'}. {@code shared} is an
 * {@code ApplicationModule.Type.OPEN} module that every entity already depends on (they all extend
 * {@link AbstractAuditedEntity}), so declaring the marker here adds no new module edge.
 *
 * <p><strong>This annotation is the authority on what gets tracked.</strong> It is deliberately a
 * compile-time, class-level marker rather than a list of fully-qualified class-name strings: this
 * repository is a template, and a clone that renames {@code com.mycompanyname.zero} must not
 * silently lose its entity history. A stale string list produces no exception and no log line — only
 * a permanently empty {@code /api/entity-changes}. A stale annotation is impossible; the compiler
 * moves it with the class.
 *
 * <p>The {@code zero.audit.tracked-entity-types} property remains as an escape hatch for entities
 * whose source you cannot annotate (third-party or generated types). It is empty by default.
 *
 * <p>{@code @Inherited} so bytecode-enhanced Hibernate subclasses and mapped-superclass hierarchies
 * are covered.
 */
@Documented
@Inherited
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TrackChanges {
}
