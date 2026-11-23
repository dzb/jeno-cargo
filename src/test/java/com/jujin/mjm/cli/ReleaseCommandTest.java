package com.jujin.mjm.cli;

import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.mjm.store.ArtifactStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

/** {@code mjm release}: bundle output in, verified installer out. */
class ReleaseCommandTest {

    @TempDir
    Path dir;

    private ArtifactStore store;
    private HttpServer server;

    @BeforeEach
    void setUp() throws Exception {
        ProjectLock.useRoot(dir.resolve(".locks"));
        com.jujin.mjm.store.Paths.useForTesting(dir.resolve(".cache/store"),
                dir.resolve(".cache/sources"), dir.resolve(".locks"));
        com.jujin.mjm.resolve.ArtifactLocations.useProjectRoot(dir);
        store = new ArtifactStore(dir.resolve(".cache/store"));
        Files.createDirectories(dir.resolve("src/main/java/app"));
        Files.writeString(dir.resolve("mjm.toml"), """
            [project]
            name = "app"
            version = "1.0.0"
            main = "app.Main"

            [java]
            version = 17
            """);
        Files.writeString(dir.resolve("src/main/java/app/Main.java"), """
            package app;
            public final class Main {
                public static void main(String[] args) { System.out.println("app-ok"); }
            }
            """);
        Files.writeString(dir.resolve("mjm.lock"), "");
        assertEquals(0, new BuildCommand(store, dir).execute(List.of()));
        assertEquals(0, new BundleCommand(store, dir).execute(List.of()));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        ProjectLock.useUserRoot();
        com.jujin.mjm.store.Paths.resetForTesting();
        System.clearProperty("mjm.github.token");
        System.clearProperty("mjm.github.api");
        System.clearProperty("mjm.github.uploads");
    }

    @Test
    void withoutATargetItPreparesAndListsTheUpload() throws Exception {
        Result result = run();

        assertEquals(0, result.exit(), result.out() + result.err());
        Path release = dir.resolve("target/dist/release");
        assertTrue(Files.exists(release.resolve("setup.sh")));
        assertTrue(Files.exists(release.resolve("app-1.0.0.tar.gz.sha256")));
        var json = JsonUtils.parseObject(
                Files.readString(release.resolve("release.json")).strip());
        assertEquals("app", json.getString("name"));
        assertEquals("1.0.0", json.getString("version"));
        assertEquals(17, json.getInt("jdk"));
        assertEquals("app-1.0.0.tar.gz", json.getString("artifact"));
        String bootstrap = Files.readString(release.resolve("setup.sh"));
        assertTrue(bootstrap.contains("file://" + dir.resolve("target/dist/app-1.0.0.tar.gz")),
                bootstrap);
        assertTrue(result.out().contains("upload these four files"), result.out());
    }

    @Test
    void aLocalTargetWritesTheVersionedAndStableLayouts() throws Exception {
        Path repo = dir.resolve("repo");

        Result result = run("mjm:local:" + repo);

        assertEquals(0, result.exit(), result.out() + result.err());
        Path versioned = repo.resolve("app/1.0.0");
        assertTrue(Files.exists(versioned.resolve("release/app-1.0.0.tar.gz")));
        assertTrue(Files.exists(versioned.resolve("release/setup.sh")));
        assertTrue(Files.exists(versioned.resolve("release/release.json")));
        assertTrue(Files.exists(versioned.resolve("setup.sh")));
        assertTrue(Files.exists(repo.resolve("app/setup.sh")), "a stable entry exists");
    }

    @Test
    void dryRunWritesNothingToTheTarget() throws Exception {
        Path repo = dir.resolve("never");

        Result result = run("--dry-run", "mjm:local:" + repo);

        assertEquals(0, result.exit(), result.out() + result.err());
        assertFalse(Files.exists(repo), "dry-run never touches the target");
        assertTrue(result.out().contains("dry run — would upload:"), result.out());
    }

    @Test
    void anMjmRepositoryIsPutAndReadBack() throws Exception {
        Map<String, byte[]> files = new ConcurrentHashMap<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("PUT".equals(exchange.getRequestMethod())) {
                files.put(path, exchange.getRequestBody().readAllBytes());
                exchange.sendResponseHeaders(201, -1);
                return;
            }
            byte[] body = files.get(path);
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        String base = "mjm:http://localhost:" + server.getAddress().getPort() + "/repo/";

        Result result = run(base);

        assertEquals(0, result.exit(), result.out() + result.err());
        assertTrue(files.containsKey("/repo/app/1.0.0/release/app-1.0.0.tar.gz"));
        assertTrue(files.containsKey("/repo/app/1.0.0/release/setup.sh"));
        assertTrue(files.containsKey("/repo/app/setup.sh"), "the stable entry is uploaded");
    }

    @Test
    void aGitHubTargetUploadsAndVerifies() throws Exception {
        Map<String, byte[]> assets = new ConcurrentHashMap<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/releases") && "POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 201, "{\"id\":7}");
                return;
            }
            if (path.contains("/assets")) {
                String name = exchange.getRequestURI().getQuery().split("name=")[1];
                assets.put(name, exchange.getRequestBody().readAllBytes());
                respond(exchange, 201, "{\"browser_download_url\":\"http://localhost:"
                        + server.getAddress().getPort() + "/download/" + name + "\"}");
                return;
            }
            byte[] body = assets.get(path.substring(path.lastIndexOf('/') + 1));
            if (body == null) {
                respond(exchange, 404, "{}");
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        String base = "http://localhost:" + server.getAddress().getPort();
        System.setProperty("mjm.github.api", base);
        System.setProperty("mjm.github.uploads", base);
        System.setProperty("mjm.github.token", "test-token");

        Result result = run("gh:owner/repo");

        assertEquals(0, result.exit(), result.out() + result.err());
        assertTrue(assets.containsKey("app-1.0.0.tar.gz"));
        assertTrue(assets.containsKey("setup.sh"));
        assertTrue(assets.containsKey("release.json"));
    }

    @Test
    void withoutABundleItSaysWhatToRun() throws Exception {
        Files.delete(dir.resolve("target/dist/app-1.0.0.tar.gz"));

        Result result = run();

        assertEquals(1, result.exit());
        assertTrue(result.err().contains("run mjm bundle first"), result.err());
    }

    private Result run(String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            exit = new ReleaseCommand(dir).execute(List.of(args));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new Result(exit, out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status,
                                String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private record Result(int exit, String out, String err) {}

    @Test
    void theBootstrapInstallsInAnIsolatedHome() throws Exception {
        String shell = Files.exists(Path.of("/bin/sh")) ? "/bin/sh" : null;
        if (shell == null || commandMissing("curl") && commandMissing("wget")
                || commandMissing("tar")) {
            return;  // this machine cannot exercise the bootstrap
        }
        assertEquals(0, run().exit());
        Path home = Files.createDirectories(dir.resolve("home"));
        ProcessBuilder installer = new ProcessBuilder(shell,
                dir.resolve("target/dist/release/setup.sh").toString())
                .directory(dir.resolve("target/dist").toFile())
                .redirectErrorStream(true)
                .redirectOutput(dir.resolve("install.log").toFile());
        installer.environment().put("HOME", home.toString());
        Process install = installer.start();
        assertEquals(0, install.waitFor(),
                Files.readString(dir.resolve("install.log")));
        assertTrue(Files.exists(home.resolve(".app/lib/app-1.0.0.jar")),
                "the bootstrap unpacked and installed the dist");

        Process launched = new ProcessBuilder(home.resolve(".app/bin/app").toString())
                .redirectErrorStream(true)
                .redirectOutput(dir.resolve("run.log").toFile())
                .start();
        assertEquals(0, launched.waitFor());
        assertTrue(Files.readString(dir.resolve("run.log")).contains("app-ok"),
                Files.readString(dir.resolve("run.log")));
    }

    @Test
    void anMjmTargetThatRejectsTheUploadFails() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> respond(exchange, 500, "no"));
        server.start();
        String base = "mjm:http://localhost:" + server.getAddress().getPort() + "/repo/";

        Result result = run(base);

        assertEquals(1, result.exit(), result.out() + result.err());
        assertTrue(result.err().contains("release upload failed"), result.err());
    }

    @Test
    void aGitHubTargetWithoutATokenFailsBeforeUploading() throws Exception {
        Result result = run("gh:owner/repo");

        assertEquals(1, result.exit());
        assertTrue(result.err().contains("MJM_GITHUB_TOKEN"), result.err());
    }

    private static boolean commandMissing(String command) {
        try {
            return new ProcessBuilder("sh", "-c", "command -v " + command)
                    .start().waitFor() != 0;
        } catch (IOException | InterruptedException e) {
            return true;
        }
    }
}
