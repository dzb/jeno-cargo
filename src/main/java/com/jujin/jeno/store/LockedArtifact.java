package com.jujin.jeno.store;

import com.jujin.jeno.resolve.Scope;

/**
 * One row of jeno.lock: the resolution facts the build runs on — identity,
 * origin, the pinned bytes (SPEC.md §4). {@code metaSha256} is null for bare
 * URL leaves, which have no metadata; {@code scope} is {@link Scope#MAIN}
 * for production artifacts and {@link Scope#DEV} for test-only ones (the
 * lockfile writes {@code scope = "dev"} only for DEV rows — an absent scope
 * is MAIN, so pre-{@code [deps-dev]} lockfiles read unchanged).
 *
 * <p>{@link #source()} is the normalized internal canonical spelling, not
 * necessarily the text in a v3 lock file. Lock v3 writes the jeno layout
 * unmarked and Maven as {@code maven:...}, while this field keeps the older
 * internal spelling so all in-process comparisons and resolver identities stay
 * stable. Commands that display a source use {@code Repository.displaySource}.
 */
public record LockedArtifact(
        String id,
        String version,
        String source,
        String url,
        String sha256,
        String metaSha256,
        int depth,
        Scope scope,
        boolean api) {

    public LockedArtifact {
        if (scope == null) {
            scope = Scope.MAIN;
        }
    }

    /** Back-compat: rows without an {@code api} marker are not exported API. */
    public LockedArtifact(String id, String version, String source, String url,
                          String sha256, String metaSha256, int depth, Scope scope) {
        this(id, version, source, url, sha256, metaSha256, depth, scope, false);
    }

    /** Back-compat constructor: every pre-scope caller built MAIN rows. */
    public LockedArtifact(String id, String version, String source, String url,
                          String sha256, String metaSha256, int depth) {
        this(id, version, source, url, sha256, metaSha256, depth, Scope.MAIN, false);
    }

    /** Whether this artifact belongs on a production classpath. */
    public boolean isMain() {
        return scope == Scope.MAIN;
    }

    /**
     * The URL to read these bytes from. The lock stores what it wrote — for a
     * {@code local:} repository that is {@code file:vendor/…}, relative to the
     * project — so a reader expands it against the project root. Absolute
     * {@code file:///…} rows (older locks) come back unchanged.
     */
    public String readableUrl(java.nio.file.Path projectRoot) {
        return com.jujin.jeno.resolve.ArtifactLocations.expand(url, projectRoot);
    }
}
