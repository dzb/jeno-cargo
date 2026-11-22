package com.jujin.mjm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code mjm clean} — remove this project's build output.
 *
 * <pre>
 *   mjm clean                only target/ (the default)
 *   mjm clean --target       only target/ (the same, spelled out for scripts)
 *   mjm clean --dist         only dist/
 *   mjm clean --target --dist  both
 * </pre>
 *
 * <p>Cleaning a build means removing intermediates. {@code dist/} holds what
 * is about to ship — the release triple, a bundle — possibly for several
 * versions at once, so deleting it is a separate, explicit request
 * ({@code --dist}) and never a side effect of "clean".
 *
 * <p>Never touches the store. {@code ~/.mjm/store} is content-addressed and
 * shared across projects: deleting it would cost every project a re-download,
 * which is the opposite of what "clean my build" means. {@code mjm lock} is
 * the command that refreshes it, and it does so without destroying anything.
 *
 * <p>The split between the two directories is the one established earlier:
 * {@code target/} holds intermediates that {@code rm -rf} is safe on, and
 * {@code dist/} holds deliverables — which is why the default stops at
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
        return "[--target] [--dist]";
    }

    @Override
    public String summary() {
        return "remove build output (target/ by default; --dist also removes the "
                + "deliverables; the store is never touched)";
    }

    @Override
    public int execute(List<String> args) {
        // No flag means the safe default (target/). A flag states exactly what
        // to remove, and repeating it accumulates: --target --dist is both.
        boolean target = false;
        boolean dist = false;
        for (String arg : args) {
            switch (arg) {
                case "--target" -> target = true;
                case "--dist" -> dist = true;
                default -> {
                    System.err.println("mjm: unknown clean option '" + arg + "'"
                            + " — use --target or --dist");
                    return 2;
                }
            }
        }
        if (!target && !dist) {
            target = true;
        }

        boolean removedTarget = false;
        boolean removedSomething = false;
        if (target) {
            removedTarget = remove(projectDir.resolve("target"), "target/");
            removedSomething = removedTarget;
        }
        if (dist) {
            removedSomething |= remove(projectDir.resolve("dist"), "dist/");
        }

        if (!removedSomething) {
            System.out.println("nothing to clean — no build output in " + projectDir);
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
                System.out.println("removed " + label);
                return true;
            }
            if (isMountPoint(directory)) {
                throw new IllegalStateException("refusing to remove " + directory
                        + " — it is a mount point (a different file store from its parent); "
                        + "unmount it first, or remove its contents by hand");
            }
            DistOutput.clear(directory);
            System.out.println("removed " + label);
            return true;
        } catch (IOException e) {
            System.err.println("mjm: cannot remove " + directory + " — " + e.getMessage());
            throw new IllegalStateException(e);
        }
    }

    /**
     * A directory whose file store differs from its parent's is a mount point:
     * removing it would try to delete a whole mounted filesystem. That is never
     * what "clean my build" means, so the command refuses instead.
     */
    static boolean isMountPoint(Path directory) {
        try {
            Path parent = directory.toAbsolutePath().normalize().getParent();
            return parent != null
                    && !Files.getFileStore(directory).equals(Files.getFileStore(parent));
        } catch (IOException e) {
            return false;  // cannot tell: treat as an ordinary directory
        }
    }
}
