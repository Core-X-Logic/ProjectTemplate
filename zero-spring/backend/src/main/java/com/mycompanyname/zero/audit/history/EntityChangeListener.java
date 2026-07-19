package com.mycompanyname.zero.audit.history;

import com.mycompanyname.zero.audit.AuditPrincipal;
import com.mycompanyname.zero.audit.AuditSupport;
import com.mycompanyname.zero.audit.domain.EntityChange;
import com.mycompanyname.zero.audit.domain.EntityChangeType;
import com.mycompanyname.zero.audit.domain.EntityPropertyChange;
import com.mycompanyname.zero.shared.domain.TrackChanges;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostDeleteEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Hibernate post-commit event listener (registered via {@link AuditHibernateConfigurer}) that turns
 * inserts/updates/deletes of tracked entities into {@code EntityChange} history.
 *
 * <p>An entity is tracked when it carries {@link TrackChanges} (the authority) or when its
 * fully-qualified name is listed in {@link AuditProperties} (the escape hatch for types this module
 * must not modify).
 *
 * <p>Loop safety: entities in this module's own package are never tracked, so writing history rows
 * cannot re-trigger tracking. Changes are buffered per transaction and flushed by
 * {@link EntityChangeWriter} after commit, so no new rows are pushed into the caller's active flush.
 */
@Component
@Slf4j
public class EntityChangeListener
        implements PostInsertEventListener, PostUpdateEventListener, PostDeleteEventListener {

    /**
     * Root package of the audit module, derived from a class that lives in it rather than written
     * out as a literal. {@code AuditSupport} sits directly in the module root, so this resolves to
     * the parent of both {@code audit.domain} (where the history rows are mapped) and
     * {@code audit.history} (this package). Deriving it keeps the loop guard working after a
     * template clone renames the base package — a stale literal would silently stop matching and
     * let history writes re-trigger history writes.
     */
    private static final String AUDIT_PACKAGE = AuditSupport.class.getPackageName();

    private static final ThreadLocal<List<EntityChange>> BUFFER = new ThreadLocal<>();

    private final AuditProperties auditProperties;
    private final ObjectProvider<EntityChangeWriter> writerProvider;

    public EntityChangeListener(AuditProperties auditProperties, ObjectProvider<EntityChangeWriter> writerProvider) {
        this.auditProperties = auditProperties;
        this.writerProvider = writerProvider;
    }

    @Override
    public void onPostInsert(PostInsertEvent event) {
        EntityPersister persister = event.getPersister();
        if (!isTracked(event.getEntity(), persister)) {
            return;
        }
        EntityChange change = newChange(EntityChangeType.CREATED, event.getId(), persister);
        String[] names = persister.getPropertyNames();
        Object[] state = event.getState();
        if (names != null && state != null) {
            for (int i = 0; i < names.length && i < state.length; i++) {
                Object value = state[i];
                if (value == null || !AuditSupport.isSimpleValue(value)) {
                    continue;
                }
                addProperty(change, names[i], null, value);
            }
        }
        enqueue(change);
    }

    @Override
    public void onPostUpdate(PostUpdateEvent event) {
        EntityPersister persister = event.getPersister();
        if (!isTracked(event.getEntity(), persister)) {
            return;
        }
        EntityChange change = newChange(EntityChangeType.UPDATED, event.getId(), persister);
        String[] names = persister.getPropertyNames();
        Object[] oldState = event.getOldState();
        Object[] newState = event.getState();
        if (names != null && oldState != null && newState != null) {
            int[] dirty = event.getDirtyProperties();
            if (dirty != null) {
                for (int index : dirty) {
                    if (index >= 0 && index < names.length && index < oldState.length && index < newState.length) {
                        recordUpdate(change, names[index], oldState[index], newState[index]);
                    }
                }
            } else {
                for (int i = 0; i < names.length && i < oldState.length && i < newState.length; i++) {
                    recordUpdate(change, names[i], oldState[i], newState[i]);
                }
            }
        }
        enqueue(change);
    }

    @Override
    public void onPostDelete(PostDeleteEvent event) {
        EntityPersister persister = event.getPersister();
        if (!isTracked(event.getEntity(), persister)) {
            return;
        }
        enqueue(newChange(EntityChangeType.DELETED, event.getId(), persister));
    }

    @Override
    public boolean requiresPostCommitHandling(EntityPersister persister) {
        return false;
    }

    private void recordUpdate(EntityChange change, String name, Object oldValue, Object newValue) {
        if (Objects.equals(oldValue, newValue)) {
            return;
        }
        if (!AuditSupport.isSimpleValue(oldValue) && !AuditSupport.isSimpleValue(newValue)) {
            return;
        }
        addProperty(change, name, oldValue, newValue);
    }

    private void addProperty(EntityChange change, String name, Object oldValue, Object newValue) {
        EntityPropertyChange propertyChange = new EntityPropertyChange();
        propertyChange.setPropertyName(AuditSupport.truncate(name, 128));
        boolean sensitive = AuditSupport.isSensitive(name);
        propertyChange.setOriginalValue(sensitive ? mask(oldValue) : AuditSupport.formatValue(oldValue));
        propertyChange.setNewValue(sensitive ? mask(newValue) : AuditSupport.formatValue(newValue));
        change.addPropertyChange(propertyChange);
    }

    private String mask(Object value) {
        return value == null ? null : "***";
    }

    private EntityChange newChange(EntityChangeType type, Object id, EntityPersister persister) {
        EntityChange change = new EntityChange();
        change.setChangeType(type);
        change.setEntityTypeName(AuditSupport.truncate(persister.getEntityName(), 256));
        change.setEntityId(AuditSupport.truncate(id == null ? "" : String.valueOf(id), 64));
        change.setChangeTime(Instant.now());
        change.setUserId(AuditPrincipal.userId());
        change.setTenantId(AuditPrincipal.tenantId());
        return change;
    }

    private boolean isTracked(Object entity, EntityPersister persister) {
        if (entity == null) {
            return false;
        }
        Class<?> type = entity.getClass();
        return isTrackedType(type, persister != null ? persister.getEntityName() : type.getName());
    }

    /**
     * The tracking decision itself, separated from the Hibernate event plumbing so it can be
     * exercised directly — with real entity classes and no database — by
     * {@code EntityChangeTrackingTest}. Visible for testing.
     *
     * <p>Order matters: the audit module's own types are rejected first (loop guard) and cannot be
     * re-enabled by configuration; then the configured escape-hatch list; then {@link TrackChanges}.
     */
    boolean isTrackedType(Class<?> type, String entityName) {
        if (type == null) {
            return false;
        }
        String className = type.getName();
        if (className.startsWith(AUDIT_PACKAGE) || (entityName != null && entityName.startsWith(AUDIT_PACKAGE))) {
            return false;
        }
        Set<String> tracked = auditProperties.getTrackedEntityTypes();
        if (tracked.contains(className) || (entityName != null && tracked.contains(entityName))) {
            return true;
        }
        return isAnnotated(type);
    }

    /** The derived audit-module root package used by the loop guard. Visible for testing. */
    static String auditPackage() {
        return AUDIT_PACKAGE;
    }

    private boolean isAnnotated(Class<?> type) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            if (current.isAnnotationPresent(TrackChanges.class)) {
                return true;
            }
            current = current.getSuperclass();
        }
        return false;
    }

    private void enqueue(EntityChange change) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.debug("No active transaction synchronization; skipping entity change for {}", change.getEntityTypeName());
            return;
        }
        List<EntityChange> buffer = BUFFER.get();
        if (buffer == null) {
            buffer = new ArrayList<>();
            BUFFER.set(buffer);
            TransactionSynchronizationManager.registerSynchronization(new BufferSynchronization());
        }
        buffer.add(change);
    }

    private final class BufferSynchronization implements TransactionSynchronization {

        @Override
        public void afterCommit() {
            List<EntityChange> buffer = BUFFER.get();
            if (buffer == null || buffer.isEmpty()) {
                return;
            }
            try {
                writerProvider.getObject().writeAll(new ArrayList<>(buffer));
            } catch (RuntimeException ex) {
                log.warn("Failed to persist {} entity change(s): {}", buffer.size(), ex.getMessage());
            }
        }

        @Override
        public void afterCompletion(int status) {
            BUFFER.remove();
        }
    }
}
