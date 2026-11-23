package com.jujin.jeno.pom;

import java.util.List;

/**
 * One dependency of a decoded pom, in the form jeno's resolver consumes: an
 * exact version (never a range, never a snapshot), a transitive scope
 * ({@code compile} or {@code runtime}), and the exclusions the declaring pom
 * attached — wildcards ({@code *}) included, since Maven Central uses them.
 *
 * @param type       {@code jar} (bundles included) or {@code pom} — a
 *                   {@code pom}-typed dependency has no jar; it exists so the
 *                   resolver can walk its metadata
 * @param classifier nullable — e.g. {@code linux-x86_64}
 */
public record PomDependency(
        String groupId,
        String artifactId,
        String version,
        String scope,
        String type,
        String classifier,
        List<Exclusion> exclusions) {

    public PomDependency {
        if (exclusions == null) {
            exclusions = List.of();
        }
        exclusions = List.copyOf(exclusions);
    }

    /** The {@code group:artifact} key dependency management matches on. */
    public String key() {
        return groupId + ":" + artifactId;
    }

    /** One {@code <exclusion>} entry; either side may be the {@code *} wildcard. */
    public record Exclusion(String groupId, String artifactId) {
    }
}
