package com.jujin.mj.cli;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code mj update} end to end — against a loopback releases API, never the
 * real GitHub. The served release carries a minimal dist tar.gz whose
 * setup.sh drops a marker into the redirected HOME; the marker's presence
 * proves the full path: detect → download → extract → run the packaged
 * setup.sh. CI has already exercised the same chain for real (it installs
 * the previous release to build the next); this test pins the logic.
 */
class UpdateTest {

    @TempDir
    Path dir;

    @Test
    void updateDetectsDownloadsAndInstalls(@TempDir Path home) throws Exception {
        packageDist("0.1.99");
        HttpServer server = serveLatest("v0.1.99");

        System.setProperty("mj.update.api",
                "http://localhost:" + server.getAddress().getPort()
                        + "/repos/dzb/mj-cargo/releases/latest");
        System.setProperty("mj.update.home", home.toString());
        try {
            int exit = new UpdateCommand().execute(List.of());
            assertEquals(0, exit, "the upgrade completed");
            assertTrue(Files.exists(home.resolve(".mj/marker")),
                    "the packaged setup.sh ran under the redirected HOME");
        } finally {
            server.stop(0);
            System.clearProperty("mj.update.api");
            System.clearProperty("mj.update.home");
        }
    }

    @Test
    void theCurrentVersionIsANoOp(@TempDir Path home) throws Exception {
        HttpServer server = serveLatest("v" + Mj.VERSION);

        System.setProperty("mj.update.api",
                "http://localhost:" + server.getAddress().getPort()
                        + "/repos/dzb/mj-cargo/releases/latest");
        System.setProperty("mj.update.home", home.toString());
        try {
            int exit = new UpdateCommand().execute(List.of());
            assertEquals(0, exit, "already-latest exits zero");
            assertFalse(Files.exists(home.resolve(".mj/marker")),
                    "nothing was installed");
        } finally {
            server.stop(0);
            System.clearProperty("mj.update.api");
            System.clearProperty("mj.update.home");
        }
    }

    /**
     * Builds the minimal dist tar.gz the loopback serves: a setup.sh that
     * drops the marker — the installer is the artifact under test.
     */
    private void packageDist(String version) throws IOException, InterruptedException {
        Path dist = Files.createDirectories(dir.resolve("mj-" + version));
        Files.writeString(dist.resolve("setup.sh"), """
            #!/bin/bash
            set -e
            mkdir -p "$HOME/.mj"
            touch "$HOME/.mj/marker"
            """);
        Process tar = new ProcessBuilder("tar", "-czf",
                dir.resolve("mj-bin-" + version + ".tar.gz").toString(),
                "-C", dir.toString(), "mj-" + version)
                .inheritIO().start();
        assertEquals(0, tar.waitFor(), "the fixture tar.gz was built");
    }

    /** Serves the releases API and the tar.gz asset on one loopback. */
    private HttpServer serveLatest(String tag) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        String json = "{\"tag_name\":\"" + tag + "\",\"assets\":[{"
                + "\"name\":\"mj-bin-" + tag.substring(1) + ".tar.gz\","
                + "\"browser_download_url\":\"http://localhost:"
                + server.getAddress().getPort() + "/assets/mj-bin-"
                + tag.substring(1) + ".tar.gz\"}]}";
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        server.createContext("/repos/dzb/mj-cargo/releases/latest", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/assets/", exchange -> {
            Path tar = dir.resolve(exchange.getRequestURI().getPath()
                    .replace("/assets/", ""));
            byte[] bytes = Files.readAllBytes(tar);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return server;
    }
}
