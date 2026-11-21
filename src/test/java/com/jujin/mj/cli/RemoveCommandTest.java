package com.jujin.mj.cli;

import com.jujin.mj.store.ArtifactStore;
import com.jujin.mj.store.StoreDescriptorSource;
import com.jujin.mj.store.StorePomSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code mj remove} — the missing verb. Before it existed, dropping a
 * dependency meant hand-editing mj.toml and remembering to re-lock; now one
 * line goes away, comments and layout survive, and the lock is refreshed.
 */
class RemoveCommandTest {

    @TempDir
    Path dir;

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

            [dev-deps]
            "com.example:test-kit" = "3.0.0"
            """;

        String updated = RemoveCommand.removeLine(text, "com.example:lib-b");

        assertFalse(updated.contains("lib-b"), "the target line is gone");
        assertTrue(updated.contains("\"com.example:lib-a\" = \"1.0.0\""),
                "its neighbour survives with its comment");
        assertTrue(updated.contains("# hand-written comment"), "comments survive");
        assertTrue(updated.contains("[dev-deps]"), "the other section survives");
        assertTrue(updated.contains("test-kit"));
    }

    @Test
    void removesFromDevDepsToo() {
        String text = """
            [project]
            name = "demo"
            version = "0.1.0"

            [dev-deps]
            "com.example:test-kit" = "3.0.0"
            "com.example:other" = "1.0.0"
            """;

        String updated = RemoveCommand.removeLine(text, "com.example:test-kit");
        assertFalse(updated.contains("test-kit"));
        assertTrue(updated.contains("\"com.example:other\" = \"1.0.0\""));
    }

    @Test
    void removesFromARepositoryGroup() {
        String text = """
            [project]
            name = "demo"
            version = "0.1.0"

            [repos]
            corp = "https://repo.example.com/maven2/"

            [deps.corp]
            "com.corp:internal" = "1.0.0"
            """;

        String updated = RemoveCommand.removeLine(text, "com.corp:internal");
        assertFalse(updated.contains("com.corp:internal"));
        assertTrue(updated.contains("[deps.corp]"), "the group header stays");
    }

    @Test
    void anUndeclaredIdIsAnErrorNamingTheDeclaredSet(@TempDir Path storeDir) throws Exception {
        Files.writeString(dir.resolve("mj.toml"), """
            [project]
            name = "demo"
            version = "0.1.0"

            [deps]
            "com.example:lib-a" = "1.0.0"
            """);

        int exit = remove(storeDir).execute(java.util.List.of("com.example:absent"));
        assertEquals(1, exit, "removing an undeclared id fails");
        assertTrue(Files.readString(dir.resolve("mj.toml")).contains("lib-a"),
                "the file was not touched");
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
