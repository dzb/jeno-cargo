package com.jujin.mjm.cli;

import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.mjm.pom.PomSource;
import com.jujin.mjm.project.DescriptorSource;
import com.jujin.mjm.project.MjProject;
import com.jujin.mjm.project.ProjectReader;
import com.jujin.mjm.project.Species;
import com.jujin.mjm.resolve.LockFile;
import com.jujin.mjm.resolve.Resolver;
import com.jujin.mjm.resolve.ResolvedGraph;
import com.jujin.mjm.store.ArtifactStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The lock flow shared by {@code mjm lock} and {@code mjm add}: resolve the
 * graph, download every byte into the store, pin the digests into mjm.lock
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

    /** Resolves, downloads and (re)writes mjm.lock; returns the closed graph. */
    public ResolvedGraph refresh(Path projectDir) {
        MjProject project;
        try {
            project = ProjectReader.parse(Files.readString(projectDir.resolve("mjm.toml")));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read mjm.toml in " + projectDir, e);
        }

        ResolvedGraph graph = Resolver.resolve(project, pomSource, descriptorSource);

        for (ResolvedGraph.ResolvedArtifact artifact : graph.artifacts()) {
            store.ensure(artifact.jarUrl(), ArtifactStore.Kind.JAR);
            if (artifact.metadataUrl() != null) {
                store.ensure(artifact.metadataUrl(), metadataKind(artifact));
            }
        }

        try {
            Files.writeString(projectDir.resolve("mjm.lock"),
                    LockFile.serialize(graph, store::digestOf));
        } catch (IOException e) {
            throw new IllegalStateException("cannot write mjm.lock in " + projectDir, e);
        }
        return graph;
    }

    private static ArtifactStore.Kind metadataKind(ResolvedGraph.ResolvedArtifact artifact) {
        return artifact.species() == Species.CENTRAL
                ? ArtifactStore.Kind.POM
                : ArtifactStore.Kind.DESCRIPTOR;
    }
}
