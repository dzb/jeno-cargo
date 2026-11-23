package com.jujin.mjm.cli;

import com.jujin.freeway.commons.json.JsonArray;
import com.jujin.freeway.commons.json.JsonObject;
import com.jujin.freeway.commons.json.JsonUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@code mjm clean} — remove this project's build output.
 *
 * <pre>
 *   mjm clean                the whole target/ (the default)
 *   mjm clean --target       the same, spelled out for scripts
 *   mjm clean --dist         only target/dist/ (the deliverables; classes stay)
 *   mjm clean --target --dist  the whole target/ either way
 * </pre>
 *
 * <p>Cleaning a build means removing build output. {@code target/dist/} holds what
 * is about to ship — the release triple, a bundle — possibly for several
 * versions at once, so deleting it is a separate, explicit request
 * ({@code --dist}) and never a side effect of "clean".
 *
 * <p>Never touches the store. The store is content-addressed and
 * shared across projects: deleting it would cost every project a re-download,
 * which is the opposite of what "clean my build" means. {@code mjm lock} is
 * the command that refreshes it, and it does so without destroying anything.
 *
 * <p>The split between the two directories is the one established earlier:
 * {@code target/} holds intermediates that {@code rm -rf} is safe on, and
 * {@code target/dist/} holds deliverables — which is why {@code --dist} exists
 * {@code target/} and {@code --dist} has to be asked for.
 */
public final class CleanCommand implements Command {

    private final Path projectDir;

    public CleanCommand(Path projectDir) {
        this.projectDir = projectDir;
    }

    @Override
    public String name() {
        return "clean";
    }

    @Override
    public String usage() {
        return "[--target (default) | --dist]";
    }

    @Override
    public String summary() {
        return "remove build output (whole target/ by default; --dist removes only "
                + "target/dist and keeps classes; the store is never touched)";
    }

    @Override
    public Command.Category category() {
        return Command.Category.BUILD;
    }

    @Override
    public int order() {
        return 60;
    }

    @Override
    public CommandCapabilities capabilities() {
        return CommandCapabilities.builder()
                .json()
                .network(CommandCapabilities.NetworkMode.NEVER)
                .writes(CommandCapabilities.WriteScope.TARGET)
                .workspace(CommandCapabilities.WorkspaceMode.AGGREGATE)
                .destructive()
                .build();
    }

    @Override
    public int execute(List<String> args) {
        boolean json = args.contains("--json");
        List<String> cleaned = args.stream().filter(arg -> !arg.equals("--json")).toList();
        if (json) {
            return JsonWorkspace.run("clean", projectDir, cleaned, this::jsonModule);
        }
        return executeHuman(cleaned);
    }

    private JsonWorkspace.ModuleResult jsonModule(Path moduleDir, List<String> args) {
        boolean target = args.contains("--target");
        boolean dist = args.contains("--dist");
        if (!target && !dist) {
            target = true;
        }
        List<RemoveCandidate> candidates = new ArrayList<>();
        if (target) {
            candidates.add(candidate(moduleDir.resolve("target"), "target"));
        } else if (dist) {
            candidates.add(candidate(moduleDir.resolve("target/dist"), "dist"));
        }

        JsonCapture.Result captured = JsonCapture.run(() ->
                new CleanCommand(moduleDir).executeHuman(args));
        if (captured.exitCode() != 0) {
            return JsonWorkspace.ModuleResult.failed(captured.exitCode(),
                    captured.exitCode() == 2 ? "USAGE" : "CLEAN_FAILED", captured.message());
        }

        JsonArray removed = JsonUtils.array();
        for (RemoveCandidate candidate : candidates) {
            if (candidate.existed() && !Files.exists(candidate.path(), LinkOption.NOFOLLOW_LINKS)) {
                JsonObject entry = JsonUtils.object();
                entry.put("path", candidate.path().toAbsolutePath().normalize().toString());
                entry.put("kind", candidate.kind());
                entry.put("bytes", candidate.bytes());
                entry.put("fileCount", candidate.fileCount());
                removed.add(entry);
            }
        }
        JsonObject data = JsonUtils.object();
        data.put("removed", removed);
        data.put("removedCount", removed.size());
        data.put("nothingToClean", removed.isEmpty());
        return JsonWorkspace.ModuleResult.ok(data);
    }

    private static RemoveCandidate candidate(Path path, String kind) {
        boolean existed = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
        return new RemoveCandidate(path, kind, existed, existed ? treeSize(path) : 0,
                existed ? fileCount(path) : 0);
    }

    private int executeHuman(List<String> args) {
        Integer workspace = WorkspaceRun.orchestrate(projectDir, args, "cleaned", true,
                (dir, rest) -> new CleanCommand(dir).execute(rest));
        if (workspace != null) {
            return workspace;
        }
        // No flag means the safe default (target/). A flag states exactly what
        // to remove, and repeating it accumulates: --target --dist is both.
        boolean target = false;
        boolean dist = false;
        for (String arg : args) {
            switch (arg) {
                case "--target" -> target = true;
                case "--dist" -> dist = true;
                default -> {
                    System.err.println(Ui.errPrefix() + "unknown clean option '" + arg + "'"
                            + " — use --target or --dist");
                    return 2;
                }
            }
        }
        if (!target && !dist) {
            target = true;
        }

        boolean removeTarget = target;
        boolean removeDist = dist;
        // build and test write the same tree; clean deletes it. One lock per
        // project keeps them from acting on a half-deleted target/.
        return ProjectLock.with(projectDir, () -> removeOutputs(removeTarget, removeDist));
    }

    private int removeOutputs(boolean target, boolean dist) {
        List<Path> requested = new ArrayList<>();
        if (target) {
            requested.add(projectDir.resolve("target"));
        }
        if (dist) {
            requested.add(projectDir.resolve("target/dist"));
        }

        // Look before deleting: a mount anywhere in either requested tree
        // refuses the whole run. Deleting one tree and only then finding a
        // mount in the other would leave the user with half a clean.
        List<Path> mounts = new ArrayList<>();
        for (Path tree : requested) {
            if (!Files.exists(tree, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(tree)) {
                continue;  // nothing to scan; a link is removed itself
            }
            mounts.addAll(mountDetector.apply(tree));
        }
        if (!mounts.isEmpty()) {
            System.err.println(Ui.errPrefix() + "refusing to remove " + describe(requested)
                    + " — these paths are mount points: " + describe(mounts)
                    + "; unmount them first, or remove the contents by hand");
            return 1;
        }

        boolean removedTarget = false;
        boolean removedSomething = false;
        if (target) {
            // the whole build output, deliverables included
            removedTarget = remove(projectDir.resolve("target"), "target/");
            removedSomething = removedTarget;
        } else if (dist) {
            // only the deliverables: a rebuilt bundle does not need a recompile
            removedSomething = remove(projectDir.resolve("target/dist"), "target/dist/");
        }

        if (!removedSomething) {
            System.out.println(Ui.dim("nothing to clean — no build output in " + projectDir));
            return 0;
        }
        // Only removing target/ can delete this JVM's own classes, and only when
        // they were loaded from there. --dist alone cannot: the running classes
        // live under target/classes in every development layout.
        if (removedOwnClasses(removedTarget, projectDir.resolve("target"), selfLocation())) {
            throw new ShutdownAfterBuildOutput(0);
        }
        return 0;
    }

    /**
     * Whether the process must end before its container closes: the directory
     * this JVM loaded its classes from was just deleted (see
     * {@link ShutdownAfterBuildOutput}). Split out so the decision is testable
     * without moving the JVM.
     */
    static boolean removedOwnClasses(boolean removedTarget, Path targetDir, Path selfLocation) {
        return removedTarget && selfLocation != null
                && selfLocation.startsWith(targetDir.toAbsolutePath().normalize());
    }

    /** Where this JVM's own classes came from, or null when that cannot be read. */
    private static Path selfLocation() {
        try {
            return Path.of(Mjm.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;  // unknown location: assume not, and let shutdown run
        }
    }

    /**
     * Removes one entry: a directory tree, or a symbolic link (the link only —
     * never what it points at). Returns whether anything existed, so the
     * command can say "nothing to clean" instead of pretending it worked.
     *
     * <p>The existence check does not follow links: {@code dist -> target}
     * becomes a dangling link as soon as {@code target/} is removed, and a
     * following check would call it "absent" and leave it behind.
     */
    private static boolean remove(Path directory, String label) {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            if (Files.isSymbolicLink(directory)) {
                Files.delete(directory);
                System.out.println(Ui.ok("removed " + label));
                return true;
            }
            DistOutput.clear(directory);
            System.out.println(Ui.ok("removed " + label));
            return true;
        } catch (IOException e) {
            System.err.println(Ui.errPrefix() + "cannot remove " + directory + " — " + e.getMessage());
            throw new IllegalStateException(e);
        }
    }

    /** How a tree's mounts are found; replaced in tests, never at runtime. */
    private static java.util.function.Function<Path, List<Path>> mountDetector =
            CleanCommand::mountsIn;

    /** Tests only: pretend some trees contain mounts. */
    static void useMountDetector(java.util.function.Function<Path, List<Path>> detector) {
        mountDetector = detector;
    }

    /** Back to the kernel's own table (or the file-store fallback). */
    static void useKernelMountDetector() {
        mountDetector = CleanCommand::mountsIn;
    }

    /** Mount points at or inside a tree. */
    private static List<Path> mountsIn(Path directory) {
        List<Path> fromKernel = Mounts.inside(directory);
        if (fromKernel != null) {
            return fromKernel;
        }
        // No mountinfo (not Linux): compare each directory's file store with its
        // parent's, walking the whole tree. This catches a nested mount of a
        // different filesystem; it cannot catch a bind mount of the same one —
        // that form is described only by mountinfo, which this branch by
        // definition does not have. An incomplete guard, stated as such.
        return nestedFileStores(directory, parent ->
                fileStoreOf(directory, parent));
    }

    /**
     * Every directory under {@code root} whose store differs from its parent's.
     * {@code storeOf} is injectable so the fallback's walk is testable without
     * creating real mounts.
     */
    static List<Path> nestedFileStores(Path root, java.util.function.Function<Path, Object> storeOf) {
        List<Path> mounts = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path directory : walk.filter(Files::isDirectory).sorted().toList()) {
                Path parent = directory.getParent();
                if (parent == null || Files.isSymbolicLink(directory)) {
                    continue;
                }
                Object here = storeOf.apply(directory);
                Object up = storeOf.apply(parent);
                if (here != null && up != null && !here.equals(up)) {
                    mounts.add(directory);
                }
            }
        } catch (IOException e) {
            // cannot walk: deletion itself will fail with a readable message
        }
        return mounts;
    }

    /** The real fallback store lookup: each path's {@link java.nio.file.FileStore}. */
    private static Object fileStoreOf(Path ignored, Path path) {
        try {
            return Files.getFileStore(path);
        } catch (IOException e) {
            return null;
        }
    }

    /** {@code target/}, {@code target/dist/} — for messages. */
    private static String describe(List<Path> paths) {
        return paths.stream().map(Path::toString)
                .collect(java.util.stream.Collectors.joining(", "));
    }
    private static long treeSize(Path root) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        if (Files.isRegularFile(root, LinkOption.NOFOLLOW_LINKS)) {
            try {
                return Files.size(root);
            } catch (IOException e) {
                return 0;
            }
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

    private static long fileCount(Path root) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)
                || Files.isRegularFile(root, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(root)) {
            return 1;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private record RemoveCandidate(Path path, String kind, boolean existed,
                                   long bytes, long fileCount) {}

}
