package com.mycompanyname.zero.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.mycompanyname.zero.shared.web.EndpointPolicy;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    @DisplayName("Rule 4: every @Entity lives under a package declaring @ApplicationModule")
    void entitiesLiveUnderADeclaredModuleRoot() {
        check(ArchitectureRules.entitiesLiveUnderADeclaredModuleRoot());
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

    /**
     * Rule 6 is checked RAW, not frozen, and that is the point. It is at zero violations against
     * production code today, so it enforces from zero; freezing it would create a sixth store file
     * and bank whatever it happened to find on the first run, which is precisely the "green because
     * the debt was recorded, not because the code is clean" move this project keeps having to undo.
     * The frozen store stays at five files, all empty.
     */
    @Test
    @DisplayName("Rule 6: /api path literals stay in the module that serves them")
    void apiPathLiteralsStayInTheModuleThatServesThem() {
        ArchitectureRules.apiPathLiteralsStayInTheModuleThatServesThem().check(productionClasses);
    }

    /**
     * The first assertion of the claim {@code ArchitectureRules.INTENTIONALLY_ANONYMOUS} has always
     * made about itself: "SecurityConfig, not this list, is the source of truth; this list only
     * records the consequence." Until now nobody checked that. {@code anonymousExemptionsStillMatchRealHandlers}
     * below verifies each entry names an existing {@code @RestController} method — it never looks at
     * whether anything actually exposes it.
     *
     * <p>This binds the list to the handlers' own {@code @EndpointPolicy(ANONYMOUS)} claims, in both
     * directions. {@code SecurityPathBindingIT} then binds those claims to the live {@code permitAll}
     * grants, so the chain list -> claim -> grant -> live 401 is closed end to end. Kept in surefire
     * because it needs no context and this is the half people run most often.
     */
    @Test
    @DisplayName("Rule 5 guard: INTENTIONALLY_ANONYMOUS equals the set of @EndpointPolicy(ANONYMOUS) handlers")
    void theIntentionallyAnonymousSetEqualsTheAnnotatedSet() {
        Set<String> claimed = productionClasses.stream()
                .filter(clazz -> clazz.isAnnotatedWith(RestController.class))
                .flatMap(clazz -> clazz.getMethods().stream())
                .filter(method -> method.isAnnotatedWith(EndpointPolicy.class))
                .filter(method -> List.of(method.getAnnotationOfType(EndpointPolicy.class).value())
                        .contains(EndpointPolicy.Exposure.ANONYMOUS))
                .map(ArchitectureRules::key)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(claimed)
                .describedAs("no handler claims @EndpointPolicy(ANONYMOUS) at all — either the "
                        + "annotation was removed from every anonymous endpoint, or this test is "
                        + "reading the wrong classes. Both are red, never green")
                .isNotEmpty();

        assertThat(claimed)
                .describedAs("ArchitectureRules.INTENTIONALLY_ANONYMOUS and the handlers claiming "
                        + "@EndpointPolicy(ANONYMOUS) must be the SAME set. A handler in the list but "
                        + "not annotated is exempted from Rule 5 while nothing records why; a handler "
                        + "annotated but not in the list claims to be public while Rule 5 still "
                        + "demands @PreAuthorize on it")
                .containsExactlyInAnyOrderElementsOf(ArchitectureRules.INTENTIONALLY_ANONYMOUS);
    }

    /**
     * Proves the readability guard that Rule 6 now applies to registered policy holders, in both
     * directions: red on the form that was measured green, and clean on the real
     * {@code SecurityConfig}.
     *
     * <p><b>Why this test exists at all.</b> Rule 6 passing tells you nothing about this guard —
     * the guard's whole purpose is to fire on a form that does not exist in the codebase, so the
     * production run can only ever exercise its negative branch. The fixture is the positive branch,
     * and it is the auditor's finding verbatim: {@code .requestMatchers(PARTNER_PATHS).permitAll()}
     * put the entire tenancy admin surface at permitAll with surefire 137, failsafe 271 and BUILD
     * SUCCESS, because the group parsed and yielded zero literals.
     *
     * <p>The {@code hasAuthority} line in the fixture is deliberate. Keying on {@code permitAll}
     * would have closed one spelling and left the next one open; the guard reads the ARGUMENT, so
     * both are caught by the same assertion.
     *
     * <p><b>The fourth call is the second measured evasion and carries two guards at once.</b> It
     * writes the dot and the method name on separate lines — legal Java that the first version of
     * this scan, which asked for the contiguous token {@code ".requestMatchers"}, read as no call at
     * all. Reproduced against the pre-fix scan with the same form in the real {@code SecurityConfig}:
     * {@code /api/tenants/**} at {@code permitAll}, 9 tests, 0 failures, BUILD SUCCESS, UNREADABLE
     * count 0. Because that call also has to be counted by BOTH detectors in {@code JavaSources},
     * reverting either one alone makes them disagree and this test errors rather than passing.
     */
    @Test
    @DisplayName("Rule 6 guard: a requestMatchers argument that is not an inline literal is rejected")
    void unreadablePathDecisionsAreRejected() {
        String fixture = UnreadablePathPolicyFixture.class.getName();

        assertThat(JavaSources.requestMatcherArguments(fixture))
                .describedAs("the fixture's four requestMatchers calls were not read at all — the "
                        + "scan is broken, and a broken scan reports every file as clean. The fourth "
                        + "call is written with a newline between the dot and the method name, which "
                        + "is legal Java and was read as NO CALL by the contiguous-token scan this "
                        + "replaced; if only three arguments come back, the pattern match regressed")
                .hasSize(5)
                .filteredOn(argument -> !argument.isReadable())
                .describedAs("the three non-literal arguments in the fixture were classified as "
                        + "readable, which is the exact silent-green state being closed")
                .hasSize(3);

        assertThatThrownBy(() -> JavaSources.verifyRequestMatchersAreReadable(fixture))
                .describedAs("the guard accepted a String[] constant as a path decision")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PARTNER_PATHS");

        assertThat(JavaSources.verifyRequestMatchersAreReadable(
                "com.mycompanyname.zero.identity.auth.SecurityConfig"))
                .describedAs("SecurityConfig either has no requestMatchers call the scan can find — "
                        + "in which case the guard is vacuous — or writes one in a form it cannot "
                        + "read")
                .isGreaterThanOrEqualTo(3);
    }

    private static void check(ArchRule rule) {
        FreezingArchRule.freeze(rule).check(productionClasses);
    }
}
