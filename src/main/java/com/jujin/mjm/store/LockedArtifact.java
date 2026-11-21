package com.jujin.mjm.store;

import com.jujin.mjm.resolve.Scope;

/**
 * One row of mjm.lock: the resolution facts the build runs on — identity,
 * origin, the pinned bytes (SPEC.md §4). {@code metaSha256} is null for bare
 * URL leaves, which have no metadata; {@code scope} is {@link Scope#MAIN}
 * for production artifacts and {@link Scope#DEV} for test-only ones (the
 * lockfile writes {@code scope = "dev"} only for DEV rows — an absent scope
 * is MAIN, so pre-{@code [dev-deps]} lockfiles read unchanged).
 */
public record LockedArtifact(
        String id,
        String version,
        String source,
        String url,
        String sha256,
        String metaSha256,
        int depth,
        Scope scope) {

    public LockedArtifact {
        if (scope == null) {
            scope = Scope.MAIN;
        }
    }

    /** Back-compat constructor: every pre-scope caller built MAIN rows. */
    public LockedArtifact(String id, String version, String source, String url,
                          String sha256, String metaSha256, int depth) {
        this(id, version, source, url, sha256, metaSha256, depth, Scope.MAIN);
    }

    /** Whether this artifact belongs on a production classpath. */
    public boolean isMain() {
        return scope == Scope.MAIN;
    }
}
