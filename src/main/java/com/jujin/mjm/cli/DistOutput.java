package com.jujin.mjm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * One place that removes a command's previous output for this project and
 * version, so a rerun never leaves a stale artifact beside a fresh one.
 *
 * <p>{@code dist/} accumulates by design — it holds deliverables, not
 * intermediates — which means an old jar from a different command, or a
 * directory from a previous bundle of the same version, survives a rebuild
 * unless the writer clears it. A stale jar looks like a current one: same
 * name, different bytes.
 *
 * <p>Two callers, two intents. A writer clears only the paths its own run and
 * version own — a single artifact file ({@code package}, and the descriptor and
 * checksum of {@code publish}), or this version's bundle directory plus its
 * tar.gz ({@code bundle}). {@code mjm clean --dist} calls it on {@code dist/}
 * itself, because the user asked for the whole directory to go. Writers must
 * keep their own paths precise; removing the directory is a decision only a
 * human makes.
 */
final class DistOutput {

    private DistOutput() {}

    /** Deletes {@code target} recursively; a missing path is fine. */
    static void clear(Path target) throws IOException {
        if (!Files.exists(target)) {
            return;
        }
        if (Files.isRegularFile(target)) {
            Files.delete(target);
            return;
        }
        try (Stream<Path> walk = Files.walk(target)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /** Ensures {@code dist/} exists and returns it. */
    static Path dist(Path projectDir) throws IOException {
        Path dist = projectDir.resolve("dist");
        Files.createDirectories(dist);
        return dist;
    }
}
