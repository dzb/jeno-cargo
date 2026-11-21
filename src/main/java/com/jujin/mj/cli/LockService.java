package com.jujin.mj.cli;

import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.mj.pom.PomSource;
import com.jujin.mj.project.DescriptorSource;
import com.jujin.mj.project.MjProject;
import com.jujin.mj.project.ProjectReader;
import com.jujin.mj.project.Species;
import com.jujin.mj.resolve.LockFile;
import com.jujin.mj.resolve.Resolver;
import com.jujin.mj.resolve.ResolvedGraph;
import com.jujin.mj.store.ArtifactStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The lock flow shared by {@code mj lock} and {@code mj add}: resolve the
 * graph, download every byte into the store, pin the digests into mj.lock
 * (SPEC.md §4). One owner of the "lock" fact — the command is the shell
 * around it.
 */
public final class LockService {

    private final ArtifactStore store;
    private final PomSource pomSource;
    private final DescriptorSource descriptorSource;

    @Inject
    public LockService(ArtifactStore store, PomSource pomSource,
                       DescriptorSource descriptorSource) {
        this.store = store;
        this.pomSource = pomSource;
        this.descriptorSource = descriptorSource;
    }

    /** Resolves, downloads and (re)writes mj.lock; returns the closed graph. */
    public ResolvedGraph refresh(Path projectDir) {
        MjProject project;
        try {
            project = ProjectReader.parse(Files.readString(projectDir.resolve("mj.toml")));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read mj.toml in " + projectDir, e);
        }

        ResolvedGraph graph = Resolver.resolve(project, pomSource, descriptorSource);

        for (ResolvedGraph.ResolvedArtifact artifact : graph.artifacts()) {
            store.ensure(artifact.jarUrl(), ArtifactStore.Kind.JAR);
            if (artifact.metadataUrl() != null) {
                store.ensure(artifact.metadataUrl(), metadataKind(artifact));
            }
        }

        try {
            Files.writeString(projectDir.resolve("mj.lock"),
                    LockFile.serialize(graph, store::digestOf));
        } catch (IOException e) {
            throw new IllegalStateException("cannot write mj.lock in " + projectDir, e);
        }
        return graph;
    }

    private static ArtifactStore.Kind metadataKind(ResolvedGraph.ResolvedArtifact artifact) {
        return artifact.species() == Species.CENTRAL
                ? ArtifactStore.Kind.POM
                : ArtifactStore.Kind.DESCRIPTOR;
    }
}
