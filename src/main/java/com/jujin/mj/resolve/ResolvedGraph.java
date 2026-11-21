package com.jujin.mj.resolve;

import com.jujin.mj.project.Species;

import java.util.List;
import java.util.Map;

/**
 * The resolution result: every artifact in the closed graph, selected at
 * exactly one version each (SPEC.md §4.2), with the facts the lockfile, the
 * build and the tree commands need. {@link #artifacts()} is the full closure
 * (the runtime classpath's content), sorted by id; {@link #direct()} is the
 * declared subset — the compile classpath's content (SPEC.md §4.4's strict
 * visibility is "free" here: mj builds both lists from one graph);
 * {@link #edges()} is the walked graph — parent id to child ids — which is
 * what {@code mj tree} / {@code mj why} render.
 *
 * @param source      where the artifact comes from: {@code central}, an
 *                    mj-native origin ({@code gh:owner/repo}), or a named
 *                    repository's base URL — the lockfile's {@code source}
 * @param jarUrl      the artifact's download URL
 * @param metadataUrl the pom or descriptor URL (null for bare URL leaves)
 */
public record ResolvedGraph(
        List<ResolvedArtifact> artifacts,
        List<ResolvedArtifact> direct,
        Map<String, List<String>> edges) {

    public ResolvedGraph {
        if (artifacts == null) {
            artifacts = List.of();
        }
        artifacts = List.copyOf(artifacts);
        if (direct == null) {
            direct = List.of();
        }
        direct = List.copyOf(direct);
        if (edges == null) {
            edges = Map.of();
        }
        edges = Map.copyOf(edges);
    }

    public record ResolvedArtifact(
            String id,
            String version,
            Species species,
            String source,
            String jarUrl,
            String metadataUrl,
            int depth,
            boolean declared) {

        /** The lockfile's {@code id} line value, {@code id@version}. */
        public String locked() {
            return id + "@" + version;
        }
    }
}
