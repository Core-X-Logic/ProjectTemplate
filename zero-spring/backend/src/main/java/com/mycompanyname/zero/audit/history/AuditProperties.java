package com.mycompanyname.zero.audit.history;

import java.util.LinkedHashSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Audit configuration. {@code trackedEntityTypes} is the authoritative list of fully-qualified
 * entity class names whose changes are recorded. Kept as a fixed list (rather than only relying on a
 * marker annotation on the entities) so tracking is deterministic and never collides with other
 * writers. Defaults cover the Phase 2 auditable entities; overridable via
 * {@code zero.audit.tracked-entity-types}.
 */
@Component
@ConfigurationProperties(prefix = "zero.audit")
@Getter
@Setter
public class AuditProperties {

    private Set<String> trackedEntityTypes = defaultTrackedEntityTypes();

    private static Set<String> defaultTrackedEntityTypes() {
        Set<String> types = new LinkedHashSet<>();
        types.add("com.mycompanyname.zero.identity.domain.Role");
        types.add("com.mycompanyname.zero.identity.domain.User");
        types.add("com.mycompanyname.zero.identity.ou.OrganizationUnit");
        types.add("com.mycompanyname.zero.tenancy.Tenant");
        return types;
    }
}
