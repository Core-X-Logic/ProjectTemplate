package com.mycompanyname.zero.shared.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A handler's own statement about how it expects to be exposed — the counter-signature to the path
 * strings that {@code SecurityConfig} and {@code SubscriptionAccessCheck} use to make the matching
 * access decision.
 *
 * <p><b>Why this exists.</b> Five access decisions in this codebase are bound to a URL prefix owned
 * by a different module: {@code permitAll("/api/localization/**")} in {@code identity}, three
 * exemptions in {@code saas} naming {@code identity}'s and {@code localization}'s prefixes, and (until
 * this annotation landed) an audit skip list in {@code audit} naming {@code identity}'s login paths.
 * Because the binding is a string, the compiler, ArchUnit and Spring Modulith are all blind to it:
 * rename {@code /api/localization} to {@code /api/i18n} and every gate in the build stays green while
 * the login screen starts answering 401 on the dictionary it must load <em>before</em> it can render
 * a login form.
 *
 * <p><b>What it is not.</b> This is a CLAIM, never a GRANT. Writing {@code @EndpointPolicy(ANONYMOUS)}
 * exposes nothing — the grant still lives, in one reviewable place, in {@code SecurityConfig}'s
 * {@code permitAll} matchers and in {@code SubscriptionAccessCheck.DEFAULT_EXEMPT_PATHS}. The tests
 * assert AGREEMENT in both directions: a claim with no matching grant fails the build (the module
 * believes it is public and is not), and a grant covering a handler that does not claim it fails the
 * build too (the dangerous direction — a matcher written {@code /api/account/**} instead of
 * {@code /api/account/confirm-email}). Neither side can widen exposure on its own.
 *
 * <p><b>Why an annotation and not a constants class.</b> {@code public static final String} is a
 * compile-time constant: javac folds it into the referencing class file, so a shared {@code ApiPaths}
 * class would emit byte-identical output to the inline literal and make exactly nothing visible to
 * any tool. An enum constant used as an annotation member is a real reference to the owning type.
 * {@code JavaSources} documents the same measurement for {@code @PreAuthorize}.
 *
 * <p><b>Why {@code shared}.</b> {@code shared} is {@code @ApplicationModule(type = OPEN)} and is
 * already an allowed dependency of every module, so carrying this annotation costs no new
 * {@code allowedDependencies} entry and no new {@code @NamedInterface}. In particular {@code audit}
 * stays at {@code allowedDependencies = {"shared"}} — the tightest boundary in the codebase — while
 * its two hardcoded {@code identity} paths are deleted outright.
 *
 * <p><b>{@code METHOD} only, deliberately.</b> A type-level claim would silently cover the next
 * method someone adds to the controller, which is the failure this whole mechanism exists to stop.
 * Restricting the target makes that a compile error rather than something a test has to notice.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface EndpointPolicy {

    /** Every exposure this handler claims. Order is irrelevant; duplicates are harmless. */
    Exposure[] value();

    /** The exposures a handler can claim, one per decision site that reads a path today. */
    enum Exposure {

        /**
         * Reachable with no credential. Must be backed by a {@code permitAll} matcher in
         * {@code SecurityConfig} that covers every pattern this handler is mapped to.
         */
        ANONYMOUS,

        /**
         * Reachable while the tenant's subscription is expired or unpaid. Must be backed by an entry
         * in {@code SubscriptionAccessCheck.DEFAULT_EXEMPT_PATHS}. The point of claiming it per
         * method is that the {@code /api/auth/**} and {@code /api/account/**} wildcards have a blast
         * radius, and it should be written down rather than inherited.
         */
        SUBSCRIPTION_EXEMPT,

        /**
         * Not recorded in {@code audit_logs}. Read directly by {@code AuditLogInterceptor} off the
         * {@link org.springframework.web.method.HandlerMethod} the container already hands it — this
         * is the one exposure that is genuinely inverted rather than merely cross-checked, so
         * {@code audit} holds no path string at all. Reserved for endpoints whose parameters are
         * credentials.
         */
        AUDIT_EXEMPT
    }
}
