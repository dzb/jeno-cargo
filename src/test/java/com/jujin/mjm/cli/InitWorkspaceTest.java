package com.jujin.mjm.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code mjm init --ws} and the {@code mjm.ws.toml} reader. */
class InitWorkspaceTest {

    @TempDir
    Path dir;

    @Test
    void theScanFindsModulesTwoLevelsDownAndSkipsBuildOutput() throws Exception {
        module("libs/core");
        module("apps/web");
        module("target/ignored");
        module(".hidden/ignored");

        assertEquals(0, run(dir, "--ws"), errors());

        var workspace = Workspaces.read(dir).orElseThrow();
        assertEquals(List.of(dir.resolve("apps/web").toRealPath(),
                        dir.resolve("libs/core").toRealPath()),
                workspace.modules(), "sorted, build output skipped");
        String text = Files.readString(dir.resolve("mjm.ws.toml"));
        assertTrue(text.contains("modules = [\"apps/web\", \"libs/core\"]"), text);
    }

    @Test
    void explicitPathsAreWrittenAndABadOneBlocksTheFile() throws Exception {
        module("libs/core");
        module("apps/web");

        assertEquals(0, run(dir, "--ws", "apps/web", "libs/core"), errors());
        assertTrue(Files.readString(dir.resolve("mjm.ws.toml"))
                .contains("[\"apps/web\", \"libs/core\"]"), "kept free of topological guessing");

        Files.delete(dir.resolve("mjm.ws.toml"));
        Files.createDirectories(dir.resolve("empty/dir"));

        assertEquals(1, run(dir, "--ws", "apps/web", "empty/dir"), errors());
        assertFalse(Files.exists(dir.resolve("mjm.ws.toml")),
                "a bad path writes nothing at all");
    }

    @Test
    void aDirectoryWithManifestCannotBeTheWorkspaceRoot() throws Exception {
        module("");

        assertEquals(1, run(dir, "--ws"), errors());

        assertFalse(Files.exists(dir.resolve("mjm.ws.toml")));
    }

    @Test
    void anExistingFileNeedsForce() throws Exception {
        module("libs/core");
        Files.writeString(dir.resolve("mjm.ws.toml"), "# stale\n[ws]\nmodules = [\"old\"]\n");

        assertEquals(1, run(dir, "--ws"), errors());
        assertTrue(Files.readString(dir.resolve("mjm.ws.toml")).contains("old"),
                "without --force the file is untouched");

        assertEquals(0, run(dir, "--ws", "--force"), errors());
        assertFalse(Files.readString(dir.resolve("mjm.ws.toml")).contains("old"));
        assertEquals(1, Workspaces.read(dir).orElseThrow().modules().size());
    }

    @Test
    void aScanThatFindsNothingWritesNothing() throws Exception {
        Files.createDirectories(dir.resolve("src/main/java"));

        assertEquals(1, run(dir, "--ws"), errors());
        assertFalse(Files.exists(dir.resolve("mjm.ws.toml")));
    }

    @Test
    void theReaderWalksUpAndValidatesWhatItFinds(@TempDir Path elsewhere) throws Exception {
        module("libs/core");
        assertEquals(0, run(dir, "--ws", "libs/core"), errors());
        Path deep = Files.createDirectories(dir.resolve("libs/core/src/main/java"));
        assertEquals(dir.resolve("libs/core").toRealPath(),
                Workspaces.read(deep).orElseThrow().modules().get(0),
                "the nearest file is used, from any directory below it");

        Path bare = Files.createDirectories(elsewhere.resolve("deep"));
        assertTrue(Workspaces.read(bare).isEmpty(), "no file means no workspace");

        // duplicate: two spellings of one directory
        Files.writeString(dir.resolve("mjm.ws.toml"),
                "[ws]\nmodules = [\"libs/core\", \"./libs/core\"]\n");
        assertThrows(Workspaces.WorkspaceException.class, () -> Workspaces.read(dir));

        // missing manifest
        Files.writeString(dir.resolve("mjm.ws.toml"), "[ws]\nmodules = [\"not-a-module\"]\n");
        Files.createDirectories(dir.resolve("not-a-module"));
        assertThrows(Workspaces.WorkspaceException.class, () -> Workspaces.read(dir));
    }

    @Test
    void aSymlinkedRouteIsNotCountedTwice() throws Exception {
        module("libs/core");
        Files.createSymbolicLink(dir.resolve("link"), dir.resolve("libs/core"));

        assertEquals(0, run(dir, "--ws", "libs/core", "link"), errors());

        assertEquals(1, Workspaces.read(dir).orElseThrow().modules().size(),
                "one real directory, one entry");
    }

    // ------------------------------------------------------------------

    private void module(String relative) throws IOException {
        Path module = relative.isEmpty() ? dir : dir.resolve(relative);
        Files.createDirectories(module);
        Files.writeString(module.resolve("mjm.toml"), """
            [project]
            name = "m"
            version = "0.1.0"
            """);
        if (relative.isEmpty()) {
            Files.deleteIfExists(dir.resolve("mjm.ws.toml"));
        }
    }

    private static int run(Path projectDir, String... args) {
        return new InitCommand(projectDir).execute(List.of(args));
    }

    private static String errors() {
        PrintStream original = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            return buffer.toString(StandardCharsets.UTF_8);
        } finally {
            System.setErr(original);
        }
    }
}
