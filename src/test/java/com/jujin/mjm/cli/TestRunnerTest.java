package com.jujin.mjm.cli;

import com.jujin.mjm.resolve.LockFile;
import com.jujin.mjm.resolve.ResolvedGraph;
import com.jujin.mjm.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The built-in runner (node:test's shape): compile src/test/java against the
 * closure plus mj itself, run every @MjmTest, one line per failure, exit 1 on
 * any failure.
 */
class TestRunnerTest {

    @TempDir
    Path dir;

    @Test
    void failuresFailAndPassesPass(@TempDir Path storeDir) throws Exception {
        project();
        Files.writeString(dir.resolve("src/test/java/demo/Tests.java"), """
            package demo;

            import com.jujin.mjm.test.MjmTest;
            import com.jujin.mjm.test.MjmAssert;
            import com.jujin.mjm.test.MjmBeforeEach;

            public class Tests {
                @MjmBeforeEach
                void setUp() { MjmAssert.true_(true); }

                @MjmTest
                void passes() { MjmAssert.equals("a", "a"); }

                @MjmTest
                void fails() { MjmAssert.equals(1, 2); }
            }
            """);
        lock();

        int exit;
        try (var central = VendoredToolchain.start()) {
            exit = new TestCommand(new ArtifactStore(storeDir), dir).execute(List.of());
        }

        assertEquals(1, exit, "a failing @MjmTest fails the command");
    }

    @Test
    void aCleanSuiteExitsZero(@TempDir Path storeDir) throws Exception {
        project();
        Files.writeString(dir.resolve("src/test/java/demo/Tests.java"), """
            package demo;

            import com.jujin.mjm.test.MjmTest;
            import com.jujin.mjm.test.MjmAssert;

            public class Tests {
                @MjmTest
                void one() { MjmAssert.true_(true); }

                @MjmTest
                void two() { MjmAssert.false_(false); }

                @MjmTest
                void three() { MjmAssert.notNull("x"); }
            }
            """);
        lock();

        int exit;
        try (var central = VendoredToolchain.start()) {
            exit = new TestCommand(new ArtifactStore(storeDir), dir).execute(List.of());
        }

        assertEquals(0, exit, "three passing tests exit zero");
    }

    @Test
    void jupiterTestsRunThroughThePlatform(@TempDir Path storeDir) throws Exception {
        project();
        Files.createDirectories(dir.resolve("src/test/java/demo"));
        Files.writeString(dir.resolve("src/test/java/demo/JupiterTests.java"), """
            package demo;

            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

            public class JupiterTests {
                @Test
                void passes() { assertEquals(1, 1); }

                @Test
                void fails() { assertEquals(1, 2); }
            }
            """);
        lock();

        int exit;
        try (var central = VendoredToolchain.start()) {
            exit = new TestCommand(new ArtifactStore(storeDir), dir).execute(List.of());
        }

        assertEquals(1, exit, "a failing Jupiter test fails the command");
    }

    @Test
    void jupiterPassingSuiteExitsZero(@TempDir Path storeDir) throws Exception {
        project();
        Files.createDirectories(dir.resolve("src/test/java/demo"));
        Files.writeString(dir.resolve("src/test/java/demo/JupiterTests.java"), """
            package demo;

            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

            public class JupiterTests {
                @Test
                void passes() { assertEquals(1, 1); }
            }
            """);
        lock();

        int exit;
        try (var central = VendoredToolchain.start()) {
            exit = new TestCommand(new ArtifactStore(storeDir), dir).execute(List.of());
        }

        assertEquals(0, exit, "a passing Jupiter suite exits zero");
    }

    @Test
    void noTestSourcesIsQuietlyZero(@TempDir Path storeDir) throws Exception {
        project();
        lock();
        assertEquals(0, new TestCommand(new ArtifactStore(storeDir), dir).execute(List.of()),
                "a project without src/test/java has nothing to run");
    }

    @Test
    void aMissingManifestMeansNoFilters() {
        // the one case that stays silent: lock/build already say mjm.toml is missing
        assertTrue(TestCommand.Filter.from(dir).keeps("anything.java"));
    }

    @Test
    void aManifestThatCannotBeReadIsAnErrorNotAnEmptyFilter() throws IOException {
        Files.createDirectory(dir.resolve("mjm.toml"));  // exists, but is not a file

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> TestCommand.Filter.from(dir));

        assertTrue(error.getMessage().contains("cannot read"),
                "a broken manifest must not silently run every test, got: "
                        + error.getMessage());
    }

    @Test
    void twoRunsInTheSameProjectDoNotOverlap(@TempDir Path storeDir) throws Exception {
        project();
        Path marker = dir.resolve("marker.txt");
        Files.writeString(dir.resolve("src/test/java/demo/Tests.java"), """
            package demo;

            import com.jujin.mjm.test.MjmTest;

            public class Tests {
                @MjmTest
                void ok() throws Exception {
                    String marker = System.getProperty("probe.marker");
                    try (var out = new java.io.FileOutputStream(marker, true)) {
                        out.write((System.currentTimeMillis() + " start\\n").getBytes());
                        out.flush();
                        Thread.sleep(1200);
                        out.write((System.currentTimeMillis() + " end\\n").getBytes());
                    }
                }
            }
            """);
        lock();

        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        try (var central = VendoredToolchain.start()) {
            String base = System.getProperty("mjm.central.base");
            Process first = run(dir, java, classpath, base, marker, dir.resolve("first.log"));
            Process second = run(dir, java, classpath, base, marker, dir.resolve("second.log"));

            int firstExit = first.waitFor();
            int secondExit = second.waitFor();
            assertEquals(0, firstExit, "first run exit " + firstExit + ":\n"
                    + read(dir.resolve("first.log")));
            assertEquals(0, secondExit, "second run exit " + secondExit + ":\n"
                    + read(dir.resolve("second.log")));
        }

        // two runs, each writing a start/end pair; serialized runs never overlap
        List<long[]> intervals = new ArrayList<>();
        long start = -1;
        for (String line : Files.readAllLines(marker)) {
            String[] parts = line.strip().split(" ");
            long at = Long.parseLong(parts[0]);
            if (parts[1].equals("start")) {
                start = at;
            } else {
                intervals.add(new long[]{start, at});
            }
        }
        assertEquals(2, intervals.size(), "both runs reached the fork: " + Files.readString(marker));
        long[] a = intervals.get(0);
        long[] b = intervals.get(1);
        assertTrue(a[1] <= b[0] || b[1] <= a[0],
                "the runs must not execute at the same time: " + Files.readString(marker));
    }

    private static Process run(Path projectDir, String java, String classpath, String centralBase,
                               Path marker, Path log) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(java, "-cp", classpath,
                "-Dmjm.central.base=" + centralBase, "com.jujin.mjm.cli.Mjm", "test");
        builder.directory(projectDir.toFile());
        builder.environment().put("JAVA_TOOL_OPTIONS", "-Dprobe.marker=" + marker.toAbsolutePath());
        return builder.redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
    }

    private static String read(Path log) throws IOException {
        return Files.exists(log) ? Files.readString(log) : "(no log)";
    }

    private void project() throws IOException {
        Files.createDirectories(dir.resolve("src/test/java/demo"));
        Files.writeString(dir.resolve("mjm.toml"), """
            [project]
            name = "demo"
            version = "0.1.0"
            """);
    }

    private void lock() throws IOException {
        Files.writeString(dir.resolve("mjm.lock"),
                LockFile.serialize(new ResolvedGraph(List.of(), List.of(), Map.of()),
                        url -> "e".repeat(64)));
    }
}
