package com.jujin.jeno.cli;

import com.jujin.jeno.resolve.ArtifactLocations;
import com.jujin.jeno.store.ArtifactStore;
import com.jujin.jeno.store.StoreDescriptorSource;
import com.jujin.jeno.store.StorePomSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code mjm lock --check}: would today's manifest regenerate the lock on disk?
 * It resolves in memory and never writes — the point is Cargo's {@code --locked},
 * which catches version drift that build's "id is locked" check misses.
 */
class LockCheckTest {

    @TempDir
    Path dir;

    private LockService lockService;

    @BeforeEach
    void isolateProjectLocks() {
        ProjectLock.useRoot(dir.resolve(".locks"));
    }

    @AfterEach
    void restoreProjectLocks() {
        ProjectLock.useUserRoot();
        ArtifactLocations.useProjectRoot(Path.of("").toAbsolutePath());
    }

    @Test
    void anUpToDateLockExitsZeroAndTouchesNothing() throws Exception {
        lockProject();
        byte[] lock = Files.readAllBytes(dir.resolve("mjm.lock"));
        FileTime modified = Files.getLastModifiedTime(dir.resolve("mjm.lock"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exit = run(out, "--check");

        assertEquals(0, exit);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("mjm.lock is up to date"),
                out.toString(StandardCharsets.UTF_8));
        assertArrayEquals(lock, Files.readAllBytes(dir.resolve("mjm.lock")),
                "the lock is byte-for-byte unchanged");
        assertEquals(modified, Files.getLastModifiedTime(dir.resolve("mjm.lock")),
                "and was not even rewritten");
    }

    @Test
    void aDriftedManifestShowsTheDifferenceAndFails() throws Exception {
        lockProject();
        byte[] lock = Files.readAllBytes(dir.resolve("mjm.lock"));
        Files.writeString(dir.resolve("mjm.toml"),
                Files.readString(dir.resolve("mjm.toml"))
                        .replace("\"com.example:lib-a\" = \"vendor:1.0.0\"",
                                "\"com.example:lib-a\" = \"vendor:1.2.0\""));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exit = run(out, "--check");

        String message = out.toString(StandardCharsets.UTF_8);
        assertEquals(1, exit);
        assertTrue(message.contains("out of date"), message);
        assertTrue(message.contains("- version = \"1.0.0\""), message);
        assertTrue(message.contains("+ version = \"1.2.0\""), message);
        assertTrue(message.contains("run mjm lock"), message);
        assertArrayEquals(lock, Files.readAllBytes(dir.resolve("mjm.lock")),
                "the check never writes the lock");
    }

    @Test
    void jsonCheckReportsArtifactsAndUpToDateSummary() throws Exception {
        lockProject();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int exit = run(out, "--json", "--check");

        assertEquals(0, exit);
        var root = com.jujin.freeway.commons.json.JsonUtils.parseObject(
                out.toString(StandardCharsets.UTF_8).strip());
        assertEquals("lock", root.getString("command"));
        assertEquals("ok", root.getString("status"));
        assertEquals(true, root.getBoolean("check"));
        assertEquals(1, root.getArray("modules").size());
        var module = root.getArray("modules").getObject(0);
        assertEquals(true, module.getBoolean("upToDate"));
        assertEquals(1, root.getObject("summary").getInt("upToDateModules"));
    }

    @Test
    void jsonDriftIsAStructuredFailureWithADifference() throws Exception {
        lockProject();
        Files.writeString(dir.resolve("mjm.toml"),
                Files.readString(dir.resolve("mjm.toml"))
                        .replace("\"com.example:lib-a\" = \"vendor:1.0.0\"",
                                "\"com.example:lib-a\" = \"vendor:1.2.0\""));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int exit = run(out, "--json", "--check");

        assertEquals(1, exit);
        var root = com.jujin.freeway.commons.json.JsonUtils.parseObject(
                out.toString(StandardCharsets.UTF_8).strip());
        assertEquals("failed", root.getString("status"));
        var module = root.getArray("modules").getObject(0);
        assertEquals(false, module.getBoolean("upToDate"));
        assertTrue(module.getArray("differences").toList().stream()
                .map(String::valueOf).anyMatch(line -> line.contains("+ version = \"1.2.0\"")));
        assertTrue(root.getArray("diagnostics").toList().stream()
                .map(value -> ((java.util.Map<?, ?>) value).get("code"))
                .anyMatch("LOCK_STALE"::equals));
    }

    @Test
    void refreshIsAnUnknownOptionNow() throws Exception {
        lockProject();

        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        PrintStream original = System.err;
        int exit;
        try {
            System.setErr(new PrintStream(errors, true, StandardCharsets.UTF_8));
            exit = command().execute(List.of("--refresh"));
        } finally {
            System.setErr(original);
        }

        assertEquals(2, exit);
        String message = errors.toString(StandardCharsets.UTF_8);
        assertTrue(message.contains("--refresh was removed"), message);
        assertTrue(message.contains("use --check"), message);
    }

    private int run(ByteArrayOutputStream out, String... args) throws Exception {
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            return command().execute(List.of(args));
        } finally {
            System.setOut(original);
        }
    }

    private LockCommand command() {
        return new LockCommand(lockService, dir);
    }

    /** A project whose one dependency comes from a local repository, already locked. */
    private void lockProject() throws IOException {
        for (String version : List.of("1.0.0", "1.2.0")) {
            Path pom = dir.resolve("vendor/com/example/lib-a/" + version
                    + "/lib-a-" + version + ".pom");
            Files.createDirectories(pom.getParent());
            Files.writeString(pom, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>lib-a</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(version));
            Files.write(pom.resolveSibling("lib-a-" + version + ".jar"),
                    ("jar-" + version).getBytes());
        }
        Files.writeString(dir.resolve("mjm.toml"), """
            [project]
            name = "demo"
            version = "0.1.0"

            [repos]
            vendor = "local:vendor"

            [deps]
            "com.example:lib-a" = "vendor:1.0.0"
            """);
        ArtifactLocations.useProjectRoot(dir);
        ArtifactStore store = new ArtifactStore(dir.resolve("store"));
        lockService = new LockService(store, new StorePomSource(store),
                new StoreDescriptorSource(store));
        lockService.refresh(dir);
    }
}
