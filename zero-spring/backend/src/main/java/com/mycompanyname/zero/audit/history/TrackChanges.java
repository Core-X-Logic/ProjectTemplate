package com.mycompanyname.zero.audit.history;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an entity type whose create/update/delete operations must be recorded as
 * {@code EntityChange} history. Recognised by {@link EntityChangeListener} in addition to the
 * explicit type list configured in {@link AuditProperties} (the configured list is authoritative so
 * tracking works even for entities this module must not modify).
 */
@Documented
@Inherited
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TrackChanges {
}
