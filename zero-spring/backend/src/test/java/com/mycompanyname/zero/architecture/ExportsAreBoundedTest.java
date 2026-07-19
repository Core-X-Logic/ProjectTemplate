package com.mycompanyname.zero.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.lang.annotation.Annotation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W5-3 — no export reaches the database without going through {@code BoundedExport}.
 *
 * <p><b>Why this test and not just the two fixes.</b> Both exports in this application were written
 * unbounded, independently, by people who had each other's code in front of them. Fixing the two
 * instances leaves the pattern intact: the third export is written the same way, ships, and is
 * discovered the same way the first two were — in production, on the one table that had grown. This
 * is the same argument {@link ArchitectureRules} makes for the five frozen rules, applied to the
 * bound instead of to the query shape.
 *
 * <p><b>What counts as an export.</b> Two independent signals, so that neither a rename nor a change
 * of convention alone can slip past:
 * <ul>
 *   <li>the handler says so — its name or its mapped path contains {@code export};</li>
 *   <li>the handler BUILDS A SPREADSHEET — it reaches Apache POI transitively. This is the
 *       shape-based half, and it is the one that survives a future export called
 *       {@code download()} at {@code /api/things/dump}.</li>
 * </ul>
 *
 * <p><b>What this still cannot see</b> (stated rather than papered over): an export that streams CSV
 * or JSON, touches no POI type and is named something else entirely is invisible to both signals.
 * The bound is not enforceable by construction — {@code BoundedExport} is a collaborator, not a
 * gate the query physically has to pass through — so this test raises the cost of missing it, it
 * does not make missing it impossible.
 */
class ExportsAreBoundedTest {

    private static final String BASE_PACKAGE = ArchitectureRules.BASE_PACKAGE;
    private static final String BOUNDED_EXPORT = BASE_PACKAGE + ".shared.BoundedExport";
    private static final String BOUNDED_EXPORT_METHOD = "fetch";
    private static final String SPREADSHEET_PACKAGE = "org.apache.poi";

    private static final List<Class<? extends Annotation>> REQUEST_MAPPINGS = List.of(
            RequestMapping.class, GetMapping.class, PostMapping.class,
            PutMapping.class, DeleteMapping.class, PatchMapping.class);

    /**
     * The exports that exist today. Named so that this test cannot go green by finding nothing —
     * the vacuity failure this repository has already shipped five times.
     */
    private static final Set<String> KNOWN_EXPORT_HANDLERS = Set.of(
            "UserController#export",
            "AuditLogController#export");

    private static JavaClasses productionClasses;

    @BeforeAll
    static void importProductionClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE_PACKAGE);
        assertThat(productionClasses)
                .describedAs("no production classes imported — check that target/classes is built")
                .isNotEmpty();
    }

    @Test
    @DisplayName("every export handler fetches through BoundedExport")
    void everyExportHandlerFetchesThroughTheSharedBound() {
        List<JavaMethod> handlers = exportHandlers();

        Set<String> found = handlers.stream()
                .map(ExportsAreBoundedTest::key)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(found)
                .describedAs("the detector found no export handler at all, so the assertion below "
                        + "would certify nothing; if an export was renamed, teach the detector "
                        + "rather than deleting this list")
                .containsAll(KNOWN_EXPORT_HANDLERS);

        List<String> unbounded = handlers.stream()
                .filter(handler -> !reaches(handler, call ->
                        call.getTargetOwner().getName().equals(BOUNDED_EXPORT)
                                && call.getName().equals(BOUNDED_EXPORT_METHOD)))
                .map(JavaMethod::getFullName)
                .toList();

        assertThat(unbounded)
                .describedAs("these export endpoints fetch their rows without BoundedExport, so the "
                        + "size of the response — and of the workbook held in heap while it is built "
                        + "— is whatever the table happens to have grown to. Route the fetch through "
                        + "BoundedExport.fetch(subject, sort, pageable -> ...), which asks the "
                        + "database for zero.export.max-rows + 1 rows and refuses if the extra one "
                        + "comes back. Do NOT truncate instead: a short export looks complete.")
                .isEmpty();
    }

    /**
     * Guards the shape-based half of the detector. If the POI signal silently stopped matching (a
     * repackaged dependency, a switch of spreadsheet library), the test above would keep passing on
     * the name signal alone and would quietly stop covering an export called anything else. This
     * asserts that the signal still fires on the code we know builds a workbook.
     */
    @Test
    @DisplayName("the spreadsheet signal still detects the exports that build a workbook")
    void theSpreadsheetSignalIsNotDead() {
        List<String> byShapeOnly = requestHandlers().stream()
                .filter(ExportsAreBoundedTest::buildsASpreadsheet)
                .map(ExportsAreBoundedTest::key)
                .toList();

        assertThat(byShapeOnly)
                .describedAs("no handler reaches Apache POI any more — the name-independent half of "
                        + "the export detector is dead, and a future export not called 'export' "
                        + "would go unchecked")
                .containsAll(KNOWN_EXPORT_HANDLERS);
    }

    // --- detection -------------------------------------------------------

    private static List<JavaMethod> exportHandlers() {
        return requestHandlers().stream()
                .filter(method -> saysItIsAnExport(method) || buildsASpreadsheet(method))
                .toList();
    }

    private static List<JavaMethod> requestHandlers() {
        List<JavaMethod> handlers = new ArrayList<>();
        for (JavaClass clazz : productionClasses) {
            if (!clazz.isAnnotatedWith(RestController.class)) {
                continue;
            }
            for (JavaMethod method : clazz.getMethods()) {
                if (method.getModifiers().contains(JavaModifier.PUBLIC)
                        && REQUEST_MAPPINGS.stream().anyMatch(method::isAnnotatedWith)) {
                    handlers.add(method);
                }
            }
        }
        return handlers;
    }

    private static boolean saysItIsAnExport(JavaMethod method) {
        if (method.getName().toLowerCase(Locale.ROOT).contains("export")) {
            return true;
        }
        return mappedPaths(method).stream()
                .anyMatch(path -> path.toLowerCase(Locale.ROOT).contains("export"));
    }

    private static List<String> mappedPaths(JavaMethod method) {
        List<String> paths = new ArrayList<>();
        for (Class<? extends Annotation> mapping : REQUEST_MAPPINGS) {
            if (!method.isAnnotatedWith(mapping)) {
                continue;
            }
            Annotation annotation = method.getAnnotationOfType(mapping);
            paths.addAll(List.of(pathsOf(annotation)));
        }
        return paths;
    }

    private static String[] pathsOf(Annotation annotation) {
        if (annotation instanceof GetMapping get) {
            return get.value();
        }
        if (annotation instanceof PostMapping post) {
            return post.value();
        }
        if (annotation instanceof PutMapping put) {
            return put.value();
        }
        if (annotation instanceof DeleteMapping delete) {
            return delete.value();
        }
        if (annotation instanceof PatchMapping patch) {
            return patch.value();
        }
        if (annotation instanceof RequestMapping request) {
            return request.value();
        }
        return new String[0];
    }

    private static boolean buildsASpreadsheet(JavaMethod method) {
        return reaches(method, call ->
                call.getTargetOwner().getPackageName().startsWith(SPREADSHEET_PACKAGE));
    }

    /**
     * Breadth-first walk of the call graph from one handler, following only calls whose target is
     * production code of this application, and stopping at the first call that satisfies
     * {@code hit}.
     *
     * <p>Overloads are all enqueued: the call site names a method, and a wrong guess between two
     * overloads would make this report an unbounded export as bounded. Following every candidate
     * errs toward the false-negative-free direction for the {@code hit} check and is cheap at this
     * code size.
     */
    private static boolean reaches(JavaMethod start, Predicate<JavaCall<?>> hit) {
        Deque<JavaMethod> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            JavaMethod current = queue.poll();
            if (!visited.add(current.getFullName())) {
                continue;
            }
            for (JavaCall<?> call : current.getCallsFromSelf()) {
                if (hit.test(call)) {
                    return true;
                }
                JavaClass owner = call.getTargetOwner();
                if (!owner.getPackageName().startsWith(BASE_PACKAGE)) {
                    continue;
                }
                owner.getMethods().stream()
                        .filter(candidate -> candidate.getName().equals(call.getName()))
                        .forEach(queue::add);
            }
        }
        return false;
    }

    private static String key(JavaMethod method) {
        return method.getOwner().getSimpleName() + "#" + method.getName();
    }
}
