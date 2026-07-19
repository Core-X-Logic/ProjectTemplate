package com.mycompanyname.zero.architecture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 *   <li>Module declarations live on packages, and a {@code package-info.java} that carries no
 *       annotation produces <em>no</em> {@code package-info.class} at all. Rule 4 has to walk from
 *       an entity's package UP to its module root, inspecting ancestors that frequently contain no
 *       class of their own; on the classpath those packages simply do not exist as artifacts, so a
 *       bytecode walk would find nothing and report a clean run.
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

    /**
     * A WHOLE string literal that is an {@code /api} path and nothing else. Both ends are anchored
     * inside the quotes on purpose, and the measurement that forced it is in this repository:
     * {@code ExportLimitProperties:75} contains
     * {@code "/api/users/export and /api/audit-logs/export answering every request "} — English prose
     * that happens to begin with a path. A prefix-anchored scan reports it as a dead access decision
     * and the rule goes red against correct code on day one. Excluding whitespace and quotes from the
     * tail is what tells a route apart from a sentence.
     */
    private static final Pattern API_PATH_LITERAL = Pattern.compile("\"(/api(?:/[^\\s\"]*)?)\"");

    /**
     * {@code .requestMatchers("a", "b").permitAll()} in {@code SecurityConfig}'s fluent chain. The
     * inner group excludes parentheses so the match cannot run past the call it belongs to, and the
     * source is whitespace-normalised first because the chain wraps across lines.
     */
    private static final Pattern PERMIT_ALL_GROUP =
            Pattern.compile("\\.\\s*requestMatchers\\s*\\(([^()]*)\\)\\s*\\.\\s*permitAll\\s*\\(\\s*\\)");

    /**
     * The SAME call site as {@link #openingParenOfRequestMatchers}, detected by a completely
     * different mechanism: one regex over the whole file instead of a hand-rolled character walk.
     *
     * <p>This exists to be a second opinion, not a convenience. The hole this file has now been bitten
     * by twice is ADDITIVE loss — one call site going quiet while the others keep the aggregate
     * counter comfortably non-zero, so every "did we see anything at all" guard stays satisfied. No
     * count of things-we-found can detect a thing-we-did-not-find. Two independent detectors of the
     * same thing can, because the one that missed it DISAGREES with the one that did not. See
     * {@link #requestMatcherArguments}, which refuses to return when they disagree.
     */
    private static final Pattern REQUEST_MATCHERS_CALL =
            Pattern.compile("\\.\\s*requestMatchers\\s*\\(");

    /** A string or char literal, blanked before the independent count so quoted prose is not one. */
    private static final Pattern ANY_LITERAL =
            Pattern.compile("\"(?:[^\"\\\\\\n]|\\\\.)*\"|'(?:[^'\\\\\\n]|\\\\.)*'");

    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"]*)\"");

    /** A whole argument that is nothing but one string literal — the only form the parsers read. */
    private static final Pattern WHOLE_STRING_LITERAL =
            Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");

    private JavaSources() {
    }

    /** One {@code /api} path literal written in source, with the line it was written on. */
    record ApiPathLiteral(int line, String value) {
    }

    /**
     * Every whole {@code /api…} string literal in {@code className}'s source, comments removed.
     *
     * <p>Source and not bytecode, for the reason this whole class exists: a bytecode rule cannot tell
     * a path constant apart from a raw literal, and cannot attribute a constant-pool string to the
     * line that wrote it. Fails loudly through {@link #read} when the source file is missing.
     */
    static List<ApiPathLiteral> apiPathLiterals(String className) {
        List<String> lines = withoutCommentsPreservingLines(read(className)).lines().toList();
        List<ApiPathLiteral> found = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher matcher = API_PATH_LITERAL.matcher(lines.get(i));
            while (matcher.find()) {
                found.add(new ApiPathLiteral(i + 1, matcher.group(1)));
            }
        }
        return found;
    }

    /**
     * Every path literal granted {@code permitAll()} in {@code className}'s security chain.
     *
     * <p><b>NOT the load-bearing check any more, and the caller must know it.</b> This parses one
     * named file's fluent DSL. Four evasions were measured against it, all with a live grant on the
     * chain and a green build: a backslash-u escaped dot, a readable literal in a helper class outside
     * that file, a second {@code SecurityFilterChain} bean, and
     * {@code WebSecurityCustomizer.ignoring()}. The last three contain nothing this parser is even
     * looking at. {@code FilterChainReachabilityIT} probes the RUNNING CHAIN and caught all four; this
     * stays because it names a file and a line, which the wire probe cannot.
     *
     * <p><b>This is the most brittle component of the design and it is deliberately fail-loud.</b> It
     * parses one specific file's fluent DSL. Extract the matchers into a helper method, build one
     * from a variable, or move the list to a {@code @Bean}, and this parser sees less than the truth
     * — which is the dangerous direction for a rule that asserts "nothing outside this set is
     * anonymous".
     *
     * <p><b>Two failure modes, and the second one was measured green.</b> A TOTAL parse loss is
     * caught by the zero-group throw below plus the canaries the call sites assert. An ADDITIVE one
     * is not, and that hole was real: writing
     *
     * <pre>{@code
     * private static final String[] PARTNER_PATHS = {"/api/tenants/**"};
     * ...
     * .requestMatchers(PARTNER_PATHS).permitAll()
     * }</pre>
     *
     * matches {@link #PERMIT_ALL_GROUP} (so the group count is non-zero), yields ZERO literals from
     * inside it, leaves both canaries standing and both size floors satisfied. Measured result: the
     * whole tenancy admin surface at {@code permitAll} with surefire 137, failsafe 271, BUILD SUCCESS.
     * Counting literals per group cannot fix this — one legitimate group carries two or three of them
     * — so the invariant enforced instead is that every {@code requestMatchers} ARGUMENT is a form
     * this parser can read, checked by {@link #verifyRequestMatchersAreReadable} before any
     * extraction happens. A form it cannot read fails; it is never skipped.
     */
    static Set<String> permitAllMatchers(String className) {
        // Before extracting anything: prove the file contains nothing this parser cannot read.
        // Without this, an ADDITIVE unreadable grant is silently dropped and every assertion
        // derived from the returned set narrows without saying so. See the method's javadoc.
        verifyRequestMatchersAreReadable(className);
        String source = withoutCommentsPreservingLines(read(className)).replaceAll("\\s+", " ");
        Set<String> matchers = new LinkedHashSet<>();
        Matcher group = PERMIT_ALL_GROUP.matcher(source);
        int groups = 0;
        while (group.find()) {
            groups++;
            Matcher literal = STRING_LITERAL.matcher(group.group(1));
            while (literal.find()) {
                matchers.add(literal.group(1));
            }
        }
        if (groups == 0 || matchers.isEmpty()) {
            throw new IllegalStateException(
                    "Parsed " + groups + " permitAll() matcher group(s) and " + matchers.size()
                            + " literal(s) out of " + className + ". This parser reads the security "
                            + "chain to prove that every anonymous endpoint has a grant and that no "
                            + "other endpoint does; seeing nothing means it can prove neither. If the "
                            + "chain was legitimately refactored, fix the parser deliberately — do "
                            + "NOT relax this guard, which is the only thing standing between a "
                            + "reformat and a security rule that passes vacuously.");
        }
        return matchers;
    }

    /**
     * One argument of one {@code .requestMatchers(...)} call, with the line it was written on.
     *
     * @param line the source line the enclosing call starts on
     * @param text the argument exactly as written, trimmed
     */
    record RequestMatcherArgument(int line, String text) {

        /**
         * Whether this argument is a bare string literal — the ONLY form
         * {@link #permitAllMatchers} and {@code SecurityPathBindingIT} can actually resolve to a
         * route. A constant, an array, a field, a method call or a {@code HttpMethod} overload all
         * read as a route decision to a human and as nothing at all to the parser.
         */
        boolean isReadable() {
            return WHOLE_STRING_LITERAL.matcher(text).matches();
        }
    }

    /**
     * Every argument of every {@code .requestMatchers(...)} call in {@code className}'s source.
     *
     * <p>Unlike {@link #PERMIT_ALL_GROUP} this balances parentheses instead of excluding them, so a
     * call whose argument is itself a call ({@code .requestMatchers(antMatcher("/x"))}) is SEEN and
     * reported rather than failing to match and disappearing. It is also indifferent to what the
     * chain does next: {@code permitAll()}, {@code hasAuthority(...)} and anything added later are
     * all read the same way, because the hole being closed is in the ARGUMENT, not in the verb.
     */
    static List<RequestMatcherArgument> requestMatcherArguments(String className) {
        String source = withoutCommentsPreservingLines(read(className));
        List<RequestMatcherArgument> arguments = new ArrayList<>();
        int i = 0;
        int line = 1;
        int callSites = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            // Literals are SKIPPED rather than searched, so that prose or a violation message
            // quoting the call is never mistaken for one — Rule 6's own message contains the token.
            if (c == '"' || c == '\'') {
                int end = copyLiteral(source, i, c, new StringBuilder());
                for (int j = i; j < end; j++) {
                    if (source.charAt(j) == '\n') {
                        line++;
                    }
                }
                i = end;
                continue;
            }
            if (c == '\n') {
                line++;
                i++;
                continue;
            }
            int open = openingParenOfRequestMatchers(source, i);
            if (open < 0) {
                i++;
                continue;
            }
            callSites++;
            int close = closingParen(source, open);
            for (String argument : splitTopLevelArguments(source.substring(open + 1, close))) {
                arguments.add(new RequestMatcherArgument(line, argument));
            }
            // The dot, the name and the paren need not be adjacent, so the skipped gap can contain
            // newlines. Counting them keeps every later line number honest; the arguments above are
            // attributed to the line the CALL starts on, which is the dot.
            for (int j = i; j < open; j++) {
                if (source.charAt(j) == '\n') {
                    line++;
                }
            }
            i = open + 1;
        }
        verifyScanAgreesWithIndependentCount(className, source, callSites);
        return arguments;
    }

    /**
     * Throws when the character walk above and {@link #REQUEST_MATCHERS_CALL} disagree about how many
     * {@code requestMatchers} calls a file contains.
     *
     * <p><b>Why a second detector and not a bigger number.</b> Every vacuity guard this file had
     * before was an aggregate: "did the scan find ZERO?". That question cannot be answered wrong by a
     * partial loss. Hiding one call among six leaves five, the counter stays non-zero, and the guard
     * says nothing — which is exactly how {@code .\nrequestMatchers(EVASIVE_PATHS)} reached
     * {@code permitAll} with a green build. The invariant that actually holds is a RELATIONSHIP, not a
     * magnitude: two detectors reading the same file for the same construct must agree. A count-based
     * assertion ("there are six calls") was deliberately rejected — this repository has been through
     * that with {@code PermissionRegistryAlignmentTest}, which is relationship-based for the same
     * reason: a hard number is a number someone updates to make the build green.
     *
     * <p><b>What this does NOT cover, stated so nobody assumes it does.</b> Both detectors run on the
     * output of {@link #withoutCommentsPreservingLines}. A defect in the comment stripper itself moves
     * both of them the same way and they would agree while both being wrong. The stripper is shared on
     * purpose — it is load-bearing and separately measured (see its javadoc) — but it is a single point
     * of agreement, and the independence claimed here is about call-site DETECTION only.
     */
    private static void verifyScanAgreesWithIndependentCount(
            String className, String strippedSource, int callSites) {
        Matcher matcher = REQUEST_MATCHERS_CALL.matcher(ANY_LITERAL.matcher(strippedSource)
                .replaceAll(""));
        int independent = 0;
        while (matcher.find()) {
            independent++;
        }
        if (independent != callSites) {
            throw new IllegalStateException(
                    "Two independent scans of " + className + " disagree about how many "
                            + "requestMatchers calls it contains: the source walk read " + callSites
                            + ", a plain regex over the same text found " + independent + ". One of "
                            + "them is missing an access decision, and the dangerous direction is the "
                            + "walk missing it: an argument the walk never sees is never checked for "
                            + "readability, never extracted as a grant, and still grants at runtime. "
                            + "This is the ADDITIVE loss that aggregate 'did we see anything' guards "
                            + "cannot detect by construction. Do not silence this by relaxing either "
                            + "scan — find the form that only one of them reads and teach the other, "
                            + "in the same commit.");
        }
    }

    /**
     * Throws unless every {@code requestMatchers} argument in {@code className} is a bare string
     * literal. Returns the number of arguments examined, so callers can guard their own vacuity.
     *
     * <p><b>Deliberately stricter than "the parser happens to cope".</b> Rejecting a
     * {@code HttpMethod} overload or a well-named path constant will one day fail a change that is
     * perfectly correct. That is the intended trade: the failure is a red build with instructions,
     * whereas the alternative — accepting a form nobody taught the parser to read — is a green build
     * over an access decision no gate can see. This project has already paid for the second kind
     * five times. Widening the accepted forms must be a deliberate edit here, together with the
     * extraction code that has to understand them.
     */
    static int verifyRequestMatchersAreReadable(String className) {
        List<RequestMatcherArgument> arguments = requestMatcherArguments(className);
        List<String> unreadable = new ArrayList<>();
        for (RequestMatcherArgument argument : arguments) {
            if (!argument.isReadable()) {
                unreadable.add(argument.text() + " (line " + argument.line() + ")");
            }
        }
        if (!unreadable.isEmpty()) {
            throw new IllegalStateException(
                    "Unreadable requestMatchers argument(s) in " + className + ": " + unreadable
                            + ". Every path decision, in ANY class and not only a registered policy "
                            + "holder, must be "
                            + "written as an inline string literal, because that is the only form the "
                            + "source parser behind SecurityPathBindingIT can resolve against the live "
                            + "handler mapping. A constant, an array or a computed value still grants "
                            + "access at runtime while contributing NOTHING to the parsed set — so the "
                            + "grant-vs-claim assertions keep passing over a surface they can no "
                            + "longer see, and the filter-chain lock drops silently to zero. Inline "
                            + "the literals, or teach JavaSources to resolve the new form and widen "
                            + "this check in the same commit. Do not relax it.");
        }
        return arguments.size();
    }

    /**
     * Index of the {@code (} opening a {@code .requestMatchers} call that starts at {@code at}, or
     * {@code -1} when no such call starts there.
     *
     * <p><b>The dot, the name and the paren are matched as a PATTERN, never as one contiguous
     * token, and that distinction was measured.</b> The first version of this scan asked
     * {@code source.startsWith(".requestMatchers", at)}. Java does not require those characters to
     * be adjacent: whitespace, a newline or a comment may sit between the dot and the method name,
     * and any of them breaks the token while leaving the call perfectly valid. The auditor's
     * reproduction was
     *
     * <pre>{@code
     * .requestMatchers("/api/localization/**").permitAll()
     * .
     * requestMatchers(EVASIVE_PATHS).permitAll()
     * }</pre>
     *
     * which put {@code /api/tenants/**} at {@code permitAll} at runtime while the readability check
     * counted ZERO unreadable arguments and the whole build stayed green. Comments reach the same
     * state, because {@link #withoutCommentsPreservingLines} blanks them but KEEPS the newlines they
     * spanned — so a newline between the dot and the name is the realistic case, not an exotic one,
     * and the whitespace skip below must span it.
     */
    private static int openingParenOfRequestMatchers(String source, int at) {
        if (source.charAt(at) != '.') {
            return -1;
        }
        String name = "requestMatchers";
        int i = skipWhitespace(source, at + 1);
        if (!source.startsWith(name, i)) {
            return -1;
        }
        i += name.length();
        // requestMatchersOfMine(...) is a different method and must not be read as this one.
        if (i < source.length() && Character.isJavaIdentifierPart(source.charAt(i))) {
            return -1;
        }
        i = skipWhitespace(source, i);
        return i < source.length() && source.charAt(i) == '(' ? i : -1;
    }

    private static int skipWhitespace(String source, int from) {
        int i = from;
        while (i < source.length() && Character.isWhitespace(source.charAt(i))) {
            i++;
        }
        return i;
    }

    /** Index of the {@code )} closing the {@code (} at {@code open}, ignoring parens in literals. */
    private static int closingParen(String source, int open) {
        int depth = 0;
        int i = open;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                i = copyLiteral(source, i, c, new StringBuilder());
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
            i++;
        }
        throw new IllegalStateException(
                "Unbalanced requestMatchers( argument list at offset " + open + ". The source scan "
                        + "cannot read this file, which must fail rather than certify nothing.");
    }

    /** Splits an argument list at top-level commas, respecting nesting and string literals. */
    private static List<String> splitTopLevelArguments(String argumentList) {
        List<String> arguments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        int i = 0;
        while (i < argumentList.length()) {
            char c = argumentList.charAt(i);
            if (c == '"' || c == '\'') {
                i = copyLiteral(argumentList, i, c, current);
                continue;
            }
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                arguments.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
            i++;
        }
        String last = current.toString().trim();
        if (!last.isEmpty()) {
            arguments.add(last);
        }
        return arguments;
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

    /**
     * The nearest package at or above {@code className} whose {@code package-info.java} declares
     * {@code @ApplicationModule} — that is, the Modulith module root governing the class. Empty
     * when no ancestor up to and including {@code basePackage} declares one.
     *
     * <p>Walking UP is the whole point. Modulith module roots are the direct sub-packages of the
     * application base package; everything below a root is internal to it and is governed by the
     * root's declaration, not by a declaration of its own. A check that only looked at the class'
     * OWN package would demand a declaration in exactly the places Modulith forbids one.
     *
     * <p>Both {@code allowedDependencies = {...}} and {@code type = Type.OPEN} count. They are
     * opposite decisions — one narrows the module's imports, the other waives the boundary — but
     * both are decisions a human wrote down. The absence of any declaration is the thing this
     * looks for, because that is the state Modulith cannot distinguish from a clean one.
     */
    static Optional<String> declaringModuleRoot(String className, String basePackage) {
        String candidate = packageOf(className);
        while (candidate.startsWith(basePackage)) {
            if (declaresApplicationModule(candidate)) {
                return Optional.of(candidate);
            }
            int lastDot = candidate.lastIndexOf('.');
            if (lastDot < 0) {
                break;
            }
            candidate = candidate.substring(0, lastDot);
        }
        return Optional.empty();
    }

    /** True when {@code packageName}'s {@code package-info.java} carries {@code @ApplicationModule}. */
    private static boolean declaresApplicationModule(String packageName) {
        String packagePath = packageName.replace('.', '/');
        return SOURCE_ROOTS.stream()
                .map(root -> root.resolve(packagePath).resolve("package-info.java"))
                .filter(Files::isRegularFile)
                .anyMatch(file -> APPLICATION_MODULE
                        .matcher(withoutComments(readFile(file)))
                        .find());
    }

    /**
     * {@code @ApplicationModule} as an actual annotation: at the start of a line, after comments
     * have been stripped. Prose mentions of the annotation are common in these files — the
     * {@code shared.domain} package-info discusses {@code ApplicationModule.Type.OPEN} at length —
     * and counting one would be the dangerous direction of error: an entity would be certified as
     * living under a declared root by a package that declares nothing.
     */
    private static final Pattern APPLICATION_MODULE =
            Pattern.compile("(?m)^\\s*@ApplicationModule\\b");

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

    private static String readFile(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    /** Block and line comments blanked out, so prose about an annotation cannot pass for one. */
    private static String withoutComments(String source) {
        return source
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
    }

    /**
     * Comments blanked out, line numbers preserved, and — unlike {@link #withoutComments} — string
     * literals respected.
     *
     * <p><b>Why a scanner and not two regexes.</b> This was measured, and it silenced a rule
     * completely. An Ant path pattern contains the characters that open a block comment:
     * {@code "/api/auth/**"} holds {@code /*}. Running {@code (?s)/\*.*?\*&#47;} over
     * {@code SubscriptionAccessCheck} therefore started a "comment" inside the exemption list and
     * ended it at the closing {@code *&#47;} of the next javadoc thirty lines below, deleting all
     * four exemption literals. The rule then reported that the file contains no path decisions —
     * green, and certifying nothing. Only the vacuity guard caught it.
     *
     * <p>So the state machine below is load-bearing, not tidiness: a comment stripper that cannot
     * tell a comment from a wildcard is a stripper that hides exactly the strings these rules exist
     * to find. Text blocks are not handled; no path in this codebase is written as one, and
     * {@link #apiPathLiterals} would simply not see it (Rule 6's documented blind spot, shared with
     * Rule 3).
     */
    private static String withoutCommentsPreservingLines(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                i = copyLiteral(source, i, c, out);
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                while (i < source.length() && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                i += 2;
                while (i < source.length()
                        && !(source.charAt(i) == '*' && i + 1 < source.length()
                                && source.charAt(i + 1) == '/')) {
                    if (source.charAt(i) == '\n') {
                        out.append('\n');
                    }
                    i++;
                }
                i = Math.min(i + 2, source.length());
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** Copies a string or char literal verbatim, honouring backslash escapes. Returns the next index. */
    private static int copyLiteral(String source, int start, char quote, StringBuilder out) {
        out.append(quote);
        int i = start + 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            out.append(c);
            i++;
            if (c == '\\' && i < source.length()) {
                out.append(source.charAt(i));
                i++;
            } else if (c == quote || c == '\n') {
                break;
            }
        }
        return i;
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
