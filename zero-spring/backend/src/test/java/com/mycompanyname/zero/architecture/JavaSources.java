package com.mycompanyname.zero.architecture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the {@code .java} sources that back the classes ArchUnit is analysing.
 *
 * <p><b>Why source and not bytecode.</b> Two of the five architecture rules cannot be expressed on
 * bytecode at all:
 *
 * <ul>
 *   <li>{@code @PreAuthorize("hasAuthority('" + AppPermissions.USERS_READ + "')")} and
 *       {@code @PreAuthorize("hasAuthority('users.read')")} compile to the <em>byte-identical</em>
 *       annotation value. {@code public static final String} is a compile-time constant, so javac
 *       folds the concatenation away before the class file is written. A bytecode rule therefore
 *       physically cannot tell the good form from the bad one — only the source can.
 *   <li>A {@code package-info.java} that carries no annotation produces <em>no</em>
 *       {@code package-info.class}. Asking the classpath whether a package is declared would answer
 *       "no" for perfectly well documented packages and silently pass for undeclared ones.
 * </ul>
 *
 * <p><b>Fails loud, never silent.</b> If the source root is missing, or a class under analysis has
 * no source file, this class throws instead of reporting "no violations". A guard that cannot see
 * its subject must go red, not green — the whole point of the rules it serves.
 */
final class JavaSources {

    /**
     * Surefire/Failsafe run with {@code ${basedir}} as the working directory, so these resolve
     * against {@code zero-spring/backend}. Both roots are searched: production classes live in the
     * first, and architecture-rule fixtures (used to prove a rule actually goes red) in the second.
     * Scanning the test root costs nothing at production-check time because the ArchUnit import
     * excludes test classes, so no test class is ever looked up.
     */
    private static final List<Path> SOURCE_ROOTS =
            List.of(Path.of("src", "main", "java"), Path.of("src", "test", "java"));

    /**
     * A single-quoted plain token inside a SpEL expression — {@code 'users.read'}. The constant form
     * reads {@code '" + AppPermissions.USERS_READ + "'} in source, whose inner text contains
     * {@code "} and {@code +} and therefore does not match. That difference is the entire test.
     */
    private static final Pattern QUOTED_PERMISSION = Pattern.compile("'([A-Za-z][A-Za-z0-9_.]*)'");

    /** Start of a {@code @PreAuthorize} annotation; the full text is read by balancing parens. */
    private static final Pattern PRE_AUTHORIZE_START = Pattern.compile("@PreAuthorize\\s*\\(");

    /** {@code public Page<UserDto> list(...)} -> captures {@code list}. */
    private static final Pattern METHOD_DECLARATION =
            Pattern.compile("^\\s*(?:public|protected|private)\\s+[^=;]*?\\b(\\w+)\\s*\\(");

    /** A type declaration ends the search for the method a class-level annotation belongs to. */
    private static final Pattern TYPE_DECLARATION =
            Pattern.compile("\\b(?:class|interface|record|enum)\\s+\\w+");

    private JavaSources() {
    }

    /** One {@code @PreAuthorize} occurrence: the element it guards and its verbatim source text. */
    record PreAuthorizeUsage(String owner, String expression) {

        /** Raw permission literals in the expression; empty when constants were used. */
        List<String> rawPermissionLiterals() {
            List<String> found = new ArrayList<>();
            Matcher matcher = QUOTED_PERMISSION.matcher(expression);
            while (matcher.find()) {
                found.add(matcher.group(1));
            }
            return found;
        }
    }

    /** True when the package that {@code className} lives in ships a {@code package-info.java}. */
    static boolean hasPackageInfo(String className) {
        String packagePath = packageOf(className).replace('.', '/');
        return SOURCE_ROOTS.stream()
                .map(root -> root.resolve(packagePath).resolve("package-info.java"))
                .anyMatch(Files::isRegularFile);
    }

    /** Every {@code @PreAuthorize} written in the source of {@code className}, in file order. */
    static List<PreAuthorizeUsage> preAuthorizeUsages(String className) {
        String source = read(className);
        if (!source.contains("@PreAuthorize")) {
            return List.of();
        }
        List<String> lines = source.lines().toList();
        // A method may be preceded by several annotations; keying by name keeps the last one, which
        // is fine because a method can carry only one @PreAuthorize.
        Map<String, PreAuthorizeUsage> byOwner = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            if (!PRE_AUTHORIZE_START.matcher(lines.get(i)).find()) {
                continue;
            }
            int end = endOfAnnotation(lines, i);
            String expression = String.join(" ", lines.subList(i, end + 1)).trim();
            String owner = ownerOf(lines, end + 1, className);
            byOwner.put(owner, new PreAuthorizeUsage(owner, expression));
            i = end;
        }
        return List.copyOf(byOwner.values());
    }

    /** Index of the last line of the annotation that starts at {@code start}, by balancing parens. */
    private static int endOfAnnotation(List<String> lines, int start) {
        int depth = 0;
        for (int i = start; i < lines.size(); i++) {
            for (char c : lines.get(i).toCharArray()) {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                }
            }
            if (depth <= 0) {
                return i;
            }
        }
        return lines.size() - 1;
    }

    /**
     * The declaration an annotation guards: the next method declaration, or the simple class name
     * when the annotation sits on the type itself.
     */
    private static String ownerOf(List<String> lines, int from, String className) {
        String simpleName = className.substring(className.lastIndexOf('.') + 1);
        for (int i = from; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("@") || trimmed.startsWith("//")
                    || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                continue;
            }
            if (TYPE_DECLARATION.matcher(line).find()) {
                return simpleName;
            }
            Matcher method = METHOD_DECLARATION.matcher(line);
            return method.find() ? method.group(1) + "(..)" : simpleName;
        }
        return simpleName;
    }

    private static String read(String className) {
        // Nested and anonymous classes (Foo$Bar, Foo$1) live in the top-level class' source file.
        String topLevel = className.contains("$")
                ? className.substring(0, className.indexOf('$'))
                : className;
        String relative = topLevel.replace('.', '/') + ".java";
        for (Path root : SOURCE_ROOTS) {
            Path candidate = root.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                try {
                    return Files.readString(candidate, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException("Cannot read " + candidate, e);
                }
            }
        }
        throw new IllegalStateException(
                "No .java source for " + className + " under " + SOURCE_ROOTS + " (working dir: "
                        + Path.of("").toAbsolutePath() + "). The architecture rules read source on "
                        + "purpose; a missing source must fail the build, not pass it silently.");
    }

    private static String packageOf(String className) {
        int lastDot = className.lastIndexOf('.');
        return lastDot < 0 ? "" : className.substring(0, lastDot);
    }

    /** Verifies the source tree is where the rules expect it. Called once, before any rule runs. */
    static void verifySourceRootsPresent() {
        Path main = SOURCE_ROOTS.get(0);
        if (!Files.isDirectory(main)) {
            throw new IllegalStateException(
                    "Source root " + main.toAbsolutePath() + " not found (working dir: "
                            + Path.of("").toAbsolutePath() + "). Two architecture rules read .java "
                            + "source; without it they would report zero violations and the build "
                            + "would go green for the wrong reason.");
        }
    }
}
