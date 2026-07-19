package com.mycompanyname.zero.permission;

import com.mycompanyname.zero.identity.domain.AppPermissions;
import com.mycompanyname.zero.identity.domain.PermissionDefinition;
import com.mycompanyname.zero.identity.domain.PermissionDefinitions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A permission only works when three registries agree: the constant ({@link AppPermissions}), the
 * tree that makes it grantable in the UI ({@link PermissionDefinitions}), and the message bundles
 * that give it a label. Nothing at runtime notices when they disagree — each half keeps working on
 * its own, and the gap only shows up as "why can't I tick that box?".
 *
 * <p>This is not hypothetical. When this test was written it failed immediately on a real drift
 * (R-31): {@code roles.manage} was returned by {@code AppPermissions.all()} — so the seeder granted
 * it to every Admin role — while being absent from the tree and from both message bundles. It could
 * not be seen, granted to another role, or revoked through any screen, and no endpoint consumed it.
 *
 * <p><b>Deliberately relationship-based, never count-based.</b> An assertion like "there must be 22
 * permissions" breaks on every legitimate addition and teaches nobody anything; it trains people to
 * update the number and move on. Every assertion here states a rule that stays true no matter how
 * many permissions exist, so a new permission is silent when it is wired up correctly and loud when
 * it is not.
 *
 * <p>{@code SaasPermissionsAlignmentTest} is the narrower sibling of this test: it additionally
 * pins the SaaS leaves to {@code Side.HOST}, which is a security invariant of that module rather
 * than a general alignment property, so it stays where it is.
 */
class PermissionRegistryAlignmentTest {

    /**
     * Surefire runs with {@code ${basedir}} as the working directory. The bundles are discovered on
     * the filesystem rather than hardcoded as "en and tr", so adding a locale automatically extends
     * this test instead of quietly escaping it — the same reason the architecture rules read source.
     */
    private static final Path I18N_DIRECTORY = Path.of("src", "main", "resources", "i18n");

    @Test
    void everyGrantablePermissionIsALeafOfTheTreeAndViceVersa() {
        Set<String> grantable = AppPermissions.all();
        Set<String> leaves = PermissionDefinitions.leafPermissionNames();

        assertThat(difference(grantable, leaves))
                .as("granted by the seeder but missing from the permission tree: the Admin role "
                        + "receives these, yet no screen can show, grant or revoke them")
                .isEmpty();

        assertThat(difference(leaves, grantable))
                .as("offered by the permission tree but not in AppPermissions.all(): these appear "
                        + "as tickable boxes that RoleService rejects as unknown permissions")
                .isEmpty();
    }

    @Test
    void everyDeclaredConstantIsReturnedByAll() {
        assertThat(difference(declaredPermissionConstants(), AppPermissions.all()))
                .as("declared as an AppPermissions constant but absent from all(): a @PreAuthorize "
                        + "can reference it and compile, but no role is ever granted it, so the "
                        + "endpoint 403s for everyone including the Admin")
                .isEmpty();
    }

    /**
     * Modules that own endpoints but may not depend on {@code identity} keep their own permission
     * constants ({@code SaasPermissions}, {@code AuditPermissions}, {@code SettingsPermissions},
     * {@code TenantPermissions}) and the identity side repeats the values to register them. That
     * duplication is only safe while something checks it — this is that something.
     *
     * <p>The classes are discovered from the source tree rather than listed here, so a permission
     * class added for a future module is covered the day it appears instead of the day someone
     * remembers to add it to this test.
     */
    @Test
    void everyModuleOwnedPermissionIsRegisteredInTheSharedRegistry() {
        List<Class<?>> permissionClasses = modulePermissionClasses();
        assertThat(permissionClasses)
                .as("no module permission class discovered — this test must not pass by finding "
                        + "nothing to check")
                .isNotEmpty();

        for (Class<?> permissionClass : permissionClasses) {
            assertThat(difference(invokeAll(permissionClass), AppPermissions.all()))
                    .as("%s declares permissions that AppPermissions does not register: the module "
                            + "guards its endpoints with them, but no role can ever be granted "
                            + "them, so those endpoints 403 for every caller",
                            permissionClass.getSimpleName())
                    .isEmpty();
        }
    }

    @Test
    void everyTreeNodeHangsOffADeclaredParent() {
        Set<String> declaredNodes = new LinkedHashSet<>();
        PermissionDefinitions.tree().forEach(definition -> declaredNodes.add(definition.name()));

        List<String> orphans = new ArrayList<>();
        for (PermissionDefinition definition : PermissionDefinitions.tree()) {
            if (definition.parent() != null && !declaredNodes.contains(definition.parent())) {
                orphans.add(definition.name() + " -> " + definition.parent());
            }
        }

        assertThat(orphans)
                .as("tree nodes whose parent is not itself a declared node: the branch is dropped "
                        + "when the tree is assembled, so the permission never reaches the UI")
                .isEmpty();
    }

    @Test
    void everyTreeNodeHasALabelInEveryBundle() {
        List<Path> bundles = messageBundles();
        assertThat(bundles)
                .as("no message bundle found under %s — this test must not pass by finding nothing "
                        + "to check", I18N_DIRECTORY.toAbsolutePath())
                .isNotEmpty();

        for (Path bundle : bundles) {
            Properties messages = load(bundle);
            Set<String> missing = new TreeSet<>();
            for (PermissionDefinition definition : PermissionDefinitions.tree()) {
                String label = messages.getProperty(definition.displayNameKey());
                if (label == null || label.isBlank()) {
                    missing.add(definition.displayNameKey());
                }
            }
            assertThat(missing)
                    .as("permission tree nodes with no label in %s — the tree falls back to the raw "
                            + "key, so the user is asked to grant a permission called "
                            + "'Permission.users.read'", bundle.getFileName())
                    .isEmpty();
        }
    }

    /** Values of every {@code public static final String} declared on {@link AppPermissions}. */
    private static Set<String> declaredPermissionConstants() {
        Set<String> values = new LinkedHashSet<>();
        for (Field field : AppPermissions.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers)
                    && Modifier.isFinal(modifiers) && field.getType() == String.class) {
                try {
                    values.add((String) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("Cannot read AppPermissions." + field.getName(), e);
                }
            }
        }
        return values;
    }

    /**
     * Every {@code *Permissions} class under the production source root except {@link AppPermissions}
     * itself, which is the registry the others are checked against.
     */
    private static List<Class<?>> modulePermissionClasses() {
        Path sourceRoot = Path.of("src", "main", "java");
        if (!Files.isDirectory(sourceRoot)) {
            throw new IllegalStateException(
                    "Source root " + sourceRoot.toAbsolutePath() + " not found (working dir: "
                            + Path.of("").toAbsolutePath() + "). Discovering nothing would let this "
                            + "test pass while checking no module at all.");
        }
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            return files.filter(path -> path.getFileName().toString().endsWith("Permissions.java"))
                    .map(path -> sourceRoot.relativize(path).toString()
                            .replace(".java", "")
                            .replace('\\', '.')
                            .replace('/', '.'))
                    .filter(className -> !className.equals(AppPermissions.class.getName()))
                    .sorted()
                    .map(PermissionRegistryAlignmentTest::load)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot walk " + sourceRoot, e);
        }
    }

    private static Class<?> load(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Cannot load discovered permission class " + className, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> invokeAll(Class<?> permissionClass) {
        try {
            return (Set<String>) permissionClass.getMethod("all").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    permissionClass.getName() + " must expose a public static Set<String> all() so "
                            + "its permissions can be checked against the registry", e);
        }
    }

    private static Set<String> difference(Set<String> from, Set<String> without) {
        Set<String> result = new TreeSet<>(from);
        result.removeAll(without);
        return result;
    }

    private static List<Path> messageBundles() {
        if (!Files.isDirectory(I18N_DIRECTORY)) {
            throw new IllegalStateException(
                    "Message bundle directory " + I18N_DIRECTORY.toAbsolutePath() + " not found "
                            + "(working dir: " + Path.of("").toAbsolutePath() + "). A label check "
                            + "that cannot find its bundles must fail, not report zero problems.");
        }
        try (Stream<Path> files = Files.list(I18N_DIRECTORY)) {
            return files.filter(path -> path.getFileName().toString().startsWith("messages"))
                    .filter(path -> path.getFileName().toString().endsWith(".properties"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + I18N_DIRECTORY, e);
        }
    }

    private static Properties load(Path bundle) {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(bundle, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + bundle, e);
        }
        return properties;
    }
}
