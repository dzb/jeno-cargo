package com.jujin.mj.pom;

import java.util.List;
import java.util.Map;

/**
 * One pom as Maven wrote it: raw strings, nothing interpolated, nothing
 * inherited — the parent chain, property resolution and dependency
 * management all happen above this type. Field names mirror the XML so the
 * extraction reads one-to-one.
 */
record RawPom(
        Parent parent,
        String groupId,
        String artifactId,
        String version,
        String packaging,
        Map<String, String> properties,
        List<RawDependency> dependencies,
        List<RawDependency> managedDependencies) {

    /** The declared coordinates of the parent; null when the pom stands alone. */
    record Parent(String groupId, String artifactId, String version) {

        String key() {
            return groupId + ":" + artifactId + ":" + version;
        }
    }

    record RawDependency(
            String groupId,
            String artifactId,
            String version,
            String type,
            String classifier,
            String scope,
            String optional,
            List<Exclusion> exclusions) {

        record Exclusion(String groupId, String artifactId) {
        }
    }
}
