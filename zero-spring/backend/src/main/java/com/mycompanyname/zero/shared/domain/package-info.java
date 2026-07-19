/**
 * Cross-module persistence vocabulary: the mapped superclass every entity extends, the
 * {@code @TrackChanges} marker, and the two tenant filter definitions below.
 *
 * <p><strong>Why the {@code @FilterDef}s live here and not in {@code identity.domain}.</strong>
 * They started there, next to the first entities that used them. Then {@code AuditLog},
 * {@code EntityChange} and {@code UserNotification} needed {@code tenantFilter} too — and a
 * {@code @Filter(name = "tenantFilter")} on an {@code audit} entity resolving against a definition
 * owned by {@code identity} is a real dependency that nothing can see. It is matched by NAME at
 * boot, so there is no import, no bytecode reference, and no compile error; delete the definition
 * and the failure surfaces as a Hibernate mapping exception in an unrelated module.
 *
 * <p>That dependency also contradicts the modules' own declarations: {@code audit} declares
 * {@code allowedDependencies = {"shared"}} and {@code notification} declares
 * {@code {"shared", "settings"}}. Neither lists {@code identity}. This was MEASURED, not assumed —
 * with the filters added and the definitions still in {@code identity.domain},
 * {@code ModularityTests} passes. Spring Modulith cannot catch a string-resolved edge, so the
 * placement has to be right by construction rather than by test.
 *
 * <p>{@code shared} is an {@code ApplicationModule.Type.OPEN} module that every entity already
 * depends on (they all extend {@link com.mycompanyname.zero.shared.domain.AbstractAuditedEntity}),
 * so declaring the filters here adds no module edge and makes each module's declared dependency
 * list true again. This is the same move, for the same reason, that put {@code @TrackChanges} in
 * this package instead of in {@code audit.history}.
 *
 * <p><strong>Defining a filter is not enabling it.</strong> {@code HibernateTenantFilterAspect}
 * turns exactly one of these on per transaction, and Hibernate applies it only to entities that
 * declare a matching {@code @Filter}. Whether an entity declares {@code hostFilter} is therefore a
 * per-entity product decision, not a default: the audit entities deliberately omit it so host
 * support can review across tenants. See {@code TenantFilterCoverageIT}.
 */
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = Long.class))
@FilterDef(name = "hostFilter")
package com.mycompanyname.zero.shared.domain;

import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
