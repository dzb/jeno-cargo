package com.jujin.jeno.cli;

import com.jujin.jeno.project.DescriptorSource;
import com.jujin.jeno.project.JenoProject;
import com.jujin.jeno.pom.PomSource;
import com.jujin.jeno.resolve.ResolvedGraph;
import com.jujin.jeno.resolve.Resolver;
import com.jujin.jeno.resolve.Scope;
import com.jujin.jeno.store.LockFileReader;
import com.jujin.jeno.store.LockedArtifact;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The graph {@code tree} and {@code why} are allowed to show: the lockfile's
 * facts, with the manifest used only to restore the parent/child edges the flat
 * lock does not carry.
 *
 * <p>The lock is the authority because that is what {@code build}, {@code run}
 * and {@code package} actually consume; a tree drawn from a manifest that has
 * drifted would describe a closure nobody builds. So the lock is read first,
 * the manifest is resolved for edges, and the two are compared on every fact
 * the lock pins — id, version, source, depth and scope. Any difference is a
 * refusal with the differences named (at most ten), never a picture that might
 * be wrong.
 */
final class LockedGraphView {

    private static final int MAX_DIFFERENCES = 10;

    /** A lock that cannot answer: missing, unreadable, or a different graph. */
    static final class OutOfDateException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;
        OutOfDateException(String message) {
            super(message);
        }
    }

    private LockedGraphView() {}

    /**
     * Reads {@code jeno.lock}, resolves the manifest for edges, and returns the
     * resolved graph only when every locked fact agrees with it.
     */
    static ResolvedGraph graph(Path projectDir, JenoProject project,
                               PomSource pomSource, DescriptorSource descriptorSource) {
        Path lock = projectDir.resolve("jeno.lock");
        if (!Files.exists(lock)) {
            throw new OutOfDateException("no jeno.lock in " + projectDir + " — run jeno lock first");
        }
        List<LockedArtifact> locked;
        try {
            locked = LockFileReader.read(Files.readString(lock));
        } catch (IOException e) {
            throw new OutOfDateException("cannot read jeno.lock in " + projectDir);
        } catch (LegacyNames.LegacyException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new OutOfDateException(e.getMessage());
        }

        ResolvedGraph graph = Resolver.resolve(project, pomSource, descriptorSource);
        List<String> differences = compare(locked, graph);
        if (!differences.isEmpty()) {
            StringBuilder message = new StringBuilder("jeno.lock is out of date — run jeno lock");
            for (String line : differences) {
                message.append('\n').append(line);
            }
            throw new OutOfDateException(message.toString());
        }
        return graph;
    }

    /** Every pinned fact, in id order; at most {@link #MAX_DIFFERENCES} lines. */
    private static List<String> compare(List<LockedArtifact> locked, ResolvedGraph graph) {
        Map<String, LockedArtifact> rows = new LinkedHashMap<>();
        for (LockedArtifact artifact : locked) {
            rows.put(artifact.id(), artifact);
        }
        Map<String, ResolvedGraph.ResolvedArtifact> artifacts = new LinkedHashMap<>();
        for (ResolvedGraph.ResolvedArtifact artifact : graph.artifacts()) {
            artifacts.put(artifact.id(), artifact);
        }

        List<String> differences = new ArrayList<>();
        TreeSet<String> ids = new TreeSet<>(rows.keySet());
        ids.addAll(artifacts.keySet());
        for (String id : ids) {
            LockedArtifact row = rows.get(id);
            ResolvedGraph.ResolvedArtifact artifact = artifacts.get(id);
            if (row == null) {
                add(differences, "+ " + id + "@" + artifact.version() + " (not locked)");
                continue;
            }
            if (artifact == null) {
                add(differences, "- " + id + "@" + row.version()
                        + " (locked but not in the manifest graph)");
                continue;
            }
            if (!row.version().equals(artifact.version())) {
                add(differences, id + ": lock " + row.version() + ", manifest "
                        + artifact.version());
                continue;
            }
            if (!row.source().equals(artifact.source())) {
                add(differences, id + ": lock source " + row.source() + ", manifest "
                        + artifact.source());
            }
            if (row.scope() != artifact.scope()) {
                add(differences, id + ": lock scope " + scopeName(row.scope())
                        + ", manifest " + scopeName(artifact.scope()));
            }
            if (row.depth() != artifact.depth()) {
                add(differences, id + ": lock depth " + row.depth() + ", manifest "
                        + artifact.depth());
            }
            if (row.api() != artifact.api()) {
                add(differences, id + ": lock api " + row.api()
                        + ", manifest " + artifact.api());
            }
        }
        return differences;
    }

    private static String scopeName(Scope scope) {
        return scope.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static void add(List<String> differences, String line) {
        if (differences.size() < MAX_DIFFERENCES) {
            differences.add(line);
        }
    }
}
