package com.jujin.mjm.cli;

import com.jujin.mjm.resolve.LockFile;
import com.jujin.mjm.resolve.ResolvedGraph;
import com.jujin.mjm.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
