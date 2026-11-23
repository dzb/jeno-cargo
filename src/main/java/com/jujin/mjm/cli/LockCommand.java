package com.jujin.mjm.cli;

import com.jujin.freeway.ioc.annotation.Inject;

import java.nio.file.Path;
import java.util.List;

/**
 * {@code mjm lock}: the shell around {@link LockService} — resolve, download,
 * pin (SPEC.md §4).
 */
public final class LockCommand implements Command {

    private final LockService lockService;
    private final Path projectDir;

    @Inject
    public LockCommand(LockService lockService, Path projectDir) {
        this.lockService = lockService;
        this.projectDir = projectDir;
    }

    @Override
    public String name() {
        return "lock";
    }

    @Override
    public String summary() {
        return "resolve dependencies, download into the store, and pin mjm.lock";
    }

    @Override
    public String usage() {
        return "[--check]";
    }

    @Override
    public int execute(List<String> args) {
        Integer workspace = WorkspaceRun.orchestrate(projectDir, args, "locked", false,
                (dir, rest) -> new LockCommand(lockService, dir).execute(rest));
        if (workspace != null) {
            return workspace;
        }
        boolean check = false;
        for (String arg : args) {
            if (arg.equals("--check")) {
                check = true;
            } else if (arg.equals("--refresh")) {
                System.err.println("mjm: --refresh was removed — mjm lock always re-resolves; "
                        + "use --check to only compare");
                return 2;
            } else {
                System.err.println("mjm: unknown lock option '" + arg + "' — use --check");
                return 2;
            }
        }
        // lock writes both files (or, with --check, reads the state it would
        // write), so it queues with add/remove/build/test/clean
        if (check) {
            return ProjectLock.with(projectDir, this::check);
        }
        return ProjectLock.with(projectDir, () -> {
            var graph = lockService.refresh(projectDir);
            System.out.println("locked " + graph.artifacts().size()
                    + " artifact(s) into mjm.lock");
            return 0;
        });
    }

    /**
     * Cargo's {@code --locked} question: would the current mjm.toml produce the
     * lock on disk, byte for byte? Nothing is written — {@code prepare} resolves,
     * downloads into the store and serializes in memory, and the bytes are
     * compared. This catches version drift, which build's "every declared id is
     * locked" check does not.
     */
    private int check() {
        String manifest;
        String onDisk;
        try {
            manifest = java.nio.file.Files.readString(projectDir.resolve("mjm.toml"));
        } catch (java.io.IOException e) {
            System.err.println("mjm: no mjm.toml in " + projectDir
                    + " — run mjm init to create one");
            return 1;
        }
        try {
            onDisk = java.nio.file.Files.readString(projectDir.resolve("mjm.lock"));
        } catch (java.io.IOException e) {
            System.err.println("mjm: no mjm.lock in " + projectDir + " — run mjm lock");
            return 1;
        }

        LockService.Prepared prepared;
        try {
            prepared = lockService.prepare(manifest);
        } catch (RuntimeException e) {
            System.err.println("mjm: " + e.getMessage());
            return 1;
        }

        if (prepared.lockText().equals(onDisk)) {
            System.out.println("mjm.lock is up to date ("
                    + prepared.graph().artifacts().size() + " artifacts)");
            return 0;
        }

        System.out.println("mjm.lock is out of date:");
        for (String line : difference(onDisk, prepared.lockText())) {
            System.out.println("  " + line);
        }
        System.out.println("run mjm lock");
        return 1;
    }

    /** Line-level difference, first ten changed lines: {@code -} disk, {@code +} regenerated. */
    static java.util.List<String> difference(String onDisk, String regenerated) {
        String[] oldLines = onDisk.split("\n", -1);
        String[] newLines = regenerated.split("\n", -1);
        java.util.List<String> lines = new java.util.ArrayList<>();
        int length = Math.max(oldLines.length, newLines.length);
        for (int i = 0; i < length && lines.size() < 10; i++) {
            String before = i < oldLines.length ? oldLines[i] : null;
            String after = i < newLines.length ? newLines[i] : null;
            if (java.util.Objects.equals(before, after)) {
                continue;
            }
            if (before != null) {
                lines.add("- " + before);
            }
            if (after != null) {
                lines.add("+ " + after);
            }
        }
        return lines;
    }
}
