package com.mycompanyname.zero.audit.history;

import java.util.LinkedHashSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Audit configuration.
 *
 * <p><strong>The authority on what gets tracked is the
 * {@code com.mycompanyname.zero.shared.domain.TrackChanges} annotation on the entity class</strong>,
 * not this property. {@code trackedEntityTypes} is intentionally <em>empty</em> by default.
 *
 * <p>It previously held a hard-coded list of fully-qualified entity class names. That is a silent
 * failure mode for a template repository: a clone that renames the base package leaves the strings
 * pointing at classes that no longer exist. Nothing throws, nothing logs — entity history just stops
 * being written and {@code /api/entity-changes} returns an empty page forever. The annotation cannot
 * drift that way, because the compiler carries it along with the class.
 *
 * <p>This property survives as an escape hatch for the one case the annotation cannot cover:
 * tracking an entity whose source you cannot modify (a third-party or generated type). Set
 * {@code zero.audit.tracked-entity-types} to a list of fully-qualified class names to add such
 * types. Entries are matched against both the Java class name and the Hibernate entity name.
 * Anything inside the audit module's own package is ignored regardless, to prevent a write loop.
 */
@Component
@ConfigurationProperties(prefix = "zero.audit")
@Getter
@Setter
public class AuditProperties {

    /**
     * Additional entity types to track, beyond those annotated with {@code @TrackChanges}. Empty by
     * default on purpose — see the class javadoc.
     */
    private Set<String> trackedEntityTypes = new LinkedHashSet<>();
}
