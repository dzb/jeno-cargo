package com.jujin.mjm.cli;

import com.jujin.mjm.store.ArtifactStore;
import com.jujin.mjm.resolve.LockFile;
import com.jujin.mjm.resolve.ResolvedGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
