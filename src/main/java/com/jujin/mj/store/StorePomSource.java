package com.jujin.mj.store;

import com.jujin.mj.pom.PomSource;
import com.jujin.mj.resolve.ArtifactLocations;

/**
 * The Central-side metadata source: every pom comes from the store, which
 * downloads on demand and pins its sha256.
 */
public final class StorePomSource implements PomSource {

    private final ArtifactStore store;

    public StorePomSource(ArtifactStore store) {
        this.store = store;
    }

    @Override
    public String pomXml(String groupId, String artifactId, String version, String repositoryBase) {
        String url = ArtifactLocations.mavenFile(repositoryBase, groupId, artifactId, version, "pom");
        return store.readText(url, ArtifactStore.Kind.POM);
    }
}
