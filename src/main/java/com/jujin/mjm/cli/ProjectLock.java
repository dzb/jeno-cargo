package com.jujin.mjm.cli;

import com.jujin.mjm.store.ArtifactStore;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntSupplier;

/**
 * One lock per project, shared by the commands that write or delete
 * {@code target/}: {@code build}, {@code test} and {@code clean}.
 *
 * <p>They rewrite the same tree in place — a build fills {@code target/classes},
 * a test run deletes and refills {@code target/test-classes}, a clean removes
 * the whole directory. Without a lock, a second command pulls files out from
 * under a running fork (a lazily loaded class dies with
 * {@code NoClassDefFoundError}) or deletes output mid-build.
 *
 * <p>The lock file lives under {@code ~/.mjm/locks/}, never inside the project:
 * a lock inside {@code target/} is deleted by the very command that needs it,
 * and a new process would then lock a different inode — no one would be
 * waiting on anyone.
 */
final class ProjectLock {

    /** One queue per project for callers inside this JVM. */
    private static final ConcurrentHashMap<String, ReentrantLock> IN_PROCESS =
            new ConcurrentHashMap<>();

    /**
     * Where lock files live. The default comes from {@link com.jujin.mjm.store.Paths};
     * tests point it at a scratch directory so a suite run does not litter the
     * real home with one file per fixture project.
     */
    private static Path root = com.jujin.mjm.store.Paths.locks();

    private ProjectLock() {}

    /**
     * {@code ~/.mjm/locks/<sha256(real path)>.lock} — keyed by the canonical
     * path, so two symlinked routes into one project are one lock.
     */
    static Path lockFile(Path projectDir) {
        String key = canonical(projectDir).toString();
        return root.resolve(ArtifactStore.sha256(key.getBytes(StandardCharsets.UTF_8)) + ".lock");
    }

    /** Tests only: keep lock files in a scratch directory. */
    static void useRoot(Path lockRoot) {
        root = lockRoot.toAbsolutePath().normalize();
    }

    /** Back to the real locks root. */
    static void useUserRoot() {
        root = com.jujin.mjm.store.Paths.locks();
    }

    /** The project's real path, so aliases of one project share a lock. */
    static Path canonical(Path projectDir) {
        try {
            return projectDir.toRealPath();
        } catch (IOException e) {
            return projectDir.toAbsolutePath().normalize();
        }
    }

    /**
     * Runs {@code body} while holding the project's lock and returns its exit
     * code. The in-process queue comes first: two callers in one JVM cannot
     * wait on the same OS file lock (the JVM reports it as overlapping and
     * throws), while the file lock covers other processes.
     */
    static int with(Path projectDir, IntSupplier body) {
        Path lockPath = lockFile(projectDir);
        try {
            Files.createDirectories(lockPath.getParent());
        } catch (IOException e) {
            System.err.println("mjm: cannot create " + lockPath.getParent()
                    + " — " + e.getMessage());
            return 1;
        }
        ReentrantLock queue = IN_PROCESS.computeIfAbsent(
                canonical(projectDir).toString(), key -> new ReentrantLock());
        queue.lock();
        try (FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            // a sidecar, written once: `mjm cache prune` can then tell a dead
            // project's lock from a live one. Old empty files stay empty.
            if (channel.size() == 0) {
                String project = "project=" + canonical(projectDir) + "\n";
                channel.write(java.nio.ByteBuffer.wrap(
                        project.getBytes(StandardCharsets.UTF_8)));
            }
            return body.getAsInt();
        } catch (IOException e) {
            System.err.println("mjm: cannot lock " + lockPath + " — " + e.getMessage());
            return 1;
        } finally {
            queue.unlock();
        }
    }
}
