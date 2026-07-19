package com.mycompanyname.zero.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the five architecture rules against production code.
 *
 * <p><b>Frozen, not enforced-from-zero.</b> Existing code violates some of these rules today.
 * Blocking the build on all of it would leave one choice — delete the rules — so instead every
 * violation that exists right now is recorded in {@code archunit_store/} and tolerated, while any
 * NEW violation fails the build. The frozen list is a debt ledger: when a violation is fixed
 * ArchUnit removes it from the store on the next run, so the allowance shrinks automatically and
 * can never grow back. Commit the store with the fix and the ratchet tightens for everyone.
 *
 * <p>Nothing here needs a database or a Spring context; these are plain unit tests and run in
 * surefire, not failsafe.
 */
class ArchitectureRulesTest {

    private static JavaClasses productionClasses;

    @BeforeAll
    static void importProductionClasses() {
        JavaSources.verifySourceRootsPresent();
        productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.mycompanyname.zero");
        assertThat(productionClasses)
                .describedAs("no production classes imported — the rules below would all pass "
                        + "vacuously; check that target/classes is built")
                .isNotEmpty();
    }

    @Test
    @DisplayName("Rule 1: @EntityGraph is never combined with Pageable")
    void entityGraphIsNeverCombinedWithPageable() {
        check(ArchitectureRules.entityGraphIsNeverCombinedWithPageable());
    }

    @Test
    @DisplayName("Rule 2: tenant-scoped entities declare a Hibernate @Filter")
    void tenantScopedEntitiesDeclareATenantFilter() {
        check(ArchitectureRules.tenantScopedEntitiesDeclareATenantFilter());
    }

    @Test
    @DisplayName("Rule 3: @PreAuthorize uses permission constants, not raw strings")
    void preAuthorizeUsesPermissionConstants() {
        check(ArchitectureRules.preAuthorizeUsesPermissionConstants());
    }

    @Test
    @DisplayName("Rule 4: every @Entity lives in a package with a package-info.java")
    void entitiesLiveInDeclaredPackages() {
        check(ArchitectureRules.entitiesLiveInDeclaredPackages());
    }

    @Test
    @DisplayName("Rule 5: REST handlers are guarded by @PreAuthorize")
    void restHandlersAreAuthorized() {
        check(ArchitectureRules.restHandlersAreAuthorized());
    }

    /**
     * Keeps rule 5's exemption list honest. If a named endpoint is renamed or deleted, the entry
     * stops matching anything and quietly becomes a permanent hole that no test covers — the exact
     * failure mode the rule was written to prevent. This fails the build instead.
     */
    @Test
    @DisplayName("Rule 5 guard: every INTENTIONALLY_ANONYMOUS entry still names a real handler")
    void anonymousExemptionsStillMatchRealHandlers() {
        Set<String> handlers = productionClasses.stream()
                .filter(clazz -> clazz.isAnnotatedWith(RestController.class))
                .flatMap(clazz -> clazz.getMethods().stream())
                .filter(method -> method.getModifiers().contains(JavaModifier.PUBLIC))
                .filter(ArchitectureRules.ARE_REQUEST_HANDLERS)
                .map(ArchitectureRules::key)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(handlers)
                .describedAs("stale entries in ArchitectureRules.INTENTIONALLY_ANONYMOUS: they no "
                        + "longer name any @RestController method, so they exempt nothing and hide "
                        + "nothing — remove them, or fix the name they were meant to point at")
                .containsAll(ArchitectureRules.INTENTIONALLY_ANONYMOUS);
    }

    private static void check(ArchRule rule) {
        FreezingArchRule.freeze(rule).check(productionClasses);
    }
}
