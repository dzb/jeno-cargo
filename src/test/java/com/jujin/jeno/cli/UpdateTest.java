package com.jujin.jeno.cli;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code mjm update} end to end — against a loopback releases API, never the
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

        System.setProperty("mjm.update.api",
                "http://localhost:" + server.getAddress().getPort()
                        + "/repos/dzb/mjm-cargo/releases/latest");
        System.setProperty("mjm.update.home", home.toString());
        try {
            int exit = new UpdateCommand().execute(List.of());
            assertEquals(0, exit, "the upgrade completed");
            assertTrue(Files.exists(home.resolve(".mjm/marker")),
                    "the packaged setup.sh ran under the redirected HOME");
        } finally {
            server.stop(0);
            System.clearProperty("mjm.update.api");
            System.clearProperty("mjm.update.home");
        }
    }

    @Test
    void theUpgradeMessageUsesTheProductName(@TempDir Path home) throws Exception {
        // A rename that misses a string literal is invisible until a user sees
        // it — this test reads the message rather than trusting the code.
        packageDist("0.1.99");
        HttpServer server = serveLatest("v0.1.99");

        System.setProperty("mjm.update.api",
                "http://localhost:" + server.getAddress().getPort()
                        + "/repos/dzb/mjm-cargo/releases/latest");
        System.setProperty("mjm.update.home", home.toString());
        PrintStream original = System.out;
        var buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            assertEquals(0, new UpdateCommand().execute(List.of()));
        } finally {
            System.setOut(original);
            server.stop(0);
            System.clearProperty("mjm.update.api");
            System.clearProperty("mjm.update.home");
        }

        String output = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("upgraded to mjm "),
                "the message names the product: " + output);
        assertFalse(output.contains("upgraded to mj "),
                "and never the previous name: " + output);
    }

    @Test
    void aTruncatedDownloadIsRetried(@TempDir Path home) throws Exception {
        packageDist("0.1.99");
        HttpServer server = serveLatest("v0.1.99", 1);

        System.setProperty("mjm.update.api",
                "http://localhost:" + server.getAddress().getPort()
                        + "/repos/dzb/mjm-cargo/releases/latest");
        System.setProperty("mjm.update.home", home.toString());
        System.setProperty("mjm.update.backoff.ms", "1,1,1,1");
        java.io.PrintStream original = System.err;
        var errors = new ByteArrayOutputStream();
        int exit;
        try {
            System.setErr(new PrintStream(errors, true, StandardCharsets.UTF_8));
            exit = new UpdateCommand().execute(List.of());
        } finally {
            System.setErr(original);
            server.stop(0);
            System.clearProperty("mjm.update.api");
            System.clearProperty("mjm.update.home");
            System.clearProperty("mjm.update.backoff.ms");
        }

        String message = errors.toString(StandardCharsets.UTF_8);
        assertEquals(0, exit, "the second, complete response is installed");
        assertTrue(message.contains("download attempt 1 failed"),
                "the retry is reported: " + message);
        assertTrue(Files.exists(home.resolve(".mjm/marker")), "the upgrade landed");
    }

    @Test
    void aPersistentlyTruncatedDownloadNamesPropagation(@TempDir Path home) throws Exception {
        packageDist("0.1.99");
        HttpServer server = serveLatest("v0.1.99", 10);
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        long leftoversBefore = countUpgradeTemps(tmp);

        System.setProperty("mjm.update.api",
                "http://localhost:" + server.getAddress().getPort()
                        + "/repos/dzb/mjm-cargo/releases/latest");
        System.setProperty("mjm.update.home", home.toString());
        System.setProperty("mjm.update.backoff.ms", "1,1,1,1");
        java.io.PrintStream original = System.err;
        var errors = new ByteArrayOutputStream();
        int exit;
        try {
            System.setErr(new PrintStream(errors, true, StandardCharsets.UTF_8));
            exit = new UpdateCommand().execute(List.of());
        } finally {
            System.setErr(original);
            server.stop(0);
            System.clearProperty("mjm.update.api");
            System.clearProperty("mjm.update.home");
            System.clearProperty("mjm.update.backoff.ms");
        }

        String message = errors.toString(StandardCharsets.UTF_8);
        assertEquals(1, exit);
        assertTrue(message.contains("may not have propagated"),
                "the next action is named: " + message);
        assertTrue(message.contains("download attempt 4 failed"),
                "all five tries happened (four retries): " + message);
        assertFalse(Files.exists(home.resolve(".mjm/marker")),
                "nothing was installed over the working one");
        assertEquals(leftoversBefore, countUpgradeTemps(tmp),
                "no mjm-upgrade-* temp file or directory survives the failure");
    }

    @Test
    void theShippedRetryWindowSpansAboutFifteenSeconds() {
        System.clearProperty("mjm.update.backoff.ms");

        long total = java.util.Arrays.stream(UpdateCommand.backoffMillis()).sum();

        assertEquals(14_500L, total,
                "5 tries with 500ms + 2s + 4s + 8s between them");
    }

    private static long countUpgradeTemps(Path tmp) throws IOException {
        try (var entries = Files.list(tmp)) {
            return entries.filter(path -> path.getFileName().toString()
                    .startsWith("mjm-upgrade-")).count();
        }
    }

    @Test
    void jsonCheckReportsAnAvailableUpdateWithoutInstalling(@TempDir Path home) throws Exception {
        HttpServer server = serveLatest("v0.1.99");
        System.setProperty("mjm.update.api",
                "http://localhost:" + server.getAddress().getPort()
                        + "/repos/dzb/mjm-cargo/releases/latest");
        System.setProperty("mjm.update.home", home.toString());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        int exit;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            exit = new UpdateCommand().execute(List.of("--json", "--check"));
        } finally {
            System.setOut(original);
            server.stop(0);
            System.clearProperty("mjm.update.api");
            System.clearProperty("mjm.update.home");
        }

        assertEquals(0, exit);
        var json = com.jujin.freeway.commons.json.JsonUtils.parseObject(
                out.toString(StandardCharsets.UTF_8));
        assertEquals("upgrade", json.getString("command"));
        assertEquals(true, json.getBoolean("checkOnly"));
        assertEquals(Jeno.VERSION, json.getString("currentVersion"));
        assertEquals("0.1.99", json.getString("latestVersion"));
        assertEquals(true, json.getBoolean("updateAvailable"));
        assertEquals(false, json.getBoolean("upgraded"));
        assertFalse(Files.exists(home.resolve(".mjm/marker")),
                "check mode never downloads or installs");
    }

    @Test
    void theCurrentVersionIsANoOp(@TempDir Path home) throws Exception {
        HttpServer server = serveLatest("v" + Jeno.VERSION);

        System.setProperty("mjm.update.api",
                "http://localhost:" + server.getAddress().getPort()
                        + "/repos/dzb/mjm-cargo/releases/latest");
        System.setProperty("mjm.update.home", home.toString());
        try {
            int exit = new UpdateCommand().execute(List.of());
            assertEquals(0, exit, "already-latest exits zero");
            assertFalse(Files.exists(home.resolve(".mjm/marker")),
                    "nothing was installed");
        } finally {
            server.stop(0);
            System.clearProperty("mjm.update.api");
            System.clearProperty("mjm.update.home");
        }
    }

    /**
     * Builds the minimal dist tar.gz the loopback serves: a setup.sh that
     * drops the marker — the installer is the artifact under test.
     */
    private void packageDist(String version) throws IOException, InterruptedException {
        Path dist = Files.createDirectories(dir.resolve("mjm-" + version));
        Files.writeString(dist.resolve("setup.sh"), """
            #!/bin/bash
            set -e
            mkdir -p "$HOME/.mjm"
            touch "$HOME/.mjm/marker"
            """);
        Process tar = new ProcessBuilder("tar", "-czf",
                dir.resolve("mjm-bin-" + version + ".tar.gz").toString(),
                "-C", dir.toString(), "mjm-" + version)
                .inheritIO().start();
        assertEquals(0, tar.waitFor(), "the fixture tar.gz was built");
    }

    /** Serves the releases API and the tar.gz asset on one loopback. */
    private HttpServer serveLatest(String tag) throws IOException {
        return serveLatest(tag, 0);
    }

    /**
     * The same, truncating the first {@code truncated} asset responses: the
     * headers promise the full length, the body stops halfway — the CDN edge
     * that was not ready yet.
     */
    private HttpServer serveLatest(String tag, int truncated) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        String json = "{\"tag_name\":\"" + tag + "\",\"assets\":[{"
                + "\"name\":\"mjm-bin-" + tag.substring(1) + ".tar.gz\","
                + "\"browser_download_url\":\"http://localhost:"
                + server.getAddress().getPort() + "/assets/mjm-bin-"
                + tag.substring(1) + ".tar.gz\"}]}";
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        server.createContext("/repos/dzb/mjm-cargo/releases/latest", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        java.util.concurrent.atomic.AtomicInteger served =
                new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/assets/", exchange -> {
            Path tar = dir.resolve(exchange.getRequestURI().getPath()
                    .replace("/assets/", ""));
            byte[] bytes = Files.readAllBytes(tar);
            boolean cut = served.incrementAndGet() <= truncated;
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes, 0, cut ? bytes.length / 2 : bytes.length);
                out.flush();
                if (cut) {
                    // let the headers and the half body reach the client before
                    // the connection closes: that is the EOF the CDN edge gives
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        });
        server.start();
        return server;
    }

    @Test
    void upgradeIsTheNameAndUpdateIsAnAlias() throws Exception {
        UpdateCommand command = new UpdateCommand();
        assertEquals("upgrade", command.name());
        assertArrayEquals(new String[]{"update"}, command.aliases());

        CommandRunner runner = new CommandRunner(List.of(command));
        String viaAlias = help(runner, "update");
        String viaName = help(runner, "upgrade");
        assertTrue(viaAlias.contains("usage: mjm upgrade"), viaAlias);
        assertTrue(viaName.contains("usage: mjm upgrade"), viaName);
        assertTrue(viaName.contains("(also: update)"), viaName);
    }

    private static String help(CommandRunner runner, String command) {
        PrintStream original = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(0, runner.run(command, "--help"));
        } finally {
            System.setOut(original);
        }
        return out.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
