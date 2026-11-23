package com.jujin.mjm.cli;

import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.mjm.store.Paths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@code mjm cache} — what the user-level directories hold, and how to reclaim
 * them.
 *
 * <pre>
 *   mjm cache dir     the three roots (store, sources, locks)
 *   mjm cache size    how much each one takes
 *   mjm cache prune   only what is provably dead: lock files whose project is
 *                     gone, interrupted {@code part-*.tmp} downloads, and index
 *                     entries whose artifact is missing
 *   mjm cache purge   remove store and sources outright (alias: clean)
 * </pre>
 *
 * <p>Everything here is local: no command in this group touches the network,
 * the project tree, {@code mjm.lock}, or the lock files of live projects.
 */
public final class CacheCommand implements Command {

    @Inject
    public CacheCommand() {
    }

    @Override
    public String name() {
        return "cache";
    }

    @Override
    public String usage() {
        return "dir | size | prune | purge";
    }

    @Override
    public String summary() {
        return "inspect or reclaim the user-level store, sources and locks";
    }

    @Override
    public int execute(List<String> args) {
        if (args.size() != 1) {
            System.err.println("mjm: cache needs one subcommand — " + usage());
            return 2;
        }
        return switch (args.getFirst()) {
            case "dir" -> dir();
            case "size" -> size();
            case "prune" -> prune();
            case "purge", "clean" -> purge();  // clean is the compatibility alias
            default -> {
                System.err.println("mjm: unknown cache subcommand '" + args.getFirst()
                        + "' — " + usage());
                yield 2;
            }
        };
    }

    private int dir() {
        System.out.println("store   " + Paths.store());
        System.out.println("sources " + Paths.sources());
        System.out.println("locks   " + Paths.locks());
        return 0;
    }

    private int size() {
        long store = bytes(Paths.store());
        long sources = bytes(Paths.sources());
        long locks = bytes(Paths.locks());
        System.out.println("store   " + human(store));
        System.out.println("sources " + human(sources));
        System.out.println("locks   " + human(locks));
        System.out.println("total   " + human(store + sources + locks));
        return 0;
    }

    /**
     * Removes only the three provably dead things. A lock file without a
     * {@code project=} line predates the sidecar and is kept — "unknown" is not
     * "dead".
     */
    private int prune() {
        try {
            if (Files.isDirectory(Paths.locks())) {
                try (Stream<Path> locks = Files.list(Paths.locks())) {
                    for (Path lock : locks.filter(path -> path.toString().endsWith(".lock"))
                            .toList()) {
                        if (projectGone(lock)) {
                            Files.deleteIfExists(lock);
                        }
                    }
                }
            }
            Path store = Paths.store();
            if (Files.isDirectory(store)) {
                try (Stream<Path> entries = Files.list(store)) {
                    for (Path entry : entries
                            .filter(path -> path.getFileName().toString().startsWith("part-"))
                            .filter(path -> path.getFileName().toString().endsWith(".tmp"))
                            .toList()) {
                        Files.deleteIfExists(entry);
                    }
                }
                Path index = store.resolve("index");
                if (Files.isDirectory(index)) {
                    try (Stream<Path> entries = Files.list(index)) {
                        for (Path entry : entries
                                .filter(path -> path.toString().endsWith(".url")).toList()) {
                            if (danglingIndexEntry(entry, store)) {
                                Files.deleteIfExists(entry);
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("mjm: cannot prune " + e.getMessage());
            return 1;
        }
        return 0;
    }

    /** A lock file's project directory no longer exists. Empty/unknown: keep. */
    private static boolean projectGone(Path lock) throws IOException {
        String text = Files.readString(lock, StandardCharsets.UTF_8).strip();
        String marker = "project=";
        int at = text.indexOf(marker);
        if (at < 0) {
            return false;  // a lock written before the sidecar: unknown, keep
        }
        String project = text.substring(at + marker.length()).strip();
        return !project.isEmpty() && !Files.exists(Path.of(project));
    }

    /** The index names a digest whose artifact is no longer in the store. */
    private static boolean danglingIndexEntry(Path entry, Path store) throws IOException {
        String digest = Files.readString(entry, StandardCharsets.US_ASCII).strip();
        if (!digest.matches("[0-9a-f]{64}")) {
            return false;  // corrupt entries are ignored by the store too
        }
        try (Stream<Path> files = Files.list(store)) {
            return files.noneMatch(path -> path.getFileName().toString()
                    .startsWith(digest + "."));
        }
    }

    /** Deletes store and sources; locks, the project and mjm.lock stay. */
    private int purge() {
        long freed = 0;
        StringBuilder report = new StringBuilder();
        for (Path root : List.of(Paths.store(), Paths.sources())) {
            long size = bytes(root);
            try {
                deleteTree(root);
            } catch (IOException e) {
                System.err.println("mjm: cannot remove " + root + " — " + e.getMessage());
                return 1;
            }
            if (size > 0) {
                freed += size;
                report.append("\nfreed ").append(human(size)).append(" from ").append(root);
            }
        }
        if (report.isEmpty()) {
            System.out.println("nothing to purge");
            return 0;
        }
        System.out.println(report.toString().strip());
        return 0;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        if (Files.isSymbolicLink(root) || Files.isRegularFile(root)) {
            Files.delete(root);
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static long bytes(Path root) {
        if (!Files.exists(root)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException e) {
            return 0;
        }
    }

    /** KiB/MiB/GiB, one decimal: sizes are read by people, not parsed. */
    static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        for (String unit : units) {
            value /= 1024;
            if (value < 1024) {
                return String.format(java.util.Locale.ROOT, "%.1f %s", value, unit);
            }
        }
        return String.format(java.util.Locale.ROOT, "%.1f PiB", value / 1024);
    }
}
