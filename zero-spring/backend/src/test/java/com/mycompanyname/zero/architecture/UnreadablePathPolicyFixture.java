package com.mycompanyname.zero.architecture;

/**
 * The defect, preserved: a security chain whose grants are spelled two different ways.
 *
 * <p>This is the auditor's measured finding, frozen into a file so the guard that now catches it can
 * be shown catching it. Written against a local stand-in for the Spring DSL rather than the real
 * {@code HttpSecurity}, because the only thing under test is how the SOURCE reads — the fixture never
 * runs and never configures anything.
 *
 * <p>Not production code, and invisible to Rule 6: {@code ArchitectureRulesTest} imports with
 * {@code DO_NOT_INCLUDE_TESTS}, so nothing here is scanned as an access decision. It is reached only
 * by name, from the one test that proves the readability check goes red.
 */
final class UnreadablePathPolicyFixture {

    /** The exact form that sat at permitAll with surefire 137, failsafe 271 and BUILD SUCCESS. */
    private static final String[] PARTNER_PATHS = {"/api/tenants/**"};

    private UnreadablePathPolicyFixture() {
    }

    static Chain configure(Chain auth) {
        return auth
                // Two readable arguments: this is what a grant is supposed to look like.
                .requestMatchers("/api/auth/login", "/api/auth/refresh").permitAll()
                // One unreadable argument, and the reason the whole check exists. The group matches,
                // the literal extraction finds nothing inside it, and the grant is real anyway.
                .requestMatchers(PARTNER_PATHS).permitAll()
                // The same hole with a different verb, to prove the check keys on the ARGUMENT and
                // not on permitAll — closing the class rather than the spelling.
                .requestMatchers(PARTNER_PATHS).hasAuthority("settings.host.manage")
                // The auditor's evasion, verbatim, and the reason the scan matches a PATTERN instead
                // of the contiguous token ".requestMatchers". Java does not require the dot, the name
                // and the paren to be adjacent; comments are stripped with their LINES PRESERVED, so
                // a newline landing here is the ordinary case rather than an exotic one. Measured on
                // the pre-fix scan: this form was read as no call at all, /api/tenants/** sat at
                // permitAll, and ArchitectureRulesTest reported 9 tests, 0 failures, BUILD SUCCESS.
                //
                // It also pins the SECOND detector. Revert the pattern match and the character walk
                // finds three calls here while the regex in JavaSources finds four — the two disagree
                // and verifyScanAgreesWithIndependentCount throws. That is deliberate: the guard
                // against additive loss needs a file where losing ONE call is visible, and an
                // aggregate count of the other three never would be.
                .
                requestMatchers(PARTNER_PATHS).permitAll();
    }

    /** The shape of the fluent DSL, and nothing else. */
    interface Chain {
        Chain requestMatchers(String... patterns);

        Chain permitAll();

        Chain hasAuthority(String authority);
    }
}
