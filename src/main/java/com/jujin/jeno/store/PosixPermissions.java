package com.jujin.jeno.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/** POSIX permissions are an optimization on Unix, not a requirement on Windows. */
public final class PosixPermissions {

    private PosixPermissions() {}

    /**
     * Applies POSIX permissions where the filesystem supports them. Windows
     * filesystems throw {@link UnsupportedOperationException}; the file bytes
     * and the command result are already valid there, so this is ignored.
     */
    public static void trySet(Path path, Set<PosixFilePermission> permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX filesystem: no permission bits to set
        }
    }
}
