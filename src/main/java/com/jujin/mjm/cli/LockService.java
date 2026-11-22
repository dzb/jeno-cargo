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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

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

    /**
     * A resolution that is ready to be written: the graph, the manifest text it
     * came from, and the lock text. Producing it touches only the store, never
     * the project, so a caller can decide afterwards whether to commit.
     */
    public record Prepared(ResolvedGraph graph, String mjmToml, String lockText) {}

    /**
     * Parses {@code mjmTomlText}, resolves it and puts every byte in the store,
     * but writes nothing into the project. {@code add}/{@code remove} call this
     * with the edited manifest so a failure leaves the disk untouched.
     */
    public Prepared prepare(String mjmTomlText) {
        MjProject project = ProjectReader.parse(mjmTomlText);
        ResolvedGraph graph = Resolver.resolve(project, pomSource, descriptorSource);
        for (ResolvedGraph.ResolvedArtifact artifact : graph.artifacts()) {
            store.ensure(artifact.jarUrl(), ArtifactStore.Kind.JAR);
            if (artifact.metadataUrl() != null) {
                store.ensure(artifact.metadataUrl(), metadataKind(artifact));
            }
        }
        return new Prepared(graph, mjmTomlText, LockFile.serialize(graph, store::digestOf));
    }

    /**
     * Writes the manifest, then the lock. If the lock cannot be written the old
     * manifest bytes go back, so the project never keeps a declaration whose
     * lock was never produced.
     */
    public void commit(Path projectDir, Prepared prepared) {
        Path manifest = projectDir.resolve("mjm.toml");
        String previous = null;
        try {
            previous = Files.readString(manifest);
        } catch (IOException ignored) {
            // no manifest yet (mjm init): nothing to restore
        }
        try {
            writeAtomically(manifest, prepared.mjmToml());
        } catch (RuntimeException e) {
            restore(manifest, previous);
            throw e;
        }
        try {
            writeAtomically(projectDir.resolve("mjm.lock"), prepared.lockText());
        } catch (RuntimeException e) {
            restore(manifest, previous);
            throw e;
        }
    }

    /** The lock command's flow: read the manifest, prepare, commit. */
    public ResolvedGraph refresh(Path projectDir) {
        String text;
        try {
            text = Files.readString(projectDir.resolve("mjm.toml"));
        } catch (IOException e) {
            throw new IllegalStateException("no mjm.toml in " + projectDir
                    + " — run mjm init to create one", e);
        }
        Prepared prepared = prepare(text);
        commit(projectDir, prepared);
        return prepared.graph();
    }

    /**
     * Writes beside the target and renames over it, so a reader sees the old
     * file or the new one and never a half-written manifest. Falls back to a
     * plain replace where atomic moves are unsupported (the same shape
     * {@code ArtifactStore} uses for its store files).
     */
    private static void writeAtomically(Path target, String text) {
        Path temp = null;
        try {
            temp = Files.createTempFile(target.getParent(),
                    target.getFileName().toString() + ".", ".tmp");
            Files.writeString(temp, text);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            temp = null;
        } catch (IOException e) {
            throw new IllegalStateException("cannot write " + target
                    + " — check the directory is writable", e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // the temp file is already useless; a leftover is noise, not a fault
                }
            }
        }
    }

    private static void restore(Path manifest, String previous) {
        if (previous == null) {
            return;
        }
        try {
            writeAtomically(manifest, previous);
        } catch (RuntimeException e) {
            throw new IllegalStateException("cannot restore " + manifest
                    + " after a failed write — the declaration may be unbacked; "
                    + "restore it by hand", e);
        }
    }

    private static ArtifactStore.Kind metadataKind(ResolvedGraph.ResolvedArtifact artifact) {
        return artifact.species() == Species.CENTRAL
                ? ArtifactStore.Kind.POM
                : ArtifactStore.Kind.DESCRIPTOR;
    }
}
