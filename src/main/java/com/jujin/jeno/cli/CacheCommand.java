package com.jujin.jeno.cli;

import com.jujin.freeway.commons.json.JsonArray;
import com.jujin.freeway.commons.json.JsonObject;
import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.jeno.store.Paths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@code jeno cache} — what the user-level directories hold, and how to reclaim
 * them.
 *
 * <pre>
 *   jeno cache dir     the three roots (store, sources, locks)
 *   jeno cache size    how much each one takes
 *   jeno cache prune   only what is provably dead: lock files whose project is
 *                     gone, interrupted {@code part-*.tmp} downloads, and index
 *                     entries whose artifact is missing
 *   jeno cache purge [--dry-run] [--yes]
 *                     remove store and sources outright (alias: clean).
 *                     Destructive: it refuses without --yes; --dry-run prints
 *                     what would go and deletes nothing.
 * </pre>
 *
 * <p>Everything here is local: no command in this group touches the network,
 * the project tree, {@code jeno.lock}, or the lock files of live projects.
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
        return "dir | size | prune | purge [--dry-run] [--yes] [--json]";
    }

    @Override
    public String summary() {
        return "inspect or reclaim the user-level store, sources and locks";
    }

    @Override
    public Command.Category category() {
        return Command.Category.MAINTAIN;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public CommandCapabilities capabilities() {
        return CommandCapabilities.builder()
                .json()
                .network(CommandCapabilities.NetworkMode.NEVER)
                .writes(CommandCapabilities.WriteScope.CACHE,
                        CommandCapabilities.WriteScope.SOURCES,
                        CommandCapabilities.WriteScope.LOCKS)
                .workspace(CommandCapabilities.WorkspaceMode.GLOBAL)
                .dryRun()
                .destructive()
                .confirmation(CommandCapabilities.Confirmation.CONDITIONAL)
                .build();
    }

    @Override
    public int execute(List<String> args) {
        if (args.isEmpty()) {
            System.err.println(Ui.errPrefix() + "cache needs one subcommand — " + usage());
            return 2;
        }
        String subcommand = args.getFirst();
        List<String> rest = args.subList(1, args.size());
        boolean json = rest.contains("--json");

        if (subcommand.equals("purge") || subcommand.equals("clean")) {
            boolean dryRun = false;
            boolean yes = false;
            for (String flag : rest) {
                switch (flag) {
                    case "--json" -> { }
                    case "--dry-run" -> dryRun = true;
                    case "--yes" -> yes = true;
                    default -> {
                        String message = "cache " + subcommand + " takes --dry-run, --yes and --json only"
                                + " — got '" + flag + "'";
                        if (json) {
                            JsonResult.error("cache", 2, "USAGE", message).emit(2);
                        } else {
                            System.err.println(Ui.errPrefix() + message);
                        }
                        return 2;
                    }
                }
            }
            return purge(subcommand, dryRun, yes, json);
        }

        for (String flag : rest) {
            if (!flag.equals("--json")) {
                String message = "cache " + subcommand + " takes no arguments — got '" + flag + "'";
                if (json) {
                    JsonResult.error("cache", 2, "USAGE", message).emit(2);
                } else {
                    System.err.println(Ui.errPrefix() + message);
                }
                return 2;
            }
        }
        return switch (subcommand) {
            case "dir" -> dir(json);
            case "size" -> size(json);
            case "prune" -> prune(json);
            default -> {
                String message = "unknown cache subcommand '" + subcommand + "' — " + usage();
                if (json) {
                    JsonResult.error("cache", 2, "USAGE", message).emit(2);
                } else {
                    System.err.println(Ui.errPrefix() + message);
                }
                yield 2;
            }
        };
    }

    private int dir(boolean json) {
        if (json) {
            JsonObject paths = JsonUtils.object();
            paths.put("store", Paths.store().toAbsolutePath().normalize().toString());
            paths.put("sources", Paths.sources().toAbsolutePath().normalize().toString());
            paths.put("locks", Paths.locks().toAbsolutePath().normalize().toString());
            JsonResult.start("cache")
                    .put("subcommand", "dir")
                    .put("paths", paths)
                    .emit(0);
            return 0;
        }
        if (Ui.enabled()) {
            System.out.println(Ui.heading("jeno local paths"));
        }
        System.out.println(Ui.dim("store   ") + Paths.store());
        System.out.println(Ui.dim("sources ") + Paths.sources());
        System.out.println(Ui.dim("locks   ") + Paths.locks());
        return 0;
    }

    private int size(boolean json) {
        long store = bytes(Paths.store());
        long sources = bytes(Paths.sources());
        long locks = bytes(Paths.locks());
        long total = store + sources + locks;
        if (json) {
            JsonObject sizes = JsonUtils.object();
            sizes.put("store", sizeJson(store));
            sizes.put("sources", sizeJson(sources));
            sizes.put("locks", sizeJson(locks));
            sizes.put("total", sizeJson(total));
            JsonResult.start("cache")
                    .put("subcommand", "size")
                    .put("sizes", sizes)
                    .emit(0);
            return 0;
        }
        if (Ui.enabled()) {
            System.out.println(Ui.heading("jeno local sizes"));
        }
        System.out.println(Ui.dim("store   ") + human(store));
        System.out.println(Ui.dim("sources ") + human(sources));
        System.out.println(Ui.dim("locks   ") + human(locks));
        System.out.println(Ui.dim("total   ") + Ui.strong(human(total)));
        return 0;
    }

    private static JsonObject sizeJson(long bytes) {
        JsonObject result = JsonUtils.object();
        result.put("bytes", bytes);
        result.put("human", human(bytes));
        return result;
    }

    /**
     * Removes only the three provably dead things. A lock file without a
     * {@code project=} line predates the sidecar and is kept — "unknown" is not
     * "dead".
     */
    private int prune(boolean json) {
        int removedFiles = 0;
        long reclaimed = 0;
        try {
            if (Files.isDirectory(Paths.locks())) {
                try (Stream<Path> locks = Files.list(Paths.locks())) {
                    for (Path lock : locks.filter(path -> path.toString().endsWith(".lock"))
                            .toList()) {
                        if (projectGone(lock)) {
                            reclaimed += sizeOf(lock);
                            if (Files.deleteIfExists(lock)) {
                                removedFiles++;
                            }
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
                        reclaimed += sizeOf(entry);
                        if (Files.deleteIfExists(entry)) {
                            removedFiles++;
                        }
                    }
                }
                Path index = store.resolve("index");
                if (Files.isDirectory(index)) {
                    try (Stream<Path> entries = Files.list(index)) {
                        for (Path entry : entries
                                .filter(path -> path.toString().endsWith(".url")).toList()) {
                            if (danglingIndexEntry(entry, store)) {
                                reclaimed += sizeOf(entry);
                                if (Files.deleteIfExists(entry)) {
                                    removedFiles++;
                                }
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            if (json) {
                JsonResult.error("cache", 1, "PRUNE_FAILED",
                        "cannot prune " + e.getMessage()).emit(1);
            } else {
                System.err.println(Ui.errPrefix() + "cannot prune " + e.getMessage());
            }
            return 1;
        }
        if (json) {
            JsonResult.start("cache")
                    .put("subcommand", "prune")
                    .put("removedFiles", removedFiles)
                    .put("reclaimedBytes", reclaimed)
                    .put("reclaimed", human(reclaimed))
                    .emit(0);
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

    /**
     * Deletes store and sources; locks, the project and jeno.lock stay.
     * Destructive, so it needs --yes; --dry-run only reports what would go.
     */
    private int purge(String subcommand, boolean dryRun, boolean yes, boolean json) {
        List<Path> roots = List.of(Paths.store(), Paths.sources());
        long freed = 0;
        StringBuilder report = new StringBuilder();
        if (dryRun || !yes) {
            if (json) {
                JsonArray rootArray = JsonUtils.array();
                for (Path root : roots) {
                    long size = bytes(root);
                    JsonObject entry = JsonUtils.object();
                    entry.put("path", root.toAbsolutePath().normalize().toString());
                    entry.put("bytes", size);
                    entry.put("human", human(size));
                    rootArray.add(entry);
                }
                JsonResult result = JsonResult.start("cache")
                        .put("subcommand", subcommand)
                        .put("dryRun", dryRun)
                        .put("requiresYes", !yes)
                        .put("roots", rootArray);
                if (!dryRun) {
                    result.diagnostic("error", "CONFIRMATION_REQUIRED",
                            "cache purge needs --yes",
                            List.of(List.of("jeno", "cache", "purge", "--yes")));
                    result.emit(2);
                    return 2;
                }
                result.emit(0);
                return 0;
            }
            for (Path root : roots) {
                long size = bytes(root);
                System.out.println((dryRun ? "would purge " : "purge ") + root
                        + (size > 0 ? " (" + human(size) + ")" : " (empty)"));
            }
            if (!dryRun) {
                System.out.println("pass --yes to delete (or --dry-run to only look)");
                return 2;
            }
            return 0;
        }

        JsonArray removed = JsonUtils.array();
        for (Path root : roots) {
            long size = bytes(root);
            try {
                deleteTree(root);
            } catch (IOException e) {
                if (json) {
                    JsonResult.error("cache", 1, "PURGE_FAILED",
                            "cannot remove " + root + " — " + e.getMessage()).emit(1);
                } else {
                    System.err.println(Ui.errPrefix() + "cannot remove " + root + " — " + e.getMessage());
                }
                return 1;
            }
            if (size > 0) {
                freed += size;
                if (json) {
                    JsonObject entry = JsonUtils.object();
                    entry.put("path", root.toAbsolutePath().normalize().toString());
                    entry.put("bytes", size);
                    entry.put("human", human(size));
                    removed.add(entry);
                } else {
                    report.append("\nfreed ").append(human(size)).append(" from ").append(root);
                }
            }
        }
        if (json) {
            JsonResult.start("cache")
                    .put("subcommand", subcommand)
                    .put("dryRun", false)
                    .put("requiresYes", false)
                    .put("freedBytes", freed)
                    .put("freed", human(freed))
                    .put("removed", removed)
                    .emit(0);
            return 0;
        }
        if (report.isEmpty()) {
            System.out.println(Ui.dim("nothing to purge"));
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

    private static long sizeOf(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.size(path) : 0;
        } catch (IOException e) {
            return 0;
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
