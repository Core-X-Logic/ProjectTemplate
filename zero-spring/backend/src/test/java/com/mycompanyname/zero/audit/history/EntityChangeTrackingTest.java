package com.mycompanyname.zero.audit.history;

import com.mycompanyname.zero.audit.domain.EntityChange;
import com.mycompanyname.zero.audit.domain.EntityPropertyChange;
import com.mycompanyname.zero.identity.domain.Role;
import com.mycompanyname.zero.identity.domain.User;
import com.mycompanyname.zero.identity.ou.OrganizationUnit;
import com.mycompanyname.zero.settings.domain.Setting;
import com.mycompanyname.zero.shared.domain.TrackChanges;
import com.mycompanyname.zero.tenancy.Tenant;
import java.lang.annotation.Inherited;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the entity-history tracking decision against silent breakage.
 *
 * <p><strong>Why this test exists.</strong> Tracking used to be driven by a hard-coded list of
 * fully-qualified class-name <em>strings</em> in {@link AuditProperties}:
 *
 * <pre>{@code types.add("com.mycompanyname.zero.identity.domain.User");}</pre>
 *
 * <p>This repository is a template. A clone renames the base package, the compiler happily rebuilds,
 * every existing test stays green — and the strings now name classes that do not exist. Nothing
 * throws. Nothing logs. Entity history simply stops being written and {@code /api/entity-changes}
 * returns an empty page forever. That is silent data loss, and until this test there was nothing in
 * the suite that would notice.
 *
 * <p><strong>Why it is written with class references.</strong> Every assertion below binds through
 * {@code User.class}, {@code Role.class}, {@code OrganizationUnit.class} and {@code Tenant.class}
 * rather than through name strings. A rename therefore carries the test along with the production
 * code: an IDE refactor updates both, and a rename that misses one of them fails to compile instead
 * of failing silently at runtime. A string literal in this file would reintroduce exactly the bug it
 * is meant to catch.
 *
 * <p>The decision under test is {@link EntityChangeListener#isTrackedType(Class, String)} — the real
 * predicate the Hibernate listener consults, exercised here without a database.
 */
class EntityChangeTrackingTest {

    /** Builds the listener the way Spring does, but with whatever configuration a test wants. */
    private EntityChangeListener listenerWith(Set<String> configuredTypes) {
        AuditProperties properties = new AuditProperties();
        properties.setTrackedEntityTypes(new LinkedHashSet<>(configuredTypes));
        return new EntityChangeListener(properties, null);
    }

    private EntityChangeListener listener() {
        return listenerWith(Set.of());
    }

    /**
     * The core guarantee: the four platform entities are tracked with <em>no</em> configuration at
     * all, which proves the {@link TrackChanges} annotation — not a string list — is what drives
     * tracking. Remove {@code @TrackChanges} from any of these classes and this test goes red.
     */
    @ParameterizedTest
    @ValueSource(classes = {User.class, Role.class, OrganizationUnit.class, Tenant.class})
    void platformEntitiesAreTrackedWithoutAnyConfiguration(Class<?> entityType) {
        assertThat(listener().isTrackedType(entityType, entityType.getName()))
                .as("%s must be tracked; it carries @TrackChanges", entityType.getSimpleName())
                .isTrue();
    }

    /**
     * Hibernate reports the entity name, which may differ from the Java class name. Tracking must
     * hold either way, so the annotation path cannot be bypassed by a naming mismatch.
     */
    @ParameterizedTest
    @ValueSource(classes = {User.class, Role.class, OrganizationUnit.class, Tenant.class})
    void platformEntitiesAreTrackedRegardlessOfReportedEntityName(Class<?> entityType) {
        assertThat(listener().isTrackedType(entityType, null))
                .as("%s must be tracked even when no entity name is reported", entityType.getSimpleName())
                .isTrue();
    }

    /**
     * The annotation must actually be on the class (not merely inherited from a shared superclass),
     * otherwise {@code AbstractAuditedEntity} would silently opt every entity in the codebase into
     * history.
     */
    @ParameterizedTest
    @ValueSource(classes = {User.class, Role.class, OrganizationUnit.class, Tenant.class})
    void platformEntitiesDeclareTheAnnotationDirectly(Class<?> entityType) {
        assertThat(entityType.getDeclaredAnnotation(TrackChanges.class))
                .as("%s must declare @TrackChanges itself", entityType.getSimpleName())
                .isNotNull();
    }

    /**
     * Proves the assertions above are not vacuous: a real entity from another module that does not
     * carry the annotation is not tracked. Without this, {@code isTrackedType} could be returning
     * true for everything and every test above would still pass.
     *
     * <p>{@code Setting} is used deliberately rather than a locally declared stand-in class: any
     * class declared in this test would live in the audit package and be rejected by the loop guard
     * instead of by the missing annotation, which would make this test pass for the wrong reason.
     */
    @Test
    void anUnannotatedEntityFromAnotherModuleIsNotTracked() {
        assertThat(listener().isTrackedType(Setting.class, Setting.class.getName()))
                .as("%s carries no @TrackChanges and must not be tracked", Setting.class.getSimpleName())
                .isFalse();
    }

    /**
     * Hibernate persists bytecode-enhanced subclasses and proxies of an entity. Without
     * {@code @Inherited}, those would silently fall out of history — the same class of invisible
     * failure this whole test exists to prevent.
     */
    @Test
    void theAnnotationIsInheritedSoHibernateSubclassesStayTracked() {
        assertThat(TrackChanges.class.isAnnotationPresent(Inherited.class))
                .as("@TrackChanges must be @Inherited to cover Hibernate's enhanced subclasses")
                .isTrue();
    }

    /**
     * The configuration property survives only as an escape hatch for entities whose source cannot
     * be annotated. It must still work, and it must be additive rather than authoritative — the same
     * {@code Setting} that is untracked by default becomes tracked once named.
     */
    @Test
    void theConfigurationEscapeHatchStillAddsTypes() {
        EntityChangeListener listener = listenerWith(Set.of(Setting.class.getName()));
        assertThat(listener.isTrackedType(Setting.class, Setting.class.getName()))
                .as("an explicitly configured type must be tracked even without the annotation")
                .isTrue();
    }

    /**
     * The default must be empty. If a future change reintroduces hard-coded fully-qualified names
     * here, the silent-rename failure mode comes back with it.
     */
    @Test
    void trackedEntityTypesConfigurationIsEmptyByDefault() {
        assertThat(new AuditProperties().getTrackedEntityTypes())
                .as("the annotation is the authority; the property must default to empty")
                .isEmpty();
    }

    /**
     * Loop guard. The audit module's own entities must never be tracked — writing a history row
     * would otherwise produce another history row. This must hold even when configuration explicitly
     * asks for them, which is what makes this assertion discriminating: if the derived audit package
     * were wrong (for example resolving to {@code ...audit.history} instead of the module root, thus
     * missing {@code ...audit.domain}), the configured entry would win and this would go red.
     */
    @Test
    void auditsOwnEntitiesAreNeverTrackedEvenWhenExplicitlyConfigured() {
        EntityChangeListener listener = listenerWith(Set.of(
                EntityChange.class.getName(), EntityPropertyChange.class.getName()));

        assertThat(listener.isTrackedType(EntityChange.class, EntityChange.class.getName()))
                .as("EntityChange must never be tracked, or history writes loop")
                .isFalse();
        assertThat(listener.isTrackedType(EntityPropertyChange.class, EntityPropertyChange.class.getName()))
                .as("EntityPropertyChange must never be tracked, or history writes loop")
                .isFalse();
    }

    /**
     * The loop-guard prefix is derived from the audit module's own package rather than written as a
     * literal, so a template clone that renames the base package keeps working. Asserted through
     * class references: if the derivation drifts to a sub-package, it stops covering the history
     * entities and this fails.
     */
    @Test
    void theDerivedAuditPackageCoversTheWholeAuditModule() {
        String auditPackage = EntityChangeListener.auditPackage();

        assertThat(EntityChange.class.getName())
                .as("the derived audit package must cover audit.domain, where history rows live")
                .startsWith(auditPackage);
        assertThat(EntityChangeListener.class.getName())
                .as("the derived audit package must cover audit.history")
                .startsWith(auditPackage);
        assertThat(User.class.getName())
                .as("the derived audit package must not swallow tracked entities from other modules")
                .doesNotStartWith(auditPackage);
    }
}
