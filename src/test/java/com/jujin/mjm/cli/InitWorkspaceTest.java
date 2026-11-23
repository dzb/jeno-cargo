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
    void aLegacyMjManifestIsNamedByTheScan() throws Exception {
        Path old = Files.createDirectories(dir.resolve("old-module"));
        Files.writeString(old.resolve("mj.toml"), """
                [project]
                name = "old"
                version = "2.0.2"
                """);

        Result result = runCaptured(dir, "--ws");

        assertEquals(1, result.exit());
        assertFalse(Files.exists(dir.resolve("mjm.ws.toml")));
        assertTrue(result.err().contains("found legacy mj.toml in: old-module"),
                result.err());
        assertTrue(result.err().contains("mjm does not read mj.toml"), result.err());
    }

    @Test
    void anExplicitLegacyModuleGetsTheMigrationHint() throws Exception {
        Path old = Files.createDirectories(dir.resolve("old-module"));
        Files.writeString(old.resolve("mj.toml"), """
                [project]
                name = "old"
                version = "2.0.2"
                """);

        Result result = runCaptured(dir, "--ws", "old-module");

        assertEquals(1, result.exit());
        assertTrue(result.err().contains("has mj.toml, not mjm.toml"), result.err());
        assertTrue(result.err().contains("mjm does not read the old name"), result.err());
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
        String name = relative.isEmpty() ? "root"
                : relative.replaceAll("[^a-z0-9]+", "-");
        Files.writeString(module.resolve("mjm.toml"), """
            [project]
            name = "%s"
            version = "0.1.0"
            """.formatted(name));
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

    private static Result runCaptured(Path projectDir, String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            exit = new InitCommand(projectDir).execute(List.of(args));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new Result(exit, out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
    }

    private record Result(int exit, String out, String err) {}

    @Test
    void jsonProjectInitReportsTheCreatedManifest() throws Exception {
        Result result = runCaptured(dir, "--json");

        assertEquals(0, result.exit(), result.err());
        var json = com.jujin.freeway.commons.json.JsonUtils.parseObject(result.out());
        assertEquals("init", json.getString("command"));
        assertEquals("project", json.getString("mode"));
        assertEquals(false, json.getBoolean("overwritten"));
        assertEquals(dir.resolve("mjm.toml").toAbsolutePath().normalize().toString(),
                json.getString("file"));
        assertEquals(InitCommand.nameOf(dir), json.getObject("project").getString("name"));
        assertTrue(Files.exists(dir.resolve("mjm.toml")));
    }

    @Test
    void jsonWorkspaceInitReportsEveryModule() throws Exception {
        module("libs/core");
        module("apps/web");

        Result result = runCaptured(dir, "--ws", "--json");

        assertEquals(0, result.exit(), result.err());
        var json = com.jujin.freeway.commons.json.JsonUtils.parseObject(result.out());
        assertEquals("workspace", json.getString("mode"));
        assertEquals(dir.resolve("mjm.ws.toml").toAbsolutePath().normalize().toString(),
                json.getString("file"));
        assertEquals(2, json.getInt("moduleCount"));
        assertEquals(java.util.Set.of("apps/web", "libs/core"),
                java.util.Set.copyOf(json.getArray("modules").toList().stream()
                        .map(String::valueOf).toList()));
    }

    @Test
    void aWorkspaceRootRefusesToBecomeAProject() throws Exception {
        module("mod");
        Files.writeString(dir.resolve("mjm.ws.toml"), """
            [ws]
            modules = ["mod"]
            """);

        assertEquals(1, run(dir), "plain init is refused");
        assertFalse(Files.exists(dir.resolve("mjm.toml")), "nothing is written");
        assertEquals(1, run(dir, "--pom"), "migration is refused too");
        assertFalse(Files.exists(dir.resolve("mjm.toml")));
        assertEquals(1, run(dir, "--force"), "--force does not override the guard");
        assertFalse(Files.exists(dir.resolve("mjm.toml")));

        assertEquals(0, run(dir, "--ws", "--force"), "init --ws still belongs here");
        assertFalse(Files.exists(dir.resolve("mjm.toml")));
    }
}
