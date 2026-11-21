package com.jujin.mj.cli;

import com.jujin.mj.store.ArtifactStore;
import com.jujin.mj.resolve.ResolvedGraph;
import com.jujin.mj.resolve.LockFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The built-in runner (node:test's shape): compile src/test/java against the
 * closure plus mj itself, run every @MjTest, one line per failure, exit 1 on
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

            import com.jujin.mj.test.MjTest;
            import com.jujin.mj.test.MjAssert;
            import com.jujin.mj.test.MjBeforeEach;

            public class Tests {
                @MjBeforeEach
                void setUp() { MjAssert.true_(true); }

                @MjTest
                void passes() { MjAssert.equals("a", "a"); }

                @MjTest
                void fails() { MjAssert.equals(1, 2); }
            }
            """);
        lock();

        int exit = new TestCommand(new ArtifactStore(storeDir), dir).execute(List.of());

        assertEquals(1, exit, "a failing @MjTest fails the command");
    }

    @Test
    void aCleanSuiteExitsZero(@TempDir Path storeDir) throws Exception {
        project();
        Files.writeString(dir.resolve("src/test/java/demo/Tests.java"), """
            package demo;

            import com.jujin.mj.test.MjTest;
            import com.jujin.mj.test.MjAssert;

            public class Tests {
                @MjTest
                void one() { MjAssert.true_(true); }

                @MjTest
                void two() { MjAssert.false_(false); }

                @MjTest
                void three() { MjAssert.notNull("x"); }
            }
            """);
        lock();

        int exit = new TestCommand(new ArtifactStore(storeDir), dir).execute(List.of());

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

        // a loopback Central that serves the real junit toolchain jars
        com.sun.net.httpserver.HttpServer central =
                com.sun.net.httpserver.HttpServer.create(
                        new InetSocketAddress("localhost", 0), 0);
        String home = System.getProperty("user.home");
        Map<String, byte[]> jars = new LinkedHashMap<>();
        for (String[] coordinate : new String[][]{
                {"org/junit/jupiter", "junit-jupiter-api"},
                {"org/junit/jupiter", "junit-jupiter-engine"},
                {"org/junit/platform", "junit-platform-launcher"}}) {
            String path = "/" + coordinate[0] + "/" + coordinate[1] + "/6.1.3/"
                    + coordinate[1] + "-6.1.3.jar";
            jars.put(path, java.nio.file.Files.readAllBytes(
                    java.nio.file.Path.of(home, ".m2/repository",
                            coordinate[0], coordinate[1], "6.1.3",
                            coordinate[1] + "-6.1.3.jar")));
        }
        central.createContext("/", exchange -> {
            byte[] body = jars.get(exchange.getRequestURI().getPath());
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        central.start();
        System.setProperty("mj.central.base",
                "http://localhost:" + central.getAddress().getPort() + "/");
        try {
            int exit = new TestCommand(new ArtifactStore(storeDir), dir).execute(List.of());
            assertEquals(1, exit, "a failing Jupiter test fails the command");
        } finally {
            central.stop(0);
            System.clearProperty("mj.central.base");
        }
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

        com.sun.net.httpserver.HttpServer central =
                com.sun.net.httpserver.HttpServer.create(
                        new InetSocketAddress("localhost", 0), 0);
        String home = System.getProperty("user.home");
        Map<String, byte[]> jars = new LinkedHashMap<>();
        for (String[] coordinate : new String[][]{
                {"org/junit/jupiter", "junit-jupiter-api"},
                {"org/junit/jupiter", "junit-jupiter-engine"},
                {"org/junit/platform", "junit-platform-launcher"}}) {
            String path = "/" + coordinate[0] + "/" + coordinate[1] + "/6.1.3/"
                    + coordinate[1] + "-6.1.3.jar";
            jars.put(path, java.nio.file.Files.readAllBytes(
                    java.nio.file.Path.of(home, ".m2/repository",
                            coordinate[0], coordinate[1], "6.1.3",
                            coordinate[1] + "-6.1.3.jar")));
        }
        central.createContext("/", exchange -> {
            byte[] body = jars.get(exchange.getRequestURI().getPath());
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        central.start();
        System.setProperty("mj.central.base",
                "http://localhost:" + central.getAddress().getPort() + "/");
        try {
            assertEquals(0, new TestCommand(new ArtifactStore(storeDir), dir).execute(List.of()),
                    "a passing Jupiter suite exits zero");
        } finally {
            central.stop(0);
            System.clearProperty("mj.central.base");
        }
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
        Files.writeString(dir.resolve("mj.toml"), """
            [project]
            name = "demo"
            version = "0.1.0"
            """);
    }

    private void lock() throws IOException {
        Files.writeString(dir.resolve("mj.lock"),
                LockFile.serialize(new ResolvedGraph(List.of(), List.of(), Map.of()),
                        url -> "e".repeat(64)));
    }
}
