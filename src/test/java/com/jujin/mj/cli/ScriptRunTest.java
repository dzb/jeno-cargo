package com.jujin.mj.cli;

import com.jujin.mj.resolve.LockFile;
import com.jujin.mj.resolve.ResolvedGraph;
import com.jujin.mj.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code [scripts]} through {@code mj run}: a script gets the project's
 * computed classpath as {@code MJ_CLASSPATH} and its home as {@code MJ_HOME},
 * so it can launch the project without rebuilding the classpath by hand.
 * Extra arguments arrive as {@code $1}, {@code $2}… — not folded into the
 * command string.
 */
class ScriptRunTest {

    @TempDir
    Path dir;

    @Test
    void aScriptSeesTheClasspathAndHome(@TempDir Path storeDir) throws Exception {
        Files.createDirectories(dir.resolve("src/main/java/demo"));
        Files.writeString(dir.resolve("mj.toml"), """
            [project]
            name = "demo"
            version = "1.0.0"

            [scripts]
            probe = "printf '%s\\n' \\"$MJ_HOME\\" \\"$MJ_CLASSPATH\\" > out.txt"
            """);
        Files.writeString(dir.resolve("src/main/java/demo/App.java"),
                "package demo; public final class App {}");
        Files.writeString(dir.resolve("mj.lock"),
                LockFile.serialize(new ResolvedGraph(List.of(), List.of(), Map.of()),
                        url -> "e".repeat(64)));

        ArtifactStore store = new ArtifactStore(storeDir);
        assertEquals(0, new BuildCommand(store, dir).execute(List.of()));

        String output = capture(store, List.of("probe"));

        assertTrue(output.contains(dir.toAbsolutePath().toString()),
                "MJ_HOME names the project directory: " + output);
        assertTrue(output.contains(dir.resolve("target/classes").toString()),
                "MJ_CLASSPATH starts with the build output: " + output);
    }

    @Test
    void extraArgumentsArriveAsPositionalParameters(@TempDir Path storeDir) throws Exception {
        Files.writeString(dir.resolve("mj.toml"), """
            [project]
            name = "demo"
            version = "1.0.0"

            [scripts]
            echoargs = "printf '1=%s 2=%s\\n' \\"$1\\" \\"$2\\" > out.txt"
            """);
        Files.writeString(dir.resolve("mj.lock"), "");

        String output = capture(new ArtifactStore(storeDir), List.of("echoargs", "alpha", "beta"));
        assertTrue(output.contains("1=alpha 2=beta"),
                "caller arguments land in $1/$2, not the command string: " + output);
    }

    @Test
    void aScriptStillRunsWithoutABuildOrLock(@TempDir Path storeDir) throws Exception {
        // no target/classes and no mj.lock: the variables are absent, but a
        // script that does not need them must still run
        Files.writeString(dir.resolve("mj.toml"), """
            [project]
            name = "demo"
            version = "1.0.0"

            [scripts]
            hello = "printf 'ok\\n' > out.txt"
            """);

        String output = capture(new ArtifactStore(storeDir), List.of("hello"));
        assertTrue(output.contains("ok"), "a build-independent script runs: " + output);
    }

    @Test
    void aMainClassStillWinsWhenNoScriptMatches(@TempDir Path storeDir) throws Exception {
        Files.createDirectories(dir.resolve("src/main/java/demo"));
        Files.writeString(dir.resolve("mj.toml"), """
            [project]
            name = "demo"
            version = "1.0.0"

            [scripts]
            build-stuff = "true"
            """);
        Files.writeString(dir.resolve("src/main/java/demo/Main.java"), """
            package demo;

            public final class Main {
                public static void main(String[] args) throws Exception {
                    java.nio.file.Files.writeString(java.nio.file.Path.of("out.txt"), "main-ran");
                }
            }
            """);
        Files.writeString(dir.resolve("mj.lock"),
                LockFile.serialize(new ResolvedGraph(List.of(), List.of(), Map.of()),
                        url -> "e".repeat(64)));

        ArtifactStore store = new ArtifactStore(storeDir);
        assertEquals(0, new BuildCommand(store, dir).execute(List.of()));

        String output = capture(store, List.of("demo.Main"));
        assertTrue(output.contains("main-ran"), "unmatched names fall through to java: " + output);
    }

    /**
     * Runs the command and returns what the child wrote to {@code out.txt}.
     * Scripts inherit stdio (deliberately — a script's output should reach the
     * terminal), so the fixture has to capture through a file rather than
     * System.out.
     */
    private String capture(ArtifactStore store, List<String> args) throws Exception {
        Path out = dir.resolve("out.txt");
        Files.deleteIfExists(out);
        assertEquals(0, new RunCommand(store, dir).execute(args), "the run exits zero");
        return Files.exists(out) ? Files.readString(out) : "";
    }
}
