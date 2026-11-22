package com.jujin.mjm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The mount points that sit at or inside a directory, read from
 * {@code /proc/self/mountinfo}.
 *
 * <p>Deleting a directory tree that contains a mount would delete the mounted
 * filesystem's contents — {@code Files.walk} descends into it. A
 * {@link java.nio.file.FileStore} comparison only catches a different
 * filesystem, so a bind mount of the same filesystem, or a mount one level
 * deeper, slips through. The kernel's own table has every mount point, so that
 * is what the guard reads.
 */
final class Mounts {

    private Mounts() {}

    /**
     * Mount points recorded in {@code mountinfo} that are {@code root} itself or
     * below it. A path that merely shares a prefix ({@code /tmp/ab} against
     * {@code /tmp/a}) is not a hit — paths are compared component by component.
     */
    static List<Path> inside(String mountInfo, Path root) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        List<Path> found = new ArrayList<>();
        for (String line : mountInfo.split("\n")) {
            String[] fields = line.strip().split(" ");
            if (fields.length < 5) {
                continue;
            }
            Path mount = Path.of(unescape(fields[4])).toAbsolutePath().normalize();
            if (mount.startsWith(normalizedRoot)) {
                found.add(mount);
            }
        }
        return found;
    }

    /**
     * The same query against the running kernel, or null when this system has no
     * {@code mountinfo} (not Linux) — the caller then falls back to a weaker
     * check instead of pretending it knows.
     */
    static List<Path> inside(Path root) {
        Path mountInfo = Path.of("/proc/self/mountinfo");
        if (!Files.isReadable(mountInfo)) {
            return null;
        }
        try {
            return inside(Files.readString(mountInfo), root);
        } catch (IOException e) {
            return null;
        }
    }

    /** mountinfo escapes space, tab, newline and backslash as octal. */
    static String unescape(String field) {
        return field.replace("\\040", " ")
                .replace("\\011", "\t")
                .replace("\\012", "\n")
                .replace("\\134", "\\");
    }
}
