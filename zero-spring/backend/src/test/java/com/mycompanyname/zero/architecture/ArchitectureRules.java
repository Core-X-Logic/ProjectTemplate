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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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
 * The architecture rules of this template, defined once and checked twice: against production code
 * by {@link ArchitectureRulesTest}, and raw against deliberately broken fixtures whenever the guards
 * themselves need re-proving.
 *
 * <p>Rules 1-5 are FROZEN at the call site (five store files, all at zero violations). Rule 6 is
 * checked RAW: it is at zero today, and freezing a new rule would bank whatever it happened to find
 * into a store this project requires to stay empty. Their {@code as}/{@code because} text is the
 * frozen store's key, so rewording rules 1-5 orphans their violations and silently re-freezes from
 * zero — do not touch it. Appending a rule is safe.
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
            // SecurityConfig: "/api/auth/two-factor/verify" — the pre-login second-factor step. The
            // caller holds only an opaque challenge (no session), so it is anonymous like login; the
            // handler claims @EndpointPolicy(ANONYMOUS) and is throttled via zero.ratelimit.paths.
            "AuthController#verifyTwoFactor",
            // SecurityConfig: "/api/account/forgot-password", "/reset-password", "/confirm-email"
            "AccountController#forgotPassword",
            "AccountController#resetPassword",
            "AccountController#confirmEmail",
            // SecurityConfig: "/api/localization/**" — the login screen needs its dictionary before
            // it can offer a login form at all.
            "LocalizationController#languages",
            "LocalizationController#dictionary",
            // SecurityConfig: "/api/billing/webhook/stripe" — Stripe calls it and holds no
            // credential of ours; authentication IS the Stripe-Signature header, verified in the
            // handler before anything is stored. On CONTRACT-wave5 §2.4: that contract forbade
            // additions to this list because the six handlers then under discussion were NOT
            // permitAll — listing them would have documented an exposure that did not exist. This
            // endpoint IS permitAll, which is exactly the consequence this list exists to record.
            "BillingWebhookController#stripeWebhook",
            // SecurityConfig: "/api/billing/webhook/paytr" (P2'-A) — same shape, second provider:
            // PayTR calls it server-to-server and authentication IS the HMAC `hash` field inside
            // the form body, verified offline in PayTRBillingProvider before anything is stored.
            // Named individually, exact path — the per-provider webhook routes are deliberately
            // NOT collapsed into a pattern here or in SecurityConfig.
            "BillingWebhookController#paytrWebhook",
            // SecurityConfig: "/api/billing/webhook/iyzico" (P2'-B) — third provider, same shape:
            // authentication IS the X-IYZ-SIGNATURE-V3 header, verified offline in
            // IyzicoBillingProvider before anything is stored; the payload is additionally never
            // trusted for activation (retrieve-authoritative, BillingConfirmationService).
            "BillingWebhookController#iyzicoWebhook",
            // SecurityConfig: "/api/billing/callback/iyzico" (P2'-B) — the buyer's BROWSER, not
            // the provider's server: no credential exists to demand. Carries no proof at all,
            // which is exactly why the handler treats it as a trigger and activates only on the
            // provider's own retrieve answer, never on the request.
            "BillingCallbackController#iyzicoCallback");

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

    // ---------------------------------------------------------------------------------------
    // Rule 6 — an /api path literal stays in the module that serves it
    // ---------------------------------------------------------------------------------------

    /**
     * The two classes allowed to name another module's URL surface, because naming it is their
     * entire job: they are where an access decision is made, and keeping the decisions centralised
     * is what lets one reviewer read the whole effective grant in two files.
     *
     * <p>They are not unchecked — they are checked by a different, stronger mechanism.
     * {@code SecurityPathBindingIT} and {@code SubscriptionExemptPathBindingIT} assert their literals
     * against the LIVE handler mapping in both directions. This list is the registration that makes
     * a third policy holder a visible, required diff instead of something a reviewer has to notice.
     *
     * <p><b>The registration is conditional, and it was not always.</b> Being on this list buys
     * exemption from the ownership check only; it obliges the holder to keep its path decisions in a
     * form those cross-checks can actually READ — see
     * {@link #apiPathLiteralsStayInTheModuleThatServesThem()}. Until that obligation existed the
     * exemption was a blanket skip, and a grant spelled as a {@code String[]} constant was invisible
     * to the ownership rule (skipped as a holder) AND to the source parser (no literal inside the
     * matcher group) while every gate stayed green.
     */
    static final Set<String> PATH_POLICY_HOLDERS = Set.of(
            "com.mycompanyname.zero.identity.auth.SecurityConfig",
            "com.mycompanyname.zero.saas.subscription.SubscriptionAccessCheck");

    /**
     * Literals that name the API namespace itself rather than any route inside it: the interceptor
     * registration ({@code AuditWebConfig}), the interceptor's own namespace guard
     * ({@code AuditLogInterceptor}), the application-wide body bound ({@code RequestLimitProperties})
     * and the startup validator's filter. No module owns {@code /api}, so no module can be told it is
     * trespassing by writing it, and demanding an owner for these is how a rule goes red against
     * correct code.
     */
    private static final Set<String> API_NAMESPACE_LITERALS = Set.of("/api", "/api/", "/api/**");

    /**
     * An access decision bound to a URL string is invisible to every tool this project owns. The
     * compiler sees a {@code String}; Modulith compares package references and a URL is not one;
     * bytecode ArchUnit cannot tell a path constant from a raw literal. Measured with {@code javap}:
     * a {@code static final String} constant folds to a bare {@code ldc} with NO reference to the
     * class that declared it, so hoisting these paths into a shared {@code ApiPaths} class would emit
     * byte-identical output and make exactly nothing visible. Only the source can see it, which is
     * why this rule reads source — the same reason Rule 3 does.
     *
     * <p>The invariant is ownership, not existence: {@code /api/localization/**} written inside
     * {@code identity} is an undeclared dependency on {@code localization}'s published URL surface,
     * and the day localization renames its prefix the matcher silently stops matching while every
     * gate in the build stays green. This rule confines such literals to the module that serves them
     * plus the two registered policy holders, so the SEVENTH place someone parks a path decision goes
     * red rather than joining the pattern. It also fails a literal that no {@code @RestController}
     * serves at all, which catches the rename statically.
     *
     * <p>The registered policy holders are exempt from the ownership half and are held to the second
     * half instead: every {@code requestMatchers} argument they write must be a bare string literal.
     * That is not a style preference. The ownership half SKIPS a holder, and the source parser behind
     * {@code SecurityPathBindingIT} sees only literals — so a grant spelled any other way falls
     * through both at once. Measured: {@code .requestMatchers(PARTNER_PATHS).permitAll()} put the
     * whole tenancy admin surface at {@code permitAll} with surefire 137, failsafe 271, BUILD SUCCESS
     * and both parser canaries intact. The invariant is therefore about the FORM of the decision, not
     * about the verb after it — {@code hasAuthority} is read the same way, or the next spelling is
     * simply the next hole.
     *
     * <p><b>Which layer is load-bearing.</b> Not this one. Everything above is a property of SOURCE
     * TEXT, and source text of a fluent DSL cannot be made airtight — four evasions were measured
     * against it, each with the grant live on the running chain and this rule green: a backslash-u
     * escaped dot (javac decodes it before lexing, so the token this rule looks for is not in the
     * file), a readable literal in a helper class called from the chain builder, a second
     * {@code SecurityFilterChain} bean with {@code securityMatcher}, and
     * {@code WebSecurityCustomizer.ignoring()}. The last three contain no {@code requestMatchers}
     * token at all. {@code FilterChainReachabilityIT} asks the RUNNING FILTER CHAIN which routes it
     * serves without a credential and caught all four; it is the guarantee. This rule is kept as
     * defence in depth and as the fast half of the loop — it runs without a servlet container and
     * names the file and line, which a wire probe cannot. A green result here means the text is
     * clean, never that the chain is.
     *
     * <p><b>Not frozen, on purpose.</b> It is at zero today, so it is checked raw. Freezing a new
     * rule banks whatever it happens to find into a store that this project requires to stay empty,
     * and a ratchet that starts non-zero is a ratchet nobody trusts.
     */
    static ArchRule apiPathLiteralsStayInTheModuleThatServesThem() {
        return classes()
                .that().resideInAPackage(PRODUCTION_PACKAGE)
                .and(ARE_TOP_LEVEL)
                .should(new ApiPathOwnershipCondition())
                .as("Rule 6: an /api path literal appears only in the module that serves that route, "
                        + "or in a registered access-policy holder, and every requestMatchers "
                        + "argument anywhere in production source is an inline string literal")
                .because("a path string binds one module to another module's URL surface where no "
                        + "compiler, no Modulith check and no bytecode rule can see the coupling — "
                        + "and a path decision written in a form the cross-checks cannot read is "
                        + "exempted by the first half and invisible to the second");
    }

    /**
     * Derives which module serves which route prefix from the controllers themselves, then requires
     * every {@code /api} literal in production source to belong to its own module.
     */
    private static final class ApiPathOwnershipCondition extends ArchCondition<JavaClass> {

        /** {@code /api/audit-logs} -> {@code audit}. Two segments: the module's route prefix. */
        private final Map<String, String> ownerByPrefix = new TreeMap<>();

        private final Set<String> policyHoldersSeen = new TreeSet<>();
        private int matcherArgumentsSeen;
        private int classesSeen;
        private int literalsSeen;
        private int literalsAttributed;

        ApiPathOwnershipCondition() {
            super("keep /api path literals inside the module that serves them");
        }

        /**
         * The ownership map is built from the mapping annotations of every imported controller, NOT
         * from a hand-written registry. Class-level {@code @RequestMapping} is concatenated with the
         * method-level value exactly as Spring does, and an ABSENT class-level mapping contributes
         * the empty string rather than skipping the class — {@code AuditLogController} and
         * {@code FeatureController} carry no class-level mapping at all, so a rule that read class
         * annotations alone would silently skip the whole of {@code audit} and half of {@code saas}.
         */
        @Override
        public void init(Collection<JavaClass> allClasses) {
            for (JavaClass clazz : allClasses) {
                if (!clazz.isAnnotatedWith(RestController.class)) {
                    continue;
                }
                String module = JavaSources.declaringModuleRoot(clazz.getName(), BASE_PACKAGE)
                        .map(root -> root.substring(root.lastIndexOf('.') + 1))
                        .orElse(null);
                if (module == null) {
                    continue;
                }
                for (String classPrefix : mappedValues(clazz, RequestMapping.class)) {
                    for (JavaMethod method : clazz.getMethods()) {
                        for (Class<? extends Annotation> mapping : REQUEST_MAPPINGS) {
                            for (String methodPath : mappedValues(method, mapping)) {
                                registerRoute(classPrefix + methodPath, module);
                            }
                        }
                    }
                }
            }
        }

        private void registerRoute(String path, String module) {
            if (!path.startsWith("/api/")) {
                return;
            }
            String[] segments = path.split("/");
            if (segments.length < 3) {
                return;
            }
            ownerByPrefix.putIfAbsent("/api/" + segments[2], module);
        }

        @Override
        public void check(JavaClass clazz, ConditionEvents events) {
            classesSeen++;
            boolean policyHolder = PATH_POLICY_HOLDERS.contains(clazz.getName());
            String ownModule = JavaSources.declaringModuleRoot(clazz.getName(), BASE_PACKAGE)
                    .map(root -> root.substring(root.lastIndexOf('.') + 1))
                    .orElse(null);

            // UNCONDITIONAL, and it was not always. Running this only on PATH_POLICY_HOLDERS meant a
            // grant written in a helper class — one nobody registered, because registering it is what
            // a reviewer would have noticed — was seen by nothing at all: not by this check (not a
            // holder) and not by the source parser (which only ever parses SecurityConfig). The
            // holders list decides who may name ANOTHER module's routes; it must not also decide who
            // gets their access decisions read. Every production class pays the check.
            checkPathDecisionsAreMachineReadable(clazz, events);

            for (JavaSources.ApiPathLiteral literal : JavaSources.apiPathLiterals(clazz.getName())) {
                literalsSeen++;
                if (policyHolder) {
                    policyHoldersSeen.add(clazz.getName());
                    continue;
                }
                if (API_NAMESPACE_LITERALS.contains(literal.value())) {
                    continue;
                }
                literalsAttributed++;
                String owner = ownerOf(literal.value());
                if (owner == null) {
                    events.add(SimpleConditionEvent.violated(clazz,
                            "Dead path literal '" + literal.value() + "' in " + clazz.getName()
                                    + ":" + literal.line() + " — no @RestController serves any route "
                                    + "under it. Either the endpoint moved and this decision now "
                                    + "matches nothing (a permitAll that silently stopped granting, "
                                    + "an exemption that silently stopped exempting), or the string "
                                    + "has never matched. Known prefixes: " + ownerByPrefix.keySet()));
                } else if (!owner.equals(ownModule)) {
                    events.add(SimpleConditionEvent.violated(clazz,
                            "Cross-module path literal '" + literal.value() + "' in "
                                    + clazz.getName() + ":" + literal.line() + " — that route is "
                                    + "served by module '" + owner + "' but the literal is written in "
                                    + "module '" + ownModule + "'. Nothing in the build can see this "
                                    + "coupling: rename the route and this string silently stops "
                                    + "matching. Either move the decision to the owning module, or "
                                    + "make the handler state it with "
                                    + "@EndpointPolicy and read that instead (see AuditLogInterceptor). "
                                    + "If this really is a central access-policy site, register it in "
                                    + "ArchitectureRules.PATH_POLICY_HOLDERS — which obliges it to be "
                                    + "cross-checked against the live mappings by SecurityPathBindingIT."));
                }
            }
        }

        /**
         * Wherever a class configures URL access it must do so in a form the cross-checks can read
         * back. Classes that write no {@code requestMatchers} call contribute nothing and cost
         * nothing, so this runs on ALL of them rather than on a registered list.
         *
         * <p><b>The list was the hole.</b> While this ran only on {@link #PATH_POLICY_HOLDERS}, a
         * grant written in an unregistered helper class was checked by nothing: not here (not a
         * holder) and not by the source parser, which only ever parses {@code SecurityConfig}.
         * Registration decides who may name ANOTHER module's routes; it must not also decide whose
         * access decisions get read.
         *
         * <p>Reported as a violation rather than thrown so that ALL offending arguments appear in one
         * run — a file that has drifted usually has drifted more than once, and fixing them one
         * build at a time is how a reviewer concludes the problem was a typo. The parallel guard in
         * {@code JavaSources.permitAllMatchers} throws instead, because there the caller is about to
         * derive assertions from a set it cannot trust and must not proceed at all.
         */
        private void checkPathDecisionsAreMachineReadable(JavaClass clazz, ConditionEvents events) {
            for (JavaSources.RequestMatcherArgument argument
                    : JavaSources.requestMatcherArguments(clazz.getName())) {
                matcherArgumentsSeen++;
                if (argument.isReadable()) {
                    continue;
                }
                events.add(SimpleConditionEvent.violated(clazz,
                        "Unreadable path decision " + argument.text() + " in " + clazz.getName()
                                + ":" + argument.line() + " — a requestMatchers argument that is not "
                                + "an inline string literal. It grants access at runtime and "
                                + "contributes NOTHING to any check: the ownership half of this rule "
                                + "skips registered policy holders, and the source parser behind "
                                + "SecurityPathBindingIT extracts only literals, so the assertions keep "
                                + "passing over a surface neither of them can see. That is the "
                                + "filter-chain lock dropping to zero while the gate reports success. "
                                + "Inline the literals so both checks can read them, or teach "
                                + "JavaSources to resolve this form and widen the check in the same "
                                + "commit."));
            }
        }

        /** Longest registered prefix the literal sits under; null when nothing serves it. */
        private String ownerOf(String literal) {
            String best = null;
            for (String prefix : ownerByPrefix.keySet()) {
                boolean under = literal.equals(prefix) || literal.startsWith(prefix + "/");
                if (under && (best == null || prefix.length() > best.length())) {
                    best = prefix;
                }
            }
            return best == null ? null : ownerByPrefix.get(best);
        }

        /**
         * Vacuity guards, five of them, because this rule has five independent ways to certify
         * nothing while reporting success — and "green means verified" is the assumption this
         * repository has been wrong about five times.
         */
        @Override
        public void finish(ConditionEvents events) {
            if (classesSeen == 0) {
                events.add(SimpleConditionEvent.violated(this,
                        "Rule 6 examined ZERO classes. Check that target/classes is built and that "
                                + BASE_PACKAGE + " is still the base package."));
            }
            if (ownerByPrefix.isEmpty()) {
                events.add(SimpleConditionEvent.violated(this,
                        "Rule 6 derived ZERO route prefixes from @RestController mapping annotations, "
                                + "so every literal would be reported as dead or, worse, the rule "
                                + "would be trivially satisfied. The mapping extractor is broken."));
            }
            if (literalsSeen == 0) {
                events.add(SimpleConditionEvent.violated(this,
                        "Rule 6 found ZERO /api path literals in production source. This codebase "
                                + "declares its routes with them, so seeing none means the source "
                                + "scan is not reading what it thinks it is reading."));
            }
            if (literalsAttributed == 0) {
                events.add(SimpleConditionEvent.violated(this,
                        "Rule 6 attributed ZERO literals to an owning module — every one was skipped "
                                + "as a policy holder or as the /api namespace. The ownership check, "
                                + "which is the entire rule, ran against nothing."));
            }
            // A FLOOR, and knowingly a weak one. It catches TOTAL loss only: the readability scan
            // going quiet everywhere at once. It cannot catch ADDITIVE loss — one call site hidden
            // among six leaves five and this stays silent, which is precisely how
            // ".\nrequestMatchers(EVASIVE_PATHS).permitAll()" reached permitAll with a green build.
            // The additive half is NOT defended here and must not be believed to be: it lives in
            // JavaSources.verifyScanAgreesWithIndependentCount, which fails the single FILE whose two
            // independent call-site detectors disagree, no matter how many other files are clean. An
            // aggregate count of what was found can never notice what was not found.
            if (matcherArgumentsSeen == 0) {
                events.add(SimpleConditionEvent.violated(this,
                        "Rule 6 examined ZERO requestMatchers arguments across all of "
                                + BASE_PACKAGE + ". The security chain is written with those calls, "
                                + "so seeing none means the readability check ran against nothing — "
                                + "the state in which an unreadable grant is reported as clean."));
            }
            for (String holder : PATH_POLICY_HOLDERS) {
                if (!policyHoldersSeen.contains(holder)) {
                    events.add(SimpleConditionEvent.violated(this,
                            "Registered path-policy holder " + holder + " was not examined, or holds "
                                    + "no /api literal any more. Either it was renamed or deleted "
                                    + "(remove it from PATH_POLICY_HOLDERS), or its access decisions "
                                    + "moved somewhere this rule is not looking — which is exactly "
                                    + "the state that must never be green."));
                }
            }
        }

        /** {@code value()} with {@code path()} as the fallback alias, for any mapping annotation. */
        private static List<String> mappedValues(Object annotated, Class<? extends Annotation> type) {
            Annotation annotation = annotationOn(annotated, type);
            if (annotation == null) {
                // An absent class-level mapping still contributes one empty prefix, so that method
                // level absolute mappings are seen. Skipping would hide two whole controllers.
                return type == RequestMapping.class && annotated instanceof JavaClass
                        ? List.of("")
                        : List.of();
            }
            String[] values = invoke(annotation, "value");
            if (values.length == 0) {
                values = invoke(annotation, "path");
            }
            return values.length == 0 ? List.of("") : List.of(values);
        }

        private static Annotation annotationOn(Object annotated, Class<? extends Annotation> type) {
            if (annotated instanceof JavaClass clazz) {
                return clazz.isAnnotatedWith(type) ? clazz.getAnnotationOfType(type) : null;
            }
            JavaMethod method = (JavaMethod) annotated;
            return method.isAnnotatedWith(type) ? method.getAnnotationOfType(type) : null;
        }

        private static String[] invoke(Annotation annotation, String member) {
            try {
                return (String[]) annotation.annotationType()
                        .getMethod(member).invoke(annotation);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                        "Cannot read " + member + "() from " + annotation.annotationType()
                                + ". Rule 6 derives route ownership from these members; failing to "
                                + "read one must break the build, not shrink the map silently.", e);
            }
        }
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
