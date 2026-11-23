package com.jujin.jeno.cli;

import java.nio.file.Files;
import java.nio.file.Path;

/** The shared entry point for project file names and old-name migration checks. */
final class ProjectFiles {

    static final String MANIFEST = "jeno.toml";
    static final String LOCK = "jeno.lock";

    private ProjectFiles() {}

    static Path manifest(Path dir) {
        return dir.resolve(MANIFEST);
    }

    static Path lock(Path dir) {
        return dir.resolve(LOCK);
    }

    static boolean hasManifest(Path dir) {
        return Files.isRegularFile(manifest(dir));
    }

    static boolean hasLock(Path dir) {
        return Files.isRegularFile(lock(dir));
    }

    /** Null when both current files are usable, else the one migration message to show. */
    static String legacyProblem(Path dir) {
        if (!hasManifest(dir) && Files.isRegularFile(dir.resolve(LegacyNames.MANIFEST))) {
            return LegacyNames.manifest(dir);
        }
        if (!hasLock(dir) && Files.isRegularFile(dir.resolve(LegacyNames.LOCK))) {
            return LegacyNames.lock(dir);
        }
        return null;
    }

    /** Prints the migration error and returns true when an old project file is present. */
    static boolean rejectLegacy(Path dir) {
        String problem = legacyProblem(dir);
        if (problem == null) {
            return false;
        }
        System.err.println(Ui.errPrefix() + problem);
        return true;
    }
}
