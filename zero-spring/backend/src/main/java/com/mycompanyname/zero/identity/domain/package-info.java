/**
 * Identity aggregate roots, exported as the {@code domain} named interface.
 *
 * <p>The {@code tenantFilter} / {@code hostFilter} {@code @FilterDef}s used to be declared here.
 * They now live in {@code com.mycompanyname.zero.shared.domain}, which explains why; the entities
 * in this package keep using them unchanged, since Hibernate resolves a filter by name across the
 * whole persistence unit.
 */
@NamedInterface("domain")
package com.mycompanyname.zero.identity.domain;

import org.springframework.modulith.NamedInterface;
