package com.jujin.jeno.cli;

import com.jujin.jeno.store.ArtifactStore;
import com.jujin.jeno.resolve.LockFile;
import com.jujin.jeno.resolve.ResolvedGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The build/run loop end to end, on a real temp project: lock file present →
 * javac compiles → java runs — with no dependencies at all, so the loop is
 * proven without a single byte of network.
 */
class BuildRunTest {

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
    void buildCompilesAndRunExecutes(@TempDir Path storeDir) throws Exception {
        writeProject(dir);

        ArtifactStore store = new ArtifactStore(storeDir);
        BuildCommand build = new BuildCommand(store, dir);
        RunCommand run = new RunCommand(store, dir);

        assertEquals(1, build.execute(java.util.List.of()),
                "a build without mjm.lock is refused with a next-action message");

        writeLock(dir);

        assertEquals(0, build.execute(java.util.List.of()), "mjm build compiles the sources");
        assertTrue(Files.exists(dir.resolve("target/classes/demo/Main.class")),
                "the compiled class lands in target/classes");

        assertEquals(0, run.execute(java.util.List.of("demo.Main")), "mj run executes the main");
    }

    @Test
    void jsonBuildExportsTheClassesArtifactManifest(@TempDir Path storeDir) throws Exception {
        writeProject(dir);
        writeLock(dir);
        ArtifactStore store = new ArtifactStore(storeDir);
        System.setProperty("mjm.color", "always");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        int exit;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            exit = new BuildCommand(store, dir).execute(java.util.List.of("--json"));
        } finally {
            System.setOut(original);
            System.clearProperty("mjm.color");
        }

        assertEquals(0, exit);
        String raw = out.toString(StandardCharsets.UTF_8);
        assertFalse(raw.contains("\u001B["), "JSON stdout must stay ANSI-free");
        var json = com.jujin.freeway.commons.json.JsonUtils.parseObject(raw);
        assertEquals("build", json.getString("command"));
        assertEquals("ok", json.getString("status"));
        assertEquals(1, json.getArray("modules").size());
        var result = json.getArray("modules").getObject(0).getObject("result");
        assertEquals(dir.resolve("target/classes").toAbsolutePath().normalize().toString(),
                result.getString("classesDir"));
        assertEquals(1, result.getInt("sourceCount"));
        assertEquals("classes", result.getArray("artifacts").getObject(0).getString("kind"));
        assertTrue(result.getArray("artifacts").getObject(0).getLong("fileCount") >= 2,
                "the class plus the version resource");
    }

    @Test
    void aStaleLockIsRefused(@TempDir Path storeDir) throws Exception {
        // mjm.toml declares lib-a; the lock only carries an unrelated id —
        // a declared id missing from the lock means someone forgot mjm lock.
        Files.writeString(dir.resolve("mjm.toml"), """
            [project]
            name = "demo"
            version = "0.1.0"

            [deps]
            "com.example:lib-a" = "1.0.0"
            """);
        Files.writeString(dir.resolve("mjm.lock"), """
            [[artifacts]]
            id = "org.other:lib"
            version = "1.0.0"
            source = "central"
            url = "https://repo.maven.apache.org/maven2/org/other/lib/1.0.0/lib-1.0.0.jar"
            sha256 = "%s"
            depth = 1
            """.formatted("a".repeat(64)));

        int exit = new BuildCommand(new ArtifactStore(storeDir), dir).execute(java.util.List.of());
        assertEquals(1, exit, "a lock without the declared id is stale");
    }

    private static void writeProject(Path dir) throws IOException {
        Files.createDirectories(dir.resolve("src/main/java/demo"));
        Files.writeString(dir.resolve("mjm.toml"), """
            [project]
            name = "demo"
            version = "0.1.0"
            """);
        Files.writeString(dir.resolve("src/main/java/demo/Main.java"), """
            package demo;

            public final class Main {
                public static void main(String[] args) {
                    System.out.println("build-run-ok");
                }
            }
            """);
    }

    private static void writeLock(Path dir) throws IOException {
        // No dependencies in this project — an empty lock still must be present.
        Files.writeString(dir.resolve("mjm.lock"),
                LockFile.serialize(new ResolvedGraph(java.util.List.of(), java.util.List.of(), java.util.Map.of()),
                        url -> "f".repeat(64)));
    }
}
