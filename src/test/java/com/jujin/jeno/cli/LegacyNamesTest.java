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

        var thrown = assertThrows(Workspaces.WorkspaceException.class,
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
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            exit = new BuildCommand(store, dir).execute(List.of());
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
