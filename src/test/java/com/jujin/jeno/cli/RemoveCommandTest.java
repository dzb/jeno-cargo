package com.jujin.jeno.cli;

import com.jujin.jeno.store.ArtifactStore;
import com.jujin.jeno.store.StoreDescriptorSource;
import com.jujin.jeno.store.StorePomSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code jeno remove} — the missing verb. Before it existed, dropping a
 * dependency meant hand-editing jeno.toml and remembering to re-lock; now one
 * line goes away, comments and layout survive, and the lock is refreshed.
 */
class RemoveCommandTest {

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
    void removesExactlyOneLineAndKeepsTheRest() {
        String text = """
            # hand-written comment
            [project]
            name = "demo"
            version = "0.1.0"

            [deps]
            "com.example:lib-a" = "1.0.0"   # keep me
            "com.example:lib-b" = "2.0.0"

            [deps-dev]
            "com.example:test-kit" = "3.0.0"
            """;

        String updated = RemoveCommand.removeLine(text, "com.example:lib-b");

        assertFalse(updated.contains("lib-b"), "the target line is gone");
        assertTrue(updated.contains("\"com.example:lib-a\" = \"1.0.0\""),
                "its neighbour survives with its comment");
        assertTrue(updated.contains("# hand-written comment"), "comments survive");
        assertTrue(updated.contains("[deps-dev]"), "the other section survives");
        assertTrue(updated.contains("test-kit"));
    }

    @Test
    void removesTheDepsApiMarkerToo() {
        String text = """
            [project]
            name = "demo"
            version = "0.1.0"

            [deps]
            "com.example:lib-a" = "1.0.0"
            "com.example:lib-b" = "2.0.0"

            [deps-api]
            "com.example:lib-a" = "1.0.0"
            """;

        String updated = RemoveCommand.removeLine(text, "com.example:lib-a");

        assertFalse(updated.contains("lib-a"), updated);
        assertTrue(updated.contains("\"com.example:lib-b\" = \"2.0.0\""), updated);
        assertTrue(updated.contains("[deps-api]"), "the empty marker header may stay");
    }

    @Test
    void removesFromDevDepsToo() {
        String text = """
            [project]
            name = "demo"
            version = "0.1.0"

            [deps-dev]
            "com.example:test-kit" = "3.0.0"
            "com.example:other" = "1.0.0"
            """;

        String updated = RemoveCommand.removeLine(text, "com.example:test-kit");
        assertFalse(updated.contains("test-kit"));
        assertTrue(updated.contains("\"com.example:other\" = \"1.0.0\""));
    }

    @Test
    void anUndeclaredIdIsAnErrorNamingTheDeclaredSet(@TempDir Path storeDir) throws Exception {
        Files.writeString(dir.resolve("jeno.toml"), """
            [project]
            name = "demo"
            version = "0.1.0"

            [deps]
            "com.example:lib-a" = "1.0.0"
            """);

        int exit = remove(storeDir).execute(java.util.List.of("com.example:absent"));
        assertEquals(1, exit, "removing an undeclared id fails");
        assertTrue(Files.readString(dir.resolve("jeno.toml")).contains("lib-a"),
                "the file was not touched");
    }

    @Test
    void aFailedRefreshLeavesTheProjectByteForByte(@TempDir Path warmStore,
                                                   @TempDir Path emptyStore) throws Exception {
        vendorProject();
        LockService warm = new LockService(new ArtifactStore(warmStore),
                new StorePomSource(new ArtifactStore(warmStore)),
                new StoreDescriptorSource(new ArtifactStore(warmStore)));
        warm.refresh(dir);
        byte[] toml = Files.readAllBytes(dir.resolve("jeno.toml"));
        byte[] locked = Files.readAllBytes(dir.resolve("jeno.lock"));

        // lib-b disappears from the repository; the store passed to remove has
        // never seen it, so the re-lock must fail
        Files.delete(dir.resolve("vendor/com/example/lib-b/1.0.0/lib-b-1.0.0.pom"));
        Files.delete(dir.resolve("vendor/com/example/lib-b/1.0.0/lib-b-1.0.0.jar"));
        ArtifactStore empty = new ArtifactStore(emptyStore);

        int exit = new RemoveCommand(new LockService(empty, new StorePomSource(empty),
                new StoreDescriptorSource(empty)), dir).execute(List.of("com.example:lib-a"));

        assertEquals(1, exit, "the remaining dependency cannot be resolved");
        assertArrayEquals(toml, Files.readAllBytes(dir.resolve("jeno.toml")),
                "jeno.toml is byte-for-byte what it was");
        assertArrayEquals(locked, Files.readAllBytes(dir.resolve("jeno.lock")),
                "jeno.lock is byte-for-byte what it was");
    }

    /** Two dependencies from a local Maven-layout repository under {@code dir}. */
    private void vendorProject() throws Exception {
        for (String artifact : List.of("lib-a", "lib-b")) {
            Path pom = dir.resolve("vendor/com/example/" + artifact + "/1.0.0/"
                    + artifact + "-1.0.0.pom");
            Files.createDirectories(pom.getParent());
            Files.writeString(pom, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>%s</artifactId>
                  <version>1.0.0</version>
                </project>
                """.formatted(artifact));
            Files.write(pom.resolveSibling(artifact + "-1.0.0.jar"), artifact.getBytes());
        }
        Files.writeString(dir.resolve("jeno.toml"), """
            [project]
            name = "demo"
            version = "0.1.0"

            [repos.vendor]
            type = "maven"
            url = "%s"

            [deps]
            "com.example:lib-a" = "vendor:1.0.0"
            "com.example:lib-b" = "vendor:1.0.0"
            """.formatted(dir.resolve("vendor").toAbsolutePath()));
    }

    @Test
    void jsonRemoveReportsTheRemovedDependencyAndUpdatedLock(@TempDir Path storeDir) throws Exception {
        vendorProject();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        int exit;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            exit = remove(storeDir).execute(List.of("com.example:lib-a", "--json"));
        } finally {
            System.setOut(original);
        }

        assertEquals(0, exit);
        var json = com.jujin.freeway.commons.json.JsonUtils.parseObject(
                out.toString(StandardCharsets.UTF_8));
        assertEquals("remove", json.getString("command"));
        assertEquals("com.example:lib-a", json.getObject("removed").getString("id"));
        assertEquals(1, json.getInt("artifactCount"));
        assertEquals(true, json.getBoolean("lockUpdated"));
        assertFalse(Files.readString(dir.resolve("jeno.toml")).contains("lib-a"));
        assertTrue(Files.exists(dir.resolve("jeno.lock")));
    }

    @Test
    void theWrongArgumentCountIsRefused(@TempDir Path storeDir) {
        assertEquals(2, remove(storeDir).execute(java.util.List.of()));
        assertEquals(2, remove(storeDir).execute(java.util.List.of("a", "b")));
    }

    private RemoveCommand remove(Path storeDir) {
        ArtifactStore store = new ArtifactStore(storeDir);
        LockService lock = new LockService(store, new StorePomSource(store),
                new StoreDescriptorSource(store));
        return new RemoveCommand(lock, dir);
    }
}
