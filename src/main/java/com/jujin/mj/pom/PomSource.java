package com.jujin.mj.pom;

/**
 * Supplies the raw {@code pom.xml} text of Maven artifacts — the one seam
 * between decoding and fetching. The decoder calls it for the requested
 * artifact, its parents and its BOM imports (all inheriting the same
 * {@code repositoryBase}); the production implementation downloads through
 * the store, a test passes a map.
 *
 * <p>{@code repositoryBase} is the resolved base of the repository the
 * lookup belongs to — Central's URL, a named repository's base, or a
 * {@code local:} directory. Missing artifacts are signaled by throwing; the
 * resolver wraps anything thrown into a {@link PomException} naming the
 * artifact it went looking for.
 */
@FunctionalInterface
public interface PomSource {

    /** The raw {@code pom.xml} text of {@code group:artifact:version}. */
    String pomXml(String groupId, String artifactId, String version, String repositoryBase);
}
