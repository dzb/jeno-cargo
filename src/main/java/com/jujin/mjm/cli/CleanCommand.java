package com.jujin.mjm.cli;

import java.io.IOException;
import java.nio.file.Files;
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

        boolean removedSomething = false;
        if (target) {
            removedSomething |= remove(projectDir.resolve("target"), "target/");
        }
        if (dist) {
            removedSomething |= remove(projectDir.resolve("dist"), "dist/");
        }

        if (!removedSomething) {
            System.out.println("nothing to clean — no build output in " + projectDir);
            return 0;
        }
        // If this process is running from target/classes, we just deleted the
        // directory it came from. Freeway's container shutdown reflects over
        // its beans and may try to re-read those class files, so the run ends
        // here with the exit code already decided.
        if (runningFrom(projectDir.resolve("target"))) {
            throw new ShutdownAfterBuildOutput(0);
        }
        return 0;
    }

    /** Whether this JVM's own classes live under {@code directory}. */
    private static boolean runningFrom(Path directory) {
        try {
            Path self = Path.of(Mjm.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).toAbsolutePath().normalize();
            return self.startsWith(directory.toAbsolutePath().normalize());
        } catch (Exception e) {
            return false;  // unknown location: assume not, and let shutdown run
        }
    }

    /**
     * Removes one directory tree. Returns whether anything existed, so the
     * command can say "nothing to clean" instead of pretending it worked.
     */
    private static boolean remove(Path directory, String label) {
        if (!Files.exists(directory)) {
            return false;
        }
        try {
            DistOutput.clear(directory);
            System.out.println("removed " + label);
            return true;
        } catch (IOException e) {
            System.err.println("mjm: cannot remove " + directory + " — " + e.getMessage());
            throw new IllegalStateException(e);
        }
    }
}
