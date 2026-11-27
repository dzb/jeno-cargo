package com.jujin.jeno.cli;

import com.jujin.jeno.project.Repository;
import com.jujin.jeno.store.LockedArtifact;

/**
 * The one vocabulary for "why this artifact cannot be checked", shared by
 * {@code outdated} and {@code audit}. Two commands describing the same lock row
 * with different words made CI filters impossible to write.
 *
 * <p>The labels are about the <em>species and layout</em>, not about which
 * query failed: a {@code gh:} release is a native release, a jeno-native
 * repository (local or remote) uses its per-artifact {@code index.jeno.toml}
 * when present and is otherwise uncheckable, a local Maven directory has no
 * listing, and a bare URL is a leaf. Anything else — including a source the
 * parser cannot classify — is a named repository.
 */
final class ArtifactReasons {

    private ArtifactReasons() {}

    static String of(LockedArtifact artifact) {
        String source = artifact.source();
        if (source.startsWith("gh:")) {
            return "native-release";
        }
        if (source.equals("url")) {
            return "bare-url";
        }
        Repository repository;
        try {
            repository = Repository.parse(source);
        } catch (IllegalArgumentException unknownSource) {
            return "named-repository";
        }
        if (repository.layout() == Repository.Layout.JENO) {
            return "jeno-repository";
        }
        return repository.isLocal() ? "local-repository" : "named-repository";
    }
}
