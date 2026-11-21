package com.jujin.mj.store;

/**
 * One row of mj.lock: the resolution facts the build runs on — identity,
 * origin, the pinned bytes (SPEC.md §4). {@code metaSha256} is null for bare
 * URL leaves, which have no metadata.
 */
public record LockedArtifact(
        String id,
        String version,
        String source,
        String url,
        String sha256,
        String metaSha256,
        int depth) {
}
