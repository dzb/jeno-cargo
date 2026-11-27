package com.jujin.jeno.cli;

import com.jujin.jeno.resolve.ArtifactLocations;
import com.jujin.jeno.store.ArtifactStore;
import com.jujin.jeno.store.StoreDescriptorSource;
import com.jujin.jeno.store.StorePomSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code local:} repository used to put the checkout's absolute path into
 * jeno.lock: bytes differed between two clones, a cold store could not build a
 * fresh clone, and {@code --offline} missed the URL index after a move. The
 * lock now writes {@code file:vendor/…} relative to the project root, and every
 * reader expands it there.
 */
class RelativeLockTest {

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

    @TempDir
    Path storeDir;

    @AfterEach
    void resetProcessState() {
        ArtifactLocations.useProjectRoot(Path.of("").toAbsolutePath());
        resetOffline();
    }

    @Test
    void theSameProjectLocksToTheSameBytesInAnyDirectory() throws IOException {
        Path first = project(dir.resolve("first"));
        Path second = project(dir.resolve("second"));

        String lockFirst = lock(first, new ArtifactStore(dir.resolve("store-first")));
        String lockSecond = lock(second, new ArtifactStore(dir.resolve("store-second")));

        assertEquals(lockFirst, lockSecond, "the lock must not bind the checkout's path");
        assertTrue(lockFirst.contains(
                        "url = \"file:vendor/com/example/lib-a/1.0.0/lib-a-1.0.0.jar\""),
                "the local row is relative to the project, got:\n" + lockFirst);
        assertFalse(lockFirst.contains("file:///"), "nothing absolute, got:\n" + lockFirst);
    }

    @Test
    void aStoreWarmedInOneCheckoutAnswersOfflineInAnother() throws IOException {
        Path first = project(dir.resolve("first"));
        Path second = project(dir.resolve("second"));
        lock(first, new ArtifactStore(storeDir));

        // a fresh store instance, so only the on-disk URL index can answer
        ArtifactStore reused = new ArtifactStore(storeDir);
        ArtifactLocations.useProjectRoot(second);
        ArtifactStore.goOffline();

        assertDoesNotThrow(() -> new LockService(reused, new StorePomSource(reused),
                new StoreDescriptorSource(reused)).refresh(second),
                "the relative key hits the index the other checkout filled");
        assertEquals(Files.readString(first.resolve("jeno.lock")),
                Files.readString(second.resolve("jeno.lock")),
                "and the second checkout writes the same portable lock");
    }

    @Test
    void aLegacyAbsoluteLockStillBuildsAndReLocksToTheRelativeForm() throws IOException {
        Path project = project(dir.resolve("legacy"));
        String relative = lock(project, new ArtifactStore(storeDir));
        String legacy = relative.replaceAll("url = \"file:(.*?)\"",
                "url = \"file://" + project.toAbsolutePath() + "/$1\"");
        assertTrue(legacy.contains("file:///"), "the fixture is the old absolute form:\n" + legacy);
        Files.writeString(project.resolve("jeno.lock"), legacy);

        int exit = new BuildCommand(new ArtifactStore(storeDir), project).execute(List.of());
        assertEquals(0, exit, "an absolute file:// row is still read and built");

        String relocked = lock(project, new ArtifactStore(storeDir));
        assertFalse(relocked.contains("file:///"),
                "a rewrite emits the relative form, got:\n" + relocked);
        assertTrue(relocked.contains("file:vendor/"), relocked);
    }

    @Test
    void aRepositoryOutsideTheProjectStaysAbsolute() throws IOException {
        Path outside = Files.createDirectories(dir.resolve("outside-repo"));
        writeRepository(outside);  // the layout root IS the repository
        Path project = Files.createDirectories(dir.resolve("outside-project"));
        Files.writeString(project.resolve("jeno.toml"), manifest(outside.toString()));

        String lock = lock(project, new ArtifactStore(dir.resolve("store-outside")));

        assertTrue(lock.contains("url = \"file://" + outside),
                "a path outside the project has no relative form, got:\n" + lock);
        assertFalse(lock.contains("file:vendor"), lock);
    }

    @Test
    void aRelativeRowExpandsAgainstTheProjectRootNotTheWorkingDirectory() {
        Path root = dir.resolve("proj");

        assertEquals(root.resolve("vendor/x.jar").toUri().toString(),
                ArtifactLocations.expand("file:vendor/x.jar", root));
        assertEquals(Path.of("/opt/repo/x.jar").toUri().toString(),
                ArtifactLocations.expand("file:///opt/repo/x.jar", root),
                "an absolute row resolves as it is");
        assertEquals("https://example.com/x.jar",
                ArtifactLocations.expand("https://example.com/x.jar", root),
                "anything that is not file: is left alone");
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /** A project whose single dependency comes from a repository declared as {@code base}. */
    private Path project(Path projectDir) throws IOException {
        Files.createDirectories(projectDir);
        writeRepository(projectDir.resolve("vendor"));
        Files.writeString(projectDir.resolve("jeno.toml"), manifest("vendor"));
        Path main = projectDir.resolve("src/main/java/demo/Main.java");
        Files.createDirectories(main.getParent());
        Files.writeString(main, """
            package demo;

            public final class Main {
                public static void main(String[] args) {
                }
            }
            """);
        return projectDir;
    }

    private static String manifest(String base) {
        return """
            [project]
            name = "demo"
            version = "0.1.0"
            main = "demo.Main"

            [repos.vendor]
            type = "maven"
            url = "%s"

            [deps]
            "com.example:lib-a" = "vendor:1.0.0"
            """.formatted(base);
    }

    /** {@code <layout>/com/example/lib-a/1.0.0/} — a one-artifact Maven layout. */
    private static void writeRepository(Path layout) throws IOException {
        Path pom = layout.resolve("com/example/lib-a/1.0.0/lib-a-1.0.0.pom");
        Files.createDirectories(pom.getParent());
        Files.writeString(pom, """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>lib-a</artifactId>
              <version>1.0.0</version>
            </project>
            """);
        Path jar = pom.resolveSibling("lib-a-1.0.0.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
            out.write("Manifest-Version: 1.0\n".getBytes());
            out.closeEntry();
        }
    }

    private String lock(Path projectDir, ArtifactStore store) throws IOException {
        ArtifactLocations.useProjectRoot(projectDir);
        new LockService(store, new StorePomSource(store), new StoreDescriptorSource(store))
                .refresh(projectDir);
        return Files.readString(projectDir.resolve("jeno.lock"));
    }

    /** Offline is process-wide static state; the suite must not inherit it. */
    private static void resetOffline() {
        try {
            java.lang.reflect.Field field = ArtifactStore.class.getDeclaredField("offline");
            field.setAccessible(true);
            field.set(null, false);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot reset the offline flag", e);
        }
    }
}
