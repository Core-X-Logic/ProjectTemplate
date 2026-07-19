package com.mycompanyname.zero.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Set;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.Filters;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

/**
 * The five architecture rules of this template, defined once and checked twice: frozen against
 * production code by {@link ArchitectureRulesTest}, and raw against deliberately broken fixtures
 * whenever the guards themselves need re-proving.
 *
 * <p><b>Why rules and not fixes.</b> A template's most dangerous defect is the COPIED PATTERN: the
 * next developer reads the existing code as the worked example and reproduces the mistake. This is
 * not hypothetical here — {@code @EntityGraph} together with {@code Pageable} had already spread
 * from {@code UserRepository} to {@code RoleRepository} before anyone noticed. Fixing the instances
 * does not stop the pattern; only a rule does.
 *
 * <p>Rules are deliberately kept free of {@code FreezingArchRule} so that they can be checked raw.
 * Freezing is applied at the call site.
 */
final class ArchitectureRules {

    private ArchitectureRules() {
    }

    static final String PRODUCTION_PACKAGE = "com.mycompanyname.zero..";

    /**
     * The application base package — Modulith module roots are its direct sub-packages, so rule 4's
     * walk up the package tree stops here. Derived from {@link #PRODUCTION_PACKAGE} so that
     * renaming the template (see {@code SETUP-NEW-PROJECT.md}) has one place to touch, not two.
     */
    static final String BASE_PACKAGE = PRODUCTION_PACKAGE.substring(
            0, PRODUCTION_PACKAGE.length() - "..".length());

    private static final String PAGEABLE = "org.springframework.data.domain.Pageable";

    private static final List<Class<? extends Annotation>> REQUEST_MAPPINGS = List.of(
            RequestMapping.class, GetMapping.class, PostMapping.class,
            PutMapping.class, DeleteMapping.class, PatchMapping.class);

    /**
     * Endpoints that must stay reachable without a credential. Each entry mirrors a
     * {@code permitAll()} matcher in {@code SecurityConfig} — that file, not this list, is the
     * source of truth; this list only records the consequence.
     *
     * <p>Named one by one on purpose. A pattern such as "anything under auth" would silently
     * whitelist the next endpoint someone drops into {@code AuthController}, which is exactly the
     * untested gap this rule exists to close. {@link ArchitectureRulesTest} additionally fails if an
     * entry here stops matching a real handler, so the list cannot rot into a permanent hole.
     */
    static final Set<String> INTENTIONALLY_ANONYMOUS = Set.of(
            // SecurityConfig: "/api/auth/login", "/api/auth/refresh"
            "AuthController#login",
            "AuthController#refresh",
            // SecurityConfig: "/api/account/forgot-password", "/reset-password", "/confirm-email"
            "AccountController#forgotPassword",
            "AccountController#resetPassword",
            "AccountController#confirmEmail",
            // SecurityConfig: "/api/localization/**" — the login screen needs its dictionary before
            // it can offer a login form at all.
            "LocalizationController#languages",
            "LocalizationController#dictionary");

    // ---------------------------------------------------------------------------------------
    // Rule 1 — @EntityGraph must not be combined with Pageable
    // ---------------------------------------------------------------------------------------

    /**
     * Hibernate cannot push a collection fetch join and a {@code LIMIT} into the same statement. It
     * gives up quietly, logs {@code HHH90003004}, reads EVERY matching row and paginates the result
     * in memory. With five demo rows this is invisible; with fifty thousand it is a heap cliff on a
     * page the UI calls on every screen.
     */
    static ArchRule entityGraphIsNeverCombinedWithPageable() {
        return methods()
                .that().areAnnotatedWith(EntityGraph.class)
                .should(new ArchCondition<JavaMethod>("not take a Pageable parameter") {
                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        boolean paginated = method.getRawParameterTypes().stream()
                                .anyMatch(type -> type.isAssignableTo(PAGEABLE));
                        if (paginated) {
                            events.add(SimpleConditionEvent.violated(method,
                                    "@EntityGraph combined with Pageable in " + method.getFullName()
                                            + " — Hibernate cannot fetch a collection and paginate in "
                                            + "one statement (HHH90003004); it loads every row and "
                                            + "slices in memory. Split the query: page the ids, then "
                                            + "fetch the associations for that page."));
                        }
                    }
                })
                .as("Rule 1: @EntityGraph and Pageable are never used on the same repository method")
                .because("in-memory pagination is invisible at demo scale and fatal at production scale");
    }

    // ---------------------------------------------------------------------------------------
    // Rule 2 — every tenant-scoped entity carries a Hibernate @Filter
    // ---------------------------------------------------------------------------------------

    /**
     * An entity with a {@code tenant_id} column and no {@code @Filter} leaks across tenants
     * SILENTLY: every positive test still passes, because tenant A's own rows are returned
     * correctly — tenant B's rows simply come along.
     *
     * <p>{@code saas} is a deliberate exception (ADR-0015). SaaS entities are host-scoped by design:
     * editions, subscriptions and tenant features are administered from the host side, so a tenant
     * filter would break the feature rather than protect it. The compensating control is that every
     * SaaS endpoint is guarded by a {@code Side.HOST} permission AND carries a negative
     * authorization test ({@code SaasAuthorizationIT}). The exemption is therefore paid for, not
     * granted.
     */
    static ArchRule tenantScopedEntitiesDeclareATenantFilter() {
        return classes()
                .that().areAnnotatedWith(Entity.class)
                .and().resideOutsideOfPackage("..saas..")
                .and(HAVE_A_TENANT_ID_COLUMN)
                .should(new ArchCondition<JavaClass>("declare a Hibernate @Filter") {
                    @Override
                    public void check(JavaClass entity, ConditionEvents events) {
                        // Repeating @Filter makes javac wrap the annotations in the @Filters
                        // container, so the single-annotation check alone would report false
                        // violations for the entities that got this RIGHT.
                        boolean filtered = entity.isAnnotatedWith(Filter.class)
                                || entity.isAnnotatedWith(Filters.class);
                        if (!filtered) {
                            events.add(SimpleConditionEvent.violated(entity,
                                    "Tenant-scoped entity " + entity.getName() + " has a tenant_id "
                                            + "column but no Hibernate @Filter — cross-tenant reads "
                                            + "will succeed silently and no positive test will notice. "
                                            + "Add @Filter(name = \"tenantFilter\", condition = "
                                            + "\"tenant_id = :tenantId\")."));
                        }
                    }
                })
                .as("Rule 2: entities with a tenant_id column declare a Hibernate tenant @Filter "
                        + "(saas excluded per ADR-0015 — host-scoped by design, paid for with a "
                        + "host permission plus a negative authorization test on every endpoint)")
                .because("a missing tenant filter is a silent cross-tenant leak, not a visible failure");
    }

    private static final DescribedPredicate<JavaClass> HAVE_A_TENANT_ID_COLUMN =
            new DescribedPredicate<>("have a tenant_id column") {
                @Override
                public boolean test(JavaClass entity) {
                    return entity.getAllFields().stream().anyMatch(field ->
                            field.getName().equals("tenantId")
                                    || (field.isAnnotatedWith(Column.class)
                                    && "tenant_id".equals(
                                    field.getAnnotationOfType(Column.class).name())));
                }
            };

    // ---------------------------------------------------------------------------------------
    // Rule 3 — @PreAuthorize references a permission constant, never a raw string
    // ---------------------------------------------------------------------------------------

    /**
     * {@code @PreAuthorize("hasAuthority('users.raed')")} compiles, deploys, and returns 403 for
     * ever. Nothing catches it: the typo is inside a string, the endpoint is "secured", and a test
     * that asserts "a user without the permission gets 403" passes for the wrong reason.
     * Referencing {@code AppPermissions.USERS_READ} turns that class of bug into a compile error.
     *
     * <p>Checked on SOURCE, and it has to be: {@code public static final String} is a compile-time
     * constant, so {@code "hasAuthority('" + AppPermissions.USERS_READ + "')"} and
     * {@code "hasAuthority('users.read')"} produce identical bytecode. See {@link JavaSources}.
     */
    static ArchRule preAuthorizeUsesPermissionConstants() {
        return classes()
                .that().resideInAPackage(PRODUCTION_PACKAGE)
                .and(ARE_TOP_LEVEL)
                .should(new ArchCondition<JavaClass>(
                        "reference AppPermissions/SaasPermissions constants in @PreAuthorize") {
                    @Override
                    public void check(JavaClass clazz, ConditionEvents events) {
                        for (JavaSources.PreAuthorizeUsage usage
                                : JavaSources.preAuthorizeUsages(clazz.getName())) {
                            for (String literal : usage.rawPermissionLiterals()) {
                                events.add(SimpleConditionEvent.violated(clazz,
                                        "Raw permission literal '" + literal + "' in @PreAuthorize on "
                                                + clazz.getName() + "." + usage.owner()
                                                + " — use the AppPermissions/SaasPermissions constant "
                                                + "so a typo becomes a compile error instead of a "
                                                + "permanent 403."));
                            }
                        }
                    }
                })
                .as("Rule 3: @PreAuthorize expressions reference permission constants, not raw strings")
                .because("a mistyped permission string compiles, passes tests, and 403s for ever");
    }

    // ---------------------------------------------------------------------------------------
    // Rule 4 — every @Entity lives under a declared module root
    // ---------------------------------------------------------------------------------------

    /**
     * Measured, not assumed: {@code ModularityTests.verify()} passes a module that writes NO
     * {@code @ApplicationModule}, because {@code allowedDependencies} defaults to empty and empty
     * means "unconstrained", not "nothing allowed". The Modulith boundary test therefore certifies
     * most confidently precisely the packages that have declared no boundary. This rule requires
     * every entity — the state a leak actually escapes through — to sit under a package that made
     * the declaration, so that Modulith's verdict on it means something.
     *
     * <p><b>Why the previous formulation was wrong, and how that was found.</b> Until Wave 5 this
     * rule read "every {@code @Entity} resides in a package that ships a {@code package-info.java}",
     * checking the entity's OWN package for the mere EXISTENCE of the file. It froze 12 violations.
     * Every one of them was measured to be a mistake:
     *
     * <ul>
     *   <li><b>It never looked where its own justification applied.</b> All 12 frozen entities sat
     *       in internal sub-packages of the five modules that DO declare {@code allowedDependencies}
     *       ({@code audit}, {@code identity}, {@code notification}, {@code saas}, {@code settings}).
     *       Not one was an undeclared module. The packages the justification actually described —
     *       {@code config}, {@code seed}, {@code shared} — contain no entity, so the rule never
     *       looked at them at all.
     *   <li><b>Its sign was inverted.</b> Three of the five entities it PASSED live in
     *       {@code identity.domain}, whose {@code package-info.java} carries {@code @NamedInterface}
     *       — an annotation that OPENS the package to other modules. The rule scored a package for
     *       loosening its boundary and penalised the properly encapsulated ones.
     *   <li><b>Its only available fix was a no-op.</b> Because it tested file existence and not
     *       content, twelve empty {@code package-info.java} files would have turned it green while
     *       changing Modulith's semantics by exactly nothing. In a template that is worse than no
     *       rule: the silencing pattern is inherited by every clone.
     * </ul>
     *
     * <p>The invariant below is the one the justification always described. It walks UP from the
     * entity to its module root and reads the declaration rather than counting the file. Both
     * {@code allowedDependencies} and {@code Type.OPEN} are accepted: waiving a boundary on purpose
     * is a decision, and this rule is about decisions being made, not about which one was made.
     */
    static ArchRule entitiesLiveUnderADeclaredModuleRoot() {
        return classes()
                .that().areAnnotatedWith(Entity.class)
                .should(new ArchCondition<JavaClass>(
                        "reside under a package declaring @ApplicationModule") {

                    private int entitiesSeen;

                    @Override
                    public void check(JavaClass entity, ConditionEvents events) {
                        entitiesSeen++;
                        if (JavaSources.declaringModuleRoot(entity.getName(), BASE_PACKAGE)
                                .isEmpty()) {
                            events.add(SimpleConditionEvent.violated(entity,
                                    "Entity " + entity.getName() + " lives in package "
                                            + entity.getPackageName() + ", and no package from "
                                            + "there up to " + BASE_PACKAGE + " declares "
                                            + "@ApplicationModule — Modulith constrains only what a "
                                            + "module root claims, so this entity's boundary is "
                                            + "unchecked rather than checked and clean. Declare the "
                                            + "module root (allowedDependencies, or Type.OPEN if the "
                                            + "waiver is deliberate); do NOT add an empty "
                                            + "package-info.java, which silences this rule without "
                                            + "changing anything Modulith enforces."));
                        }
                    }

                    /**
                     * Vacuity guard. A rule that matched no class would report "no violations" and
                     * go green having certified nothing — the failure mode this whole test class
                     * exists to prevent. If the entity scan ever comes back empty (renamed base
                     * package, unbuilt classes, a changed import option), that is a broken guard,
                     * not a clean codebase.
                     *
                     * <p><b>This is the second of two layers, and it was measured.</b> Forcing the
                     * matched set empty fails the build on ArchUnit's own {@code failOnEmptyShould},
                     * which pre-empts this method — it never runs. But that built-in is a GLOBAL
                     * default that is not pinned in {@code archunit.properties}: setting
                     * {@code archRule.failOnEmptyShould=false} to quieten some unrelated rule would
                     * switch it off everywhere at once. Re-running the same empty-set probe with the
                     * built-in disabled produces the message below instead, so this guard is the
                     * layer that survives that change rather than dead code kept for appearances.
                     */
                    @Override
                    public void finish(ConditionEvents events) {
                        if (entitiesSeen == 0) {
                            events.add(SimpleConditionEvent.violated(this,
                                    "Rule 4 examined ZERO @Entity classes. It cannot have verified "
                                            + "anything; a green result here would be vacuous. Check "
                                            + "that target/classes is built and that "
                                            + BASE_PACKAGE + " is still the base package."));
                        }
                    }
                })
                .as("Rule 4: every @Entity resides under a package that declares @ApplicationModule "
                        + "(allowedDependencies or Type.OPEN — both are decisions; absence is not)")
                .because("Modulith only constrains packages that a module root claims; an entity "
                        + "under no declared root is unchecked rather than checked and clean");
    }

    // ---------------------------------------------------------------------------------------
    // Rule 5 — every REST handler is authorized
    // ---------------------------------------------------------------------------------------

    /**
     * A handler with no {@code @PreAuthorize} falls through to whatever the filter chain happens to
     * say, which today is {@code anyRequest().authenticated()} — any logged-in user of any tenant.
     * The endpoint looks guarded in review and is not.
     */
    static ArchRule restHandlersAreAuthorized() {
        return methods()
                .that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
                .and().arePublic()
                .and(ARE_REQUEST_HANDLERS)
                .and(DescribedPredicate.not(ARE_INTENTIONALLY_ANONYMOUS))
                .should(new ArchCondition<JavaMethod>("be guarded by @PreAuthorize") {
                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        boolean guarded = method.isAnnotatedWith(PreAuthorize.class)
                                || method.getOwner().isAnnotatedWith(PreAuthorize.class);
                        if (!guarded) {
                            events.add(SimpleConditionEvent.violated(method,
                                    "Unauthorized REST handler " + method.getFullName()
                                            + " — no @PreAuthorize on the method or its controller, so "
                                            + "the endpoint is open to every authenticated caller of "
                                            + "every tenant. Add @PreAuthorize, or add it to "
                                            + "ArchitectureRules.INTENTIONALLY_ANONYMOUS with the "
                                            + "matching SecurityConfig permitAll() entry."));
                        }
                    }
                })
                .as("Rule 5: @RestController request handlers carry @PreAuthorize, except the "
                        + "endpoints named in INTENTIONALLY_ANONYMOUS")
                .because("an unguarded handler is reachable by every authenticated user of every tenant");
    }

    static final DescribedPredicate<JavaMethod> ARE_REQUEST_HANDLERS =
            new DescribedPredicate<>("are request handlers") {
                @Override
                public boolean test(JavaMethod method) {
                    return REQUEST_MAPPINGS.stream().anyMatch(method::isAnnotatedWith);
                }
            };

    private static final DescribedPredicate<JavaMethod> ARE_INTENTIONALLY_ANONYMOUS =
            new DescribedPredicate<>("are intentionally anonymous") {
                @Override
                public boolean test(JavaMethod method) {
                    return INTENTIONALLY_ANONYMOUS.contains(key(method));
                }
            };

    /** {@code AuthController#login} — the key format used by {@link #INTENTIONALLY_ANONYMOUS}. */
    static String key(JavaMethod method) {
        return method.getOwner().getSimpleName() + "#" + method.getName();
    }

    private static final DescribedPredicate<JavaClass> ARE_TOP_LEVEL =
            new DescribedPredicate<>("are top level classes") {
                @Override
                public boolean test(JavaClass clazz) {
                    // Nested classes share their outer class' source file; scanning both would
                    // report the same violation twice.
                    return clazz.isTopLevelClass();
                }
            };
}
