package com.jujin.jeno.cli;

import com.jujin.jeno.store.ArtifactStore;
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
 * {@code jeno update} end to end — against a loopback releases API, never the
 * real GitHub. The served release carries a minimal dist tar.gz whose
 * install.sh drops a marker into the redirected HOME; the marker's presence
 * proves the full path: detect → download → extract → run the packaged
 * install.sh. CI has already exercised the same chain for real (it installs
 * the previous release to build the next); this test pins the logic.
 */
class UpdateTest {

    @TempDir
    Path dir;

    @Test
    void updateDetectsDownloadsAndInstalls(@TempDir Path home) throws Exception {
        packageDist("0.1.99");
        HttpServer server = serveLatest("v0.1.99");

        System.setProperty("jeno.update.api",
                "http://localhost:" + server.getAddress().getPort()
                        + "/repos/dzb/jeno-cargo/releases/latest");
        System.setProperty("jeno.update.home", home.toString());
        try {
            int exit = new UpdateCommand().execute(List.of());
            assertEquals(0, exit, "the upgrade completed");
            assertTrue(Files.exists(home.resolve(".jeno/marker")),
                    "the packaged install.sh ran under the redirected HOME");
        } finally {
            server.stop(0);
            System.clearProperty("jeno.update.api");
            System.clearProperty("jeno.update.home");
        }
    }

    @Test
    void theUpgradeMessageUsesTheProductName(@TempDir Path home) throws Exception {
        // A rename that misses a string literal is invisible until a user sees
        // it — this test reads the message rather than trusting the code.
        packageDist("0.1.99");
        HttpServer server = serveLatest("v0.1.99");

        System.setProperty("jeno.update.api",
                "http://localhost:" + server.getAddress().getPort()
                        + "/repos/dzb/jeno-cargo/releases/latest");
        System.setProperty("jeno.update.home", home.toString());
        PrintStream original = System.out;
        var buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            assertEquals(0, new UpdateCommand().execute(List.of()));
        } finally {
            System.setOut(original);
            server.stop(0);
            System.clearProperty("jeno.update.api");
            System.clearProperty("jeno.update.home");
        }

        String output = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("upgraded to jeno "),
                "the message names the product: " + output);
        assertFalse(output.contains("upgraded to mj "),
                "and never the previous name: " + output);
    }

    @Test
    void aTruncatedDownloadIsRetried(@TempDir Path home) throws Exception {
        packageDist("0.1.99");
        HttpServer server = serveLatest("v0.1.99", 1);

        System.setProperty("jeno.update.api",
                "http://localhost:" + server.getAddress().getPort()
                        + "/repos/dzb/jeno-cargo/releases/latest");
        System.setProperty("jeno.update.home", home.toString());
        System.setProperty("jeno.update.backoff.ms", "1,1,1,1");
        java.io.PrintStream original = System.err;
        var errors = new ByteArrayOutputStream();
        int exit;
        try {
            System.setErr(new PrintStream(errors, true, StandardCharsets.UTF_8));
            exit = new UpdateCommand().execute(List.of());
        } finally {
            System.setErr(original);
            server.stop(0);
            System.clearProperty("jeno.update.api");
            System.clearProperty("jeno.update.home");
            System.clearProperty("jeno.update.backoff.ms");
        }

        String message = errors.toString(StandardCharsets.UTF_8);
        assertEquals(0, exit, "the second, complete response is installed");
        assertTrue(message.contains("download attempt 1 failed"),
                "the retry is reported: " + message);
        assertTrue(Files.exists(home.resolve(".jeno/marker")), "the upgrade landed");
    }

    @Test
    void aPersistentlyTruncatedDownloadNamesPropagation(@TempDir Path home) throws Exception {
        packageDist("0.1.99");
        HttpServer server = serveLatest("v0.1.99", 10);
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        long leftoversBefore = countUpgradeTemps(tmp);

        System.setProperty("jeno.update.api",
                "http://localhost:" + server.getAddress().getPort()
                        + "/repos/dzb/jeno-cargo/releases/latest");
        System.setProperty("jeno.update.home", home.toString());
        System.setProperty("jeno.update.backoff.ms", "1,1,1,1");
        java.io.PrintStream original = System.err;
        var errors = new ByteArrayOutputStream();
        int exit;
        try {
            System.setErr(new PrintStream(errors, true, StandardCharsets.UTF_8));
            exit = new UpdateCommand().execute(List.of());
        } finally {
            System.setErr(original);
            server.stop(0);
            System.clearProperty("jeno.update.api");
            System.clearProperty("jeno.update.home");
            System.clearProperty("jeno.update.backoff.ms");
        }

        String message = errors.toString(StandardCharsets.UTF_8);
        assertEquals(1, exit);
        assertTrue(message.contains("may not have propagated"),
                "the next action is named: " + message);
        assertTrue(message.contains("download attempt 4 failed"),
                "all five tries happened (four retries): " + message);
        assertFalse(Files.exists(home.resolve(".jeno/marker")),
                "nothing was installed over the working one");
        assertEquals(leftoversBefore, countUpgradeTemps(tmp),
                "no jeno-upgrade-* temp file or directory survives the failure");
    }

    @Test
    void theShippedRetryWindowSpansAboutFifteenSeconds() {
        System.clearProperty("jeno.update.backoff.ms");

        long total = java.util.Arrays.stream(UpdateCommand.backoffMillis()).sum();

        assertEquals(14_500L, total,
                "5 tries with 500ms + 2s + 4s + 8s between them");
    }

    private static long countUpgradeTemps(Path tmp) throws IOException {
        try (var entries = Files.list(tmp)) {
            return entries.filter(path -> path.getFileName().toString()
                    .startsWith("jeno-upgrade-")).count();
        }
    }

    @Test
    void jsonCheckReportsAnAvailableUpdateWithoutInstalling(@TempDir Path home) throws Exception {
        HttpServer server = serveLatest("v0.1.99");
        System.setProperty("jeno.update.api",
                "http://localhost:" + server.getAddress().getPort()
                        + "/repos/dzb/jeno-cargo/releases/latest");
        System.setProperty("jeno.update.home", home.toString());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        int exit;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            exit = new UpdateCommand().execute(List.of("--json", "--check"));
        } finally {
            System.setOut(original);
            server.stop(0);
            System.clearProperty("jeno.update.api");
            System.clearProperty("jeno.update.home");
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
        assertFalse(Files.exists(home.resolve(".jeno/marker")),
                "check mode never downloads or installs");
    }

    @Test
    void theCurrentVersionIsANoOp(@TempDir Path home) throws Exception {
        HttpServer server = serveLatest("v" + Jeno.VERSION);

        System.setProperty("jeno.update.api",
                "http://localhost:" + server.getAddress().getPort()
                        + "/repos/dzb/jeno-cargo/releases/latest");
        System.setProperty("jeno.update.home", home.toString());
        try {
            int exit = new UpdateCommand().execute(List.of());
            assertEquals(0, exit, "already-latest exits zero");
            assertFalse(Files.exists(home.resolve(".jeno/marker")),
                    "nothing was installed");
        } finally {
            server.stop(0);
            System.clearProperty("jeno.update.api");
            System.clearProperty("jeno.update.home");
        }
    }

    /**
     * Builds the minimal dist tar.gz the loopback serves: an install.sh that
     * drops the marker — the installer is the artifact under test. The sha256
     * file is part of the jeno release layout.
     */
    private void packageDist(String version) throws IOException, InterruptedException {
        Path dist = Files.createDirectories(dir.resolve("jeno-" + version));
        Files.writeString(dist.resolve("install.sh"), """
            #!/bin/bash
            set -e
            mkdir -p "$HOME/.jeno"
            touch "$HOME/.jeno/marker"
            """);
        Files.writeString(dist.resolve("install.ps1"), "# windows installer\n");
        Files.writeString(dir.resolve("install.ps1"), "# windows installer\n");
        Path tarPath = dir.resolve("jeno-" + version + ".tar.gz");
        Process tar = new ProcessBuilder("tar", "-czf", tarPath.toString(),
                "-C", dir.toString(), "jeno-" + version)
                .inheritIO().start();
        assertEquals(0, tar.waitFor(), "the fixture tar.gz was built");
        Files.writeString(dir.resolve("jeno-" + version + ".tar.gz.sha256"),
                ArtifactStore.sha256(tarPath) + "  " + tarPath.getFileName() + "\n");
    }

    private HttpServer serveJson(String json) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        server.createContext("/repos/dzb/jeno-cargo/releases/latest", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return server;
    }

    /** Serves the releases API and the tar.gz asset on one loopback. */
    private HttpServer serveLatest(String tag) throws IOException {
        return serveLatest(tag, 0, null, false);
    }

    private HttpServer serveLatest(String tag, int truncated) throws IOException {
        return serveLatest(tag, truncated, null, false);
    }

    /**
     * The same, optionally returning one 404 (or every 404) for one asset,
     * and truncating the first {@code truncated} asset responses: a release
     * asset that has not reached the CDN edge yet.
     */
    private HttpServer serveLatest(String tag, int truncated, String firstNotFound,
                                   boolean alwaysNotFound) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        String version = tag.substring(1);
        String json = "{\"tag_name\":\"" + tag + "\",\"assets\":["
                + "{\"name\":\"jeno-" + version + ".tar.gz\","
                + "\"browser_download_url\":\"http://localhost:"
                + server.getAddress().getPort() + "/assets/jeno-" + version + ".tar.gz\"},"
                + "{\"name\":\"jeno-" + version + ".tar.gz.sha256\","
                + "\"browser_download_url\":\"http://localhost:"
                + server.getAddress().getPort() + "/assets/jeno-" + version + ".tar.gz.sha256\"},"
                + "{\"name\":\"install.ps1\","
                + "\"browser_download_url\":\"http://localhost:"
                + server.getAddress().getPort() + "/assets/install.ps1\"}]}";
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        server.createContext("/repos/dzb/jeno-cargo/releases/latest", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        java.util.concurrent.atomic.AtomicInteger served =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger missing =
                new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/assets/", exchange -> {
            String assetPath = exchange.getRequestURI().getPath().replace("/assets/", "");
            if (firstNotFound != null && assetPath.equals(firstNotFound)
                    && (alwaysNotFound || missing.getAndIncrement() == 0)) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            Path tar = dir.resolve(assetPath);
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
    void aChecksumMismatchNamesTheAsset(@TempDir Path home) throws Exception {
        packageDist("0.1.99");
        Files.writeString(dir.resolve("jeno-0.1.99.tar.gz.sha256"),
                "0000000000000000000000000000000000000000000000000000000000000000\n");
        HttpServer server = serveLatest("v0.1.99");
        System.setProperty("jeno.update.api",
                "http://localhost:" + server.getAddress().getPort()
                        + "/repos/dzb/jeno-cargo/releases/latest");
        System.setProperty("jeno.update.home", home.toString());
        var errors = new ByteArrayOutputStream();
        PrintStream original = System.err;
        int exit;
        try {
            System.setErr(new PrintStream(errors, true, StandardCharsets.UTF_8));
            exit = new UpdateCommand().execute(List.of());
        } finally {
            System.setErr(original);
            server.stop(0);
            System.clearProperty("jeno.update.api");
            System.clearProperty("jeno.update.home");
        }
        assertEquals(1, exit);
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("sha256 mismatch"),
                errors.toString(StandardCharsets.UTF_8));
    }

    @Test
    void anOldJenoBinAssetNamesTheMigration(@TempDir Path home) throws Exception {
        Path old = Files.writeString(dir.resolve("jeno-bin-0.1.99.tar.gz"), "old");
        String json = "{\"tag_name\":\"v0.1.99\",\"assets\":[{\"name\":\"jeno-bin-0.1.99.tar.gz\","
                + "\"browser_download_url\":\"http://localhost:1/old\"}]}";
        HttpServer server = serveJson(json);
        System.setProperty("jeno.update.api",
                "http://localhost:" + server.getAddress().getPort()
                        + "/repos/dzb/jeno-cargo/releases/latest");
        try {
            int exit = new UpdateCommand().execute(List.of());
            assertEquals(1, exit);
        } finally {
            server.stop(0);
            System.clearProperty("jeno.update.api");
        }
    }

    @Test
    void theWindowsInstallerUsesPowerShellArguments(@TempDir Path home) throws Exception {
        packageDist("0.1.99");
        HttpServer server = serveLatest("v0.1.99");
        Path args = dir.resolve("powershell-args.txt");
        Path fake = dir.resolve("fake-powershell.sh");
        Files.writeString(fake, "#!/bin/sh\nprintf '%s\\n' \"$@\" > "
                + args + "\n");
        fake.toFile().setExecutable(true);
        System.setProperty("jeno.update.api",
                "http://localhost:" + server.getAddress().getPort()
                        + "/repos/dzb/jeno-cargo/releases/latest");
        System.setProperty("jeno.update.os", "windows");
        System.setProperty("jeno.update.powershell", fake.toString());
        try {
            int exit = new UpdateCommand().execute(List.of());
            assertEquals(0, exit);
            String invoked = Files.readString(args);
            assertTrue(invoked.contains("-NoProfile"), invoked);
            assertTrue(invoked.contains("-ExecutionPolicy"), invoked);
            assertTrue(invoked.contains("Bypass"), invoked);
            assertTrue(invoked.contains("-File"), invoked);
            assertTrue(invoked.contains(".ps1"), invoked);
        } finally {
            server.stop(0);
            System.clearProperty("jeno.update.api");
            System.clearProperty("jeno.update.os");
            System.clearProperty("jeno.update.powershell");
        }
    }

    @Test
    void aJustPublishedTarRetriesAfter404(@TempDir Path home) throws Exception {
        packageDist("0.1.99");
        HttpServer server = serveLatest("v0.1.99", 0, "jeno-0.1.99.tar.gz", false);
        System.setProperty("jeno.update.api",
                "http://localhost:" + server.getAddress().getPort()
                        + "/repos/dzb/jeno-cargo/releases/latest");
        System.setProperty("jeno.update.home", home.toString());
        System.setProperty("jeno.update.backoff.ms", "1,1,1,1");
        var errors = new ByteArrayOutputStream();
        PrintStream original = System.err;
        int exit;
        try {
            System.setErr(new PrintStream(errors, true, StandardCharsets.UTF_8));
            exit = new UpdateCommand().execute(List.of());
        } finally {
            System.setErr(original);
            server.stop(0);
            System.clearProperty("jeno.update.api");
            System.clearProperty("jeno.update.home");
            System.clearProperty("jeno.update.backoff.ms");
        }
        assertEquals(0, exit, errors.toString(StandardCharsets.UTF_8));
        assertTrue(errors.toString(StandardCharsets.UTF_8)
                        .contains("download attempt 1 failed (server answered 404) — retrying"),
                errors.toString(StandardCharsets.UTF_8));
        assertTrue(Files.exists(home.resolve(".jeno/marker")));
    }

    @Test
    void aPersistentlyMissingTarNamesPropagationAndUrl(@TempDir Path home) throws Exception {
        packageDist("0.1.99");
        HttpServer server = serveLatest("v0.1.99", 0, "jeno-0.1.99.tar.gz", true);
        System.setProperty("jeno.update.api",
                "http://localhost:" + server.getAddress().getPort()
                        + "/repos/dzb/jeno-cargo/releases/latest");
        System.setProperty("jeno.update.home", home.toString());
        System.setProperty("jeno.update.backoff.ms", "1,1,1,1");
        var errors = new ByteArrayOutputStream();
        PrintStream original = System.err;
        int exit;
        try {
            System.setErr(new PrintStream(errors, true, StandardCharsets.UTF_8));
            exit = new UpdateCommand().execute(List.of());
        } finally {
            System.setErr(original);
            server.stop(0);
            System.clearProperty("jeno.update.api");
            System.clearProperty("jeno.update.home");
            System.clearProperty("jeno.update.backoff.ms");
        }
        String message = errors.toString(StandardCharsets.UTF_8);
        assertEquals(1, exit);
        assertTrue(message.contains("may not have propagated"), message);
        assertTrue(message.contains("jeno-0.1.99.tar.gz"), message);
    }

    @Test
    void theWindowsInstallerRetriesA404(@TempDir Path home) throws Exception {
        packageDist("0.1.99");
        HttpServer server = serveLatest("v0.1.99", 0, "install.ps1", false);
        Path fake = dir.resolve("fake-powershell.sh");
        Files.writeString(fake, "#!/bin/sh\nexit 0\n");
        fake.toFile().setExecutable(true);
        System.setProperty("jeno.update.api",
                "http://localhost:" + server.getAddress().getPort()
                        + "/repos/dzb/jeno-cargo/releases/latest");
        System.setProperty("jeno.update.os", "windows");
        System.setProperty("jeno.update.powershell", fake.toString());
        System.setProperty("jeno.update.backoff.ms", "1,1,1,1");
        var errors = new ByteArrayOutputStream();
        PrintStream original = System.err;
        int exit;
        try {
            System.setErr(new PrintStream(errors, true, StandardCharsets.UTF_8));
            exit = new UpdateCommand().execute(List.of());
        } finally {
            System.setErr(original);
            server.stop(0);
            System.clearProperty("jeno.update.api");
            System.clearProperty("jeno.update.os");
            System.clearProperty("jeno.update.powershell");
            System.clearProperty("jeno.update.backoff.ms");
        }
        assertEquals(0, exit, errors.toString(StandardCharsets.UTF_8));
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("server answered 404"),
                errors.toString(StandardCharsets.UTF_8));
    }

    @Test
    void upgradeIsTheNameAndUpdateIsAnAlias() throws Exception {
        UpdateCommand command = new UpdateCommand();
        assertEquals("upgrade", command.name());
        assertArrayEquals(new String[]{"update"}, command.aliases());

        CommandRunner runner = new CommandRunner(List.of(command));
        String viaAlias = help(runner, "update");
        String viaName = help(runner, "upgrade");
        assertTrue(viaAlias.contains("usage: jeno upgrade"), viaAlias);
        assertTrue(viaName.contains("usage: jeno upgrade"), viaName);
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
