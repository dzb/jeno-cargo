package com.jujin.mjm.resolve;

import com.jujin.mjm.project.Species;

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
 * what {@code mjm tree} / {@code mjm why} render.
 *
 * @param source      where the artifact comes from: {@code central}, an
 *                    mj-native origin ({@code gh:owner/repo}), or a named
 *                    repository's base URL — the lockfile's {@code source}
 * @param jarUrl      the artifact's download URL
 * @param metadataUrl the pom or descriptor URL (null for bare URL leaves)
 * @param scope       {@link Scope#MAIN} (production reach) or
 *                    {@link Scope#DEV} (test-only reach — never compiled
 *                    against, never packaged, never published)
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

    /** The artifacts a build/run/package sees — everything except dev-only ones. */
    public List<ResolvedArtifact> mainArtifacts() {
        return artifacts.stream().filter(a -> a.scope() == Scope.MAIN).toList();
    }

    /**
     * The compile classpath: declared MAIN requirements only (strict
     * visibility, SPEC.md §4.4) — dev declarations never appear here.
     */
    public List<ResolvedArtifact> compileArtifacts() {
        return direct.stream().filter(a -> a.scope() == Scope.MAIN).toList();
    }

    public record ResolvedArtifact(
            String id,
            String version,
            Species species,
            String source,
            String jarUrl,
            String metadataUrl,
            int depth,
            boolean declared,
            Scope scope) {

        /** Back-compat: artifacts built by older callers are MAIN. */
        public ResolvedArtifact {
            if (scope == null) {
                scope = Scope.MAIN;
            }
        }

        public ResolvedArtifact(String id, String version, Species species, String source,
                                String jarUrl, String metadataUrl, int depth, boolean declared) {
            this(id, version, species, source, jarUrl, metadataUrl, depth, declared, Scope.MAIN);
        }

        /** The lockfile's {@code id} line value, {@code id@version}. */
        public String locked() {
            return id + "@" + version;
        }
    }
}
