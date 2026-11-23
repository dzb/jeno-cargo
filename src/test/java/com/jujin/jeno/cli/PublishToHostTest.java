package com.jujin.jeno.cli;

import com.jujin.jeno.store.ArtifactStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Maven-layout target is refused: jeno writes no POM, so neither jeno nor
 * Maven could read the triple back. Nothing is uploaded, nothing is rewritten.
 */
class PublishToHostTest {

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
    void anHttpsMavenHostIsRefusedBeforeAnyPut() throws Exception {
        Map<String, byte[]> received = new ConcurrentHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            received.put(exchange.getRequestURI().getPath(),
                    exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(201, -1);
        });
        server.start();
        String base = "https://localhost:" + server.getAddress().getPort() + "/repo/";

        try {
            project();
            ArtifactStore store = new ArtifactStore(dir.resolve("store"));
            assertEquals(0, new BuildCommand(store, dir).execute(List.of()));
            assertEquals(0, new PackageCommand(store, dir).execute(List.of()));

            Result result = publish(base);

            assertEquals(1, result.exit());
            assertTrue(result.err().contains("Maven-layout target"), result.err());
            assertTrue(result.err().contains("jeno:local:"), result.err());
            assertTrue(received.isEmpty(), "zero requests: " + received.keySet());
            assertFalse(Files.exists(dir.resolve("target/dist/widget-1.2.0.jeno.toml")),
                    "the descriptor was not rewritten");
            assertFalse(Files.exists(dir.resolve("target/dist/widget-1.2.0.sha256")),
                    "no half-published triple");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aLoopbackHttpMavenHostIsRefusedToo() throws Exception {
        Map<String, byte[]> received = new ConcurrentHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            received.put(exchange.getRequestURI().getPath(), new byte[0]);
            exchange.sendResponseHeaders(201, -1);
        });
        server.start();
        String base = "http://localhost:" + server.getAddress().getPort() + "/repo/";

        try {
            project();
            ArtifactStore store = new ArtifactStore(dir.resolve("store"));
            assertEquals(0, new BuildCommand(store, dir).execute(List.of()));
            assertEquals(0, new PackageCommand(store, dir).execute(List.of()));

            Result result = publish(base);

            assertEquals(1, result.exit());
            assertTrue(result.err().contains("Maven-layout target"), result.err());
            assertTrue(received.isEmpty(), "loopback does not make the layout readable");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aLocalMavenDirectoryIsRefusedWithTheSameMessage() throws Exception {
        project();
        ArtifactStore store = new ArtifactStore(dir.resolve("store"));
        assertEquals(0, new BuildCommand(store, dir).execute(List.of()));
        assertEquals(0, new PackageCommand(store, dir).execute(List.of()));

        Result result = publish("local:../maven-repo");

        assertEquals(1, result.exit());
        assertTrue(result.err().contains("Maven-layout target"), result.err());
        assertFalse(Files.exists(dir.getParent().resolve("maven-repo")), "nothing was written");
    }

    @Test
    void anAcceptedPutThatCannotBeReadBackIsNotASuccessfulPublish() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            if ("PUT".equals(exchange.getRequestMethod())) {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(201, -1);
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
            exchange.close();
        });
        server.start();
        String base = "jeno:http://localhost:" + server.getAddress().getPort() + "/repo/";

        try {
            project();
            ArtifactStore store = new ArtifactStore(dir.resolve("store"));
            assertEquals(0, new BuildCommand(store, dir).execute(List.of()));
            assertEquals(0, new PackageCommand(store, dir).execute(List.of()));

            Result result = publish(base);

            assertEquals(1, result.exit(), "an unreadable repository is not a publish");
            assertTrue(result.err().contains("could not read it back"), result.err());
            assertTrue(result.err().contains("anonymously readable"), result.err());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aPublicPlainHttpJenoRepositoryIsRejected() throws Exception {
        project();
        ArtifactStore store = new ArtifactStore(dir.resolve("store"));
        assertEquals(0, new BuildCommand(store, dir).execute(List.of()));
        assertEquals(0, new PackageCommand(store, dir).execute(List.of()));

        Result result = publish("jeno:http://203.0.113.1/");

        assertEquals(1, result.exit());
        assertTrue(result.err().contains("private network"), result.err());
    }

    // ------------------------------------------------------------------

    private Result publish(String target) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            exit = new PublishCommand(dir).execute(List.of(target));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new Result(exit, out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
    }

    private void project() throws IOException {
        Files.createDirectories(dir.resolve("src/main/java/demo"));
        Files.writeString(dir.resolve("jeno.toml"), """
            [project]
            name = "widget"
            group = "com.acme"
            version = "1.2.0"
            summary = "A tiny widget"
            """);
        Files.writeString(dir.resolve("src/main/java/demo/App.java"),
                "package demo; public final class App {}");
        Files.writeString(dir.resolve("jeno.lock"),
                com.jujin.jeno.resolve.LockFile.serialize(
                        new com.jujin.jeno.resolve.ResolvedGraph(List.of(), List.of(), Map.of()),
                        url -> "e".repeat(64)));
    }

    private record Result(int exit, String out, String err) {}
}
