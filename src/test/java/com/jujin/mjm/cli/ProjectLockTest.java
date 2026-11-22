package com.jujin.mjm.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One lock per project for build/test/clean. It has to live outside the
 * project — a lock under target/ is deleted by the very command that needs it —
 * and be keyed by the real path so two routes into one project queue together.
 */
class ProjectLockTest {

    @TempDir
    Path dir;

    @org.junit.jupiter.api.BeforeEach
    void isolateProjectLocks() {
        ProjectLock.useRoot(dir.resolve(".locks"));
    }

    @org.junit.jupiter.api.AfterEach
    void restoreProjectLocks() {
        ProjectLock.useUserRoot();
    }

    @Test
    void theLockLivesOutsideTheProject() throws Exception {
        Path root = Files.createTempDirectory("mjm-locks-test");
        try {
            ProjectLock.useRoot(root);
            Path lock = ProjectLock.lockFile(dir);

            assertEquals(root, lock.getParent());
            assertFalse(lock.startsWith(dir.toAbsolutePath().normalize()),
                    "clean removes target/; the lock must not be inside the project");
            assertTrue(lock.getFileName().toString().endsWith(".lock"));
        } finally {
            Files.deleteIfExists(root);
        }
    }

    @Test
    void twoRoutesIntoOneProjectShareOneLock() throws IOException {
        Path real = Files.createDirectories(dir.resolve("real"));
        Path alias = Files.createSymbolicLink(dir.resolve("alias"), real);

        assertEquals(ProjectLock.lockFile(real), ProjectLock.lockFile(alias),
                "the key is the real path, so a symlinked route does not get its own lock");
    }

    @Test
    void aSecondCallerWaitsForTheFirst() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean secondRan = new AtomicBoolean();

        Thread holder = new Thread(() -> ProjectLock.with(dir, () -> {
            inside.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 0;
        }));
        holder.start();
        assertTrue(inside.await(10, TimeUnit.SECONDS), "the first caller holds the lock");

        Thread second = new Thread(() -> ProjectLock.with(dir, () -> {
            secondRan.set(true);
            return 0;
        }));
        second.start();
        Thread.sleep(300);
        assertFalse(secondRan.get(), "the second caller waits instead of running alongside");

        release.countDown();
        second.join(10_000);
        holder.join(10_000);
        assertTrue(secondRan.get(), "and runs once the lock is free");
    }
}
