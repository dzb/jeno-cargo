package com.jujin.jeno.resolve;

/**
 * A resolved artifact's reach: {@link #MAIN} artifacts feed the compile
 * visibility, the runtime classpath and the published descriptor;
 * {@link #DEV} artifacts (declared in {@code [dev-deps]}) exist for tests
 * only — they resolve into the same lockfile, but never leak into a build,
 * a run, a package, a dist or a published descriptor.
 *
 * <p>An artifact reachable from both scopes is MAIN: production use wins
 * over test-only use (the same rule Cargo applies to dev-dependencies).
 */
public enum Scope {
    MAIN,
    DEV,
    /**
     * Annotation-processor reach ({@code [build].processors}). Resolves into
     * the lockfile and is handed to javac's {@code -processorpath}, and
     * nowhere else: not the compile classpath (a processor is not an API), not
     * the runtime closure, not a published descriptor. An artifact reachable
     * from MAIN is MAIN — production reach always wins.
     */
    PROCESSOR
}
