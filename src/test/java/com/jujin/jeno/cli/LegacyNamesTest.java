package com.jujin.jeno.cli;

import com.jujin.jeno.project.ProjectReader;
import com.jujin.jeno.project.Repository;
import com.jujin.jeno.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Old-name files and spellings stop at one migration message, never a silent fallback. */
class LegacyNamesTest {

    @TempDir
    Path dir;

    @Test
    void anOldProjectManifestNamesTheRenameAndRelock() throws Exception {
        Files.writeString(dir.resolve("mjm.toml"), """
                [project]
                name = "old"
                version = "1.0.0"
                """);

        Result result = runBuild();

        assertEquals(1, result.exit());
        assertTrue(result.err().contains("mv mjm.toml jeno.toml"), result.err());
        assertTrue(result.err().contains("jeno lock"), result.err());
    }

    @Test
    void anOldLockNamesTheRenameAndRelock() throws Exception {
        Files.writeString(dir.resolve("jeno.toml"), """
                [project]
                name = "old"
                version = "1.0.0"
                """);
        Files.writeString(dir.resolve("mjm.lock"), "# old\n");

        Result result = runBuild();

        assertEquals(1, result.exit());
        assertTrue(result.err().contains("mv mjm.lock jeno.lock"), result.err());
        assertTrue(result.err().contains("jeno lock"), result.err());
    }

    @Test
    void anOldWorkspaceFileNamesTheRename() throws Exception {
        Files.writeString(dir.resolve("mjm.ws.toml"), "[ws]\nmodules = []\n");

        var thrown = assertThrows(LegacyNames.LegacyException.class,
                () -> Workspaces.read(dir));
        assertTrue(thrown.getMessage().contains("mv mjm.ws.toml jeno.ws.toml"),
                thrown.getMessage());
    }

    @Test
    void theOldRepositoryTypeIsRejected() {
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> Repository.of("r", "mjm", "https://host/"));
        assertTrue(thrown.getMessage().contains("type = \"jeno\""), thrown.getMessage());
    }

    @Test
    void theOldRepositoryPrefixIsRejected() {
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> Repository.parse("mjm:local:/tmp/repo"));
        assertTrue(thrown.getMessage().contains("jeno:local:"), thrown.getMessage());
    }

    @Test
    void anOldDependencyPrefixIsRejectedByTheManifest() {
        var thrown = assertThrows(RuntimeException.class, () -> ProjectReader.parse("""
                [project]
                name = "consumer"
                version = "0.1.0"

                [deps]
                "a:b" = "mjm:local:/tmp/repo"
                """));
        assertTrue(thrown.getMessage().contains("jeno:"), thrown.getMessage());
    }

    @Test
    void aLocalOldDescriptorIsNamed() throws Exception {
        Path repo = Files.createDirectories(dir.resolve("repo/lib/1.0.0"));
        Path old = repo.resolve("lib-1.0.0.mjm.toml");
        Files.writeString(old, "[project]\nname = \"lib\"\nversion = \"1.0.0\"\n");

        assertEquals(old.toAbsolutePath(), LegacyNames.siblingDescriptor(
                repo.resolve("lib-1.0.0.jeno.toml").toUri().toString()));
    }

    @Test
    void jsonModeEmitsOneLegacyNameDocument() throws Exception {
        Files.writeString(dir.resolve("mjm.toml"), """
                [project]
                name = "old"
                version = "1.0.0"
                """);
        ArtifactStore store = new ArtifactStore(dir.resolve("store"));
        CommandRunner runner = new CommandRunner(List.of(new BuildCommand(store, dir)));
        PrintStream originalOut = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exit;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            exit = runner.run("--json", "build");
        } finally {
            System.setOut(originalOut);
        }

        assertEquals(1, exit);
        var json = com.jujin.freeway.commons.json.JsonUtils.parseObject(
                out.toString(StandardCharsets.UTF_8).strip());
        assertEquals("error", json.getString("status"));
        assertEquals(1, json.getInt("exitCode"));
        assertEquals("LEGACY_NAME",
                json.getArray("diagnostics").getObject(0).getString("code"));
        assertTrue(json.getArray("diagnostics").getObject(0).getString("message")
                .contains("mv mjm.toml jeno.toml"));
    }

    @Test
    void anOldLockSourceFailsBuildAndInfoWithTheSameSentence() throws Exception {
        Files.writeString(dir.resolve("jeno.toml"), """
                [project]
                name = "old"
                version = "1.0.0"
                """);
        Files.writeString(dir.resolve("jeno.lock"), """
                [[artifacts]]
                id = "com.example:lib"
                version = "1.0.0"
                source = "mjm:https://host/"
                url = "https://host/lib-1.0.0.jar"
                sha256 = "0000000000000000000000000000000000000000000000000000000000000000"
                depth = 1
                """);
        ArtifactStore store = new ArtifactStore(dir.resolve("store"));
        for (Command command : List.of(new BuildCommand(store, dir),
                new InfoCommand(store, dir))) {
            CommandRunner runner = new CommandRunner(List.of(command));
            Result result = capture(() -> command instanceof InfoCommand
                    ? runner.run("info", "com.example:lib")
                    : runner.run("build"));
            assertEquals(1, result.exit());
            assertTrue(result.err().contains("com.example:lib"), result.err());
            assertTrue(result.err().contains("jeno lock"), result.err());
        }
    }

    private static Result capture(java.util.function.IntSupplier action) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            exit = action.getAsInt();
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new Result(exit, out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void jsonLockAndTreeAlsoEmitLegacyName() throws Exception {
        Files.writeString(dir.resolve("mjm.toml"), """
                [project]
                name = "old"
                version = "1.0.0"
                """);
        ArtifactStore store = new ArtifactStore(dir.resolve("store"));
        LockService lockService = new LockService(store, null, null);
        assertLegacyJson(new LockCommand(lockService, dir));
        assertLegacyJson(new TreeCommand(store, null, null, dir));
    }

    private void assertLegacyJson(Command command) {
        CommandRunner runner = new CommandRunner(List.of(command));
        PrintStream originalOut = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exit;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            exit = runner.run("--json", command.name());
        } finally {
            System.setOut(originalOut);
        }
        assertEquals(1, exit);
        var json = com.jujin.freeway.commons.json.JsonUtils.parseObject(
                out.toString(StandardCharsets.UTF_8).strip());
        assertEquals("LEGACY_NAME",
                json.getArray("diagnostics").getObject(0).getString("code"));
    }

    @Test
    void anOldLockSourceIsNotSilentlyAccepted() throws Exception {
        var thrown = assertThrows(RuntimeException.class, () ->
                com.jujin.jeno.store.LockFileReader.read("""
                        [[artifacts]]
                        id = "com.example:lib"
                        version = "1.0.0"
                        source = "mjm:https://host/"
                        url = "https://host/lib-1.0.0.jar"
                        sha256 = "0000000000000000000000000000000000000000000000000000000000000000"
                        depth = 1
                        """));
        assertTrue(thrown.getMessage().contains("com.example:lib"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("jeno:"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("jeno lock"), thrown.getMessage());
    }

    @Test
    void onlyLegacyNamesMayContainTheOldSpelling() throws Exception {
        Path main = Path.of("src/main/java");
        try (var stream = Files.walk(main)) {
            for (Path file : stream.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                if (text.toLowerCase(java.util.Locale.ROOT).contains("mjm")) {
                    assertEquals("LegacyNames.java", file.getFileName().toString(),
                            "only LegacyNames may mention the old name: " + file);
                }
            }
        }
        assertFalse(Files.walk(main).anyMatch(path -> path.getFileName().toString()
                .toLowerCase(java.util.Locale.ROOT).contains("mjm")));
    }

    private Result runBuild() {
        ArtifactStore store = new ArtifactStore(dir.resolve("store"));
        CommandRunner runner = new CommandRunner(List.of(new BuildCommand(store, dir)));
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            exit = runner.run("build");
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new Result(exit, out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
    }

    private record Result(int exit, String out, String err) {}

    @Test
    void oldEnvironmentVariablesAreOnlyAHint() {
        assertNotNull(LegacyNames.environment());
    }
}
