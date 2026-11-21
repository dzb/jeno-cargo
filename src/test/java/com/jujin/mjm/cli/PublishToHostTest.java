package com.jujin.mjm.cli;

import com.jujin.mjm.store.ArtifactStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code mjm publish https://host/base} — the URL-first path: the triple is
 * written in Maven's layout (the one {@code [repos]} already reads) and PUT to
 * the host. No platform account, no registry; any server accepting PUT works.
 */
class PublishToHostTest {

    @TempDir
    Path dir;

    @Test
    void theTripleLandsInMavenLayoutOverPut() throws Exception {
        Map<String, byte[]> received = new ConcurrentHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            if (!"PUT".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            received.put(exchange.getRequestURI().getPath(),
                    exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(201, -1);
        });
        server.start();
        String base = "http://localhost:" + server.getAddress().getPort() + "/repo/";

        try {
            project();
            ArtifactStore store = new ArtifactStore(dir.resolve("store"));
            assertEquals(0, new BuildCommand(store, dir).execute(List.of()));
            assertEquals(0, new PackageCommand(store, dir).execute(List.of()));

            int exit = new PublishCommand(dir).execute(List.of(base));
            assertEquals(0, exit, "the upload succeeded");

            // Maven layout: group/path/artifact/version/artifact-version.ext
            assertTrue(received.containsKey(
                            "/repo/com/acme/widget/1.2.0/widget-1.2.0.jar"),
                    "the jar is at its Maven coordinates: " + received.keySet());
            assertTrue(received.containsKey(
                            "/repo/com/acme/widget/1.2.0/widget-1.2.0.mjm.toml"),
                    "the descriptor rides beside it: " + received.keySet());
            assertTrue(received.containsKey(
                            "/repo/com/acme/widget/1.2.0/widget-1.2.0.sha256"),
                    "and the checksum: " + received.keySet());

            String descriptor = new String(received.get(
                    "/repo/com/acme/widget/1.2.0/widget-1.2.0.mjm.toml"));
            assertTrue(descriptor.contains("group = \"com.acme\""),
                    "the descriptor names the group a consumer addresses: " + descriptor);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void withoutAGroupAMavenLayoutCannotBeBuilt() throws Exception {
        Map<String, byte[]> received = new ConcurrentHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            received.put(exchange.getRequestURI().getPath(), new byte[0]);
            exchange.sendResponseHeaders(201, -1);
        });
        server.start();
        String base = "http://localhost:" + server.getAddress().getPort() + "/repo/";

        try {
            Files.createDirectories(dir.resolve("src/main/java/demo"));
            Files.writeString(dir.resolve("mjm.toml"), """
                [project]
                name = "widget"
                version = "1.2.0"
                """);
            Files.writeString(dir.resolve("src/main/java/demo/App.java"),
                    "package demo; public final class App {}");
            Files.writeString(dir.resolve("mjm.lock"), "");

            ArtifactStore store = new ArtifactStore(dir.resolve("store"));
            assertEquals(0, new BuildCommand(store, dir).execute(List.of()));
            assertEquals(0, new PackageCommand(store, dir).execute(List.of()));

            assertEquals(1, new PublishCommand(dir).execute(List.of(base)),
                    "no group means no path to write to");
            assertTrue(received.isEmpty(), "nothing was uploaded");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aRejectingServerIsReportedWithTheProtocolHint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(405, -1);  // a static server: PUT not allowed
        });
        server.start();
        String base = "http://localhost:" + server.getAddress().getPort() + "/repo/";

        try {
            project();
            ArtifactStore store = new ArtifactStore(dir.resolve("store"));
            assertEquals(0, new BuildCommand(store, dir).execute(List.of()));
            assertEquals(0, new PackageCommand(store, dir).execute(List.of()));

            assertEquals(1, new PublishCommand(dir).execute(List.of(base)),
                    "a host that refuses PUT fails the publish");
        } finally {
            server.stop(0);
        }
    }

    private void project() throws Exception {
        Files.createDirectories(dir.resolve("src/main/java/demo"));
        Files.writeString(dir.resolve("mjm.toml"), """
            [project]
            name = "widget"
            group = "com.acme"
            version = "1.2.0"
            summary = "A tiny widget"
            """);
        Files.writeString(dir.resolve("src/main/java/demo/App.java"),
                "package demo; public final class App {}");
        Files.writeString(dir.resolve("mjm.lock"),
                com.jujin.mjm.resolve.LockFile.serialize(
                        new com.jujin.mjm.resolve.ResolvedGraph(List.of(), List.of(), Map.of()),
                        url -> "e".repeat(64)));
    }
}
