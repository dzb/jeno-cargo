package com.jujin.jeno.cli;

import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.jeno.store.ArtifactStore;
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

/** {@code jeno release}: bundle output in, verified installer out. */
class ReleaseCommandTest {

    @TempDir
    Path dir;

    private ArtifactStore store;
    private HttpServer server;

    @BeforeEach
    void setUp() throws Exception {
        ProjectLock.useRoot(dir.resolve(".locks"));
        com.jujin.jeno.store.Paths.useForTesting(dir.resolve(".cache/store"),
                dir.resolve(".cache/sources"), dir.resolve(".locks"));
        com.jujin.jeno.resolve.ArtifactLocations.useProjectRoot(dir);
        store = new ArtifactStore(dir.resolve(".cache/store"));
        Files.createDirectories(dir.resolve("src/main/java/app"));
        Files.writeString(dir.resolve("jeno.toml"), """
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
        Files.writeString(dir.resolve("jeno.lock"), "");
        assertEquals(0, new BuildCommand(store, dir).execute(List.of()));
        assertEquals(0, new BundleCommand(store, dir).execute(List.of()));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        ProjectLock.useUserRoot();
        com.jujin.jeno.store.Paths.resetForTesting();
        System.clearProperty("jeno.github.token");
        System.clearProperty("jeno.minisign.key");
        System.clearProperty("jeno.minisign.pubkey");
        System.clearProperty("jeno.minisign.bin");
        System.clearProperty("jeno.github.api");
        System.clearProperty("jeno.github.uploads");
    }

    @Test
    void withoutATargetItPreparesAndListsTheUpload() throws Exception {
        Result result = run();

        assertEquals(0, result.exit(), result.out() + result.err());
        Path release = dir.resolve("target/dist/release");
        assertTrue(Files.exists(release.resolve("install.sh")));
        assertTrue(Files.exists(release.resolve("install.ps1")));
        String ps1 = Files.readString(release.resolve("install.ps1"));
        assertTrue(ps1.contains("Get-FileHash -Algorithm SHA256"), ps1);
        assertTrue(ps1.contains("tar.exe"), ps1);
        assertTrue(ps1.contains("$env:USERPROFILE\\.app"), ps1);
        assertTrue(ps1.contains("SetEnvironmentVariable(\"Path\", $UserPath, \"User\")"), ps1);
        assertTrue(Files.exists(release.resolve("app-1.0.0.tar.gz.sha256")));
        var json = JsonUtils.parseObject(
                Files.readString(release.resolve("release.json")).strip());
        assertEquals("app", json.getString("name"));
        assertEquals("1.0.0", json.getString("version"));
        assertEquals(17, json.getInt("jdk"));
        assertEquals("install.sh", json.getObject("installers").getString("unix"));
        assertEquals("install.ps1", json.getObject("installers").getString("windows"));
        assertEquals("app-1.0.0.tar.gz", json.getString("artifact"));
        String bootstrap = Files.readString(release.resolve("install.sh"));
        assertTrue(bootstrap.contains("file://" + dir.resolve("target/dist/app-1.0.0.tar.gz")),
                bootstrap);
        assertTrue(result.out().contains("upload these files together"), result.out());
        assertTrue(result.out().contains("bootstrap installer URL: "), result.out());
        assertTrue(result.out().contains("release/install.sh"), result.out());
    }

    @Test
    void aLocalTargetWritesTheVersionedAndStableLayouts() throws Exception {
        Path repo = dir.resolve("repo");

        Result result = run("jeno:local:" + repo);

        assertEquals(0, result.exit(), result.out() + result.err());
        Path versioned = repo.resolve("app/1.0.0");
        assertTrue(Files.exists(versioned.resolve("release/app-1.0.0.tar.gz")));
        assertTrue(Files.exists(versioned.resolve("release/install.sh")));
        assertTrue(Files.exists(versioned.resolve("release/release.json")));
        assertTrue(Files.exists(versioned.resolve("install.sh")));
        assertTrue(Files.exists(repo.resolve("app/install.sh")), "a stable entry exists");
        assertTrue(Files.exists(repo.resolve("app/install.ps1")),
                "the stable Windows entry exists");
    }

    @Test
    void dryRunWritesNothingToTheTarget() throws Exception {
        Path repo = dir.resolve("never");

        Result result = run("--dry-run", "jeno:local:" + repo);

        assertEquals(0, result.exit(), result.out() + result.err());
        assertFalse(Files.exists(repo), "dry-run never touches the target");
        assertFalse(Files.exists(dir.resolve("target/dist/release")),
                "dry-run never prepares local release files either");
        assertTrue(result.out().contains("dry run — would upload:"), result.out());
    }

    @Test
    void jsonDryRunIsOneDocumentAndWritesNothing() throws Exception {
        Path repo = dir.resolve("never-json");

        Result result = runRaw("--json", "--dry-run", "--allow-unsigned",
                "jeno:local:" + repo);

        assertEquals(0, result.exit(), result.err());
        assertFalse(Files.exists(repo));
        assertFalse(Files.exists(dir.resolve("target/dist/release")));
        var json = JsonUtils.parseObject(result.out());
        assertEquals("release", json.getString("command"));
        assertEquals(true, json.getBoolean("dryRun"));
        assertEquals(false, json.getBoolean("uploaded"));
        assertEquals("app", json.getObject("project").getString("name"));
        assertTrue(json.getArray("files").size() >= 2);
        assertTrue(json.getArray("wouldUpload").size() > 0);
    }

    @Test
    void jsonDryRunMissingTarCarriesDryRunTrue() throws Exception {
        Files.delete(dir.resolve("target/dist/app-1.0.0.tar.gz"));

        Result result = runRaw("--json", "--dry-run", "--allow-unsigned");

        assertEquals(1, result.exit());
        var json = JsonUtils.parseObject(result.out());
        assertEquals(true, json.getBoolean("dryRun"));
        assertEquals("RELEASE_PLAN_FAILED",
                json.getArray("diagnostics").getObject(0).getString("code"));
    }

    @Test
    void jsonPrivateLanDryRunCarriesThePlainHttpWarning() throws Exception {
        Result result = runRaw("--json", "--dry-run", "--allow-unsigned",
                "jeno:http://192.168.1.39:18080/upload/");

        assertEquals(0, result.exit(), result.err());
        assertFalse(Files.exists(dir.resolve("target/dist/release")));
        var json = JsonUtils.parseObject(result.out());
        assertEquals("PLAIN_HTTP_INSECURE",
                json.getArray("diagnostics").getObject(0).getString("code"));
        assertEquals("warning",
                json.getArray("diagnostics").getObject(0).getString("severity"));
    }

    @Test
    void jsonReleaseReportsPreparedFilesWithoutATarget() throws Exception {
        Result result = runRaw("--json", "--allow-unsigned");

        assertEquals(0, result.exit(), result.err());
        assertTrue(Files.exists(dir.resolve("target/dist/release/install.sh")));
        var json = JsonUtils.parseObject(result.out());
        assertEquals(false, json.getBoolean("dryRun"));
        assertEquals(false, json.getBoolean("uploaded"));
        assertTrue(json.getArray("files").size() >= 4);
    }

    @Test
    void jsonRemoteReleaseUnderOfflineIsRefusedBeforeUpload() throws Exception {
        ArtifactStore.goOffline();
        try {
            Result result = runRaw("--json", "--allow-unsigned", "gh:owner/repo");

            assertEquals(2, result.exit());
            var json = JsonUtils.parseObject(result.out());
            assertEquals("OFFLINE_FORBIDDEN",
                    json.getArray("diagnostics").getObject(0).getString("code"));
        } finally {
            resetOffline();
        }
    }

    @Test
    void anJenoRepositoryIsPutAndReadBack() throws Exception {
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
        String base = "jeno:http://localhost:" + server.getAddress().getPort() + "/repo/";
        fakeSigner();

        Result result = runRaw(base);

        assertEquals(0, result.exit(), result.out() + result.err());
        assertTrue(files.containsKey("/repo/app/1.0.0/release/app-1.0.0.tar.gz"));
        assertTrue(files.containsKey("/repo/app/1.0.0/release/install.sh"));
        assertTrue(files.containsKey("/repo/app/install.sh"), "the stable entry is uploaded");
        assertTrue(files.containsKey("/repo/app/1.0.0/release/app-1.0.0.tar.gz.minisig"),
                "the installer signature is uploaded");
        assertTrue(files.containsKey("/repo/app/1.0.0/release/minisign.pub"),
                "the public key is uploaded");
    }

    @Test
    void aGitHubTargetUploadsAndVerifies() throws Exception {
        Map<String, byte[]> assets = new ConcurrentHashMap<>();
        StringBuilder createBody = new StringBuilder();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/releases") && "POST".equals(exchange.getRequestMethod())) {
                createBody.append(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8));
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
        System.setProperty("jeno.github.api", base);
        System.setProperty("jeno.github.uploads", base);
        System.setProperty("jeno.github.token", "test-token");
        fakeSigner();

        Path notes = dir.resolve("notes.md");
        Files.writeString(notes, "# notes from the tag\n\nrelease notes text\n");
        Result result = runRaw("gh:owner/repo", "--notes-file", notes.toString());

        assertEquals(0, result.exit(), result.out() + result.err());
        assertTrue(createBody.toString().contains("release notes text"), createBody.toString());
        assertTrue(assets.containsKey("app-1.0.0.tar.gz"));
        assertTrue(assets.containsKey("install.sh"));
        assertTrue(assets.containsKey("install.ps1"));
        assertTrue(assets.containsKey("release.json"));
        assertTrue(assets.containsKey("app-1.0.0.tar.gz.minisig"));
        assertTrue(assets.containsKey("minisign.pub"));
    }

    @Test
    void notesFileRequiresGhAndMustExist() throws Exception {
        assertEquals(2, run("jeno:local:" + dir.resolve("repo"),
                "--notes-file", dir.resolve("missing.md").toString()).exit());
        assertEquals(2, run("gh:owner/repo",
                "--notes-file", dir.resolve("missing.md").toString()).exit());
    }

    @Test
    void withoutABundleItSaysWhatToRun() throws Exception {
        Files.delete(dir.resolve("target/dist/app-1.0.0.tar.gz"));

        Result result = run();

        assertEquals(1, result.exit());
        assertTrue(result.err().contains("run jeno bundle first"), result.err());
    }


    private static final String FAKE_PUB = "untrusted comment: fake key\nRWTfakePUBLICkeyLINE\n";

    /** A fake minisign: records args, writes a dummy signature, verifies nothing. */
    private void fakeSigner() throws Exception {
        Path bin = dir.resolve("fake-minisign.sh");
        Path key = dir.resolve("secret.key");
        Path pub = dir.resolve("minisign.pub");
        Files.writeString(key, "fake-secret");
        Files.writeString(pub, FAKE_PUB);
        Files.writeString(bin, """
            #!/bin/sh
            echo "$*" >> "%s"
            case "$*" in
              *"-S "*) for a in "$@"; do case "$prev" in -x) echo fake-signature > "$a";; esac; prev="$a"; done ;;
              *"-R "*) exit 0 ;;
              *"-V "*) exit 0 ;;
            esac
            exit 0
            """.formatted(dir.resolve("fake-minisign.log")));
        bin.toFile().setExecutable(true);
        System.setProperty("jeno.minisign.bin", bin.toString());
        System.setProperty("jeno.minisign.key", key.toString());
        System.setProperty("jeno.minisign.pubkey", pub.toString());
    }

    @Test
    void aSignedLocalReleaseCarriesSignaturesAndPublicKey() throws Exception {
        fakeSigner();
        Path repo = dir.resolve("signed");

        Result result = runRaw("jeno:local:" + repo);

        assertEquals(0, result.exit(), result.out() + result.err());
        Path release = repo.resolve("app/1.0.0/release");
        assertTrue(Files.exists(release.resolve("app-1.0.0.tar.gz.minisig")));
        assertTrue(Files.exists(release.resolve("install.sh.minisig")));
        assertTrue(Files.exists(release.resolve("install.ps1.minisig")));
        assertTrue(Files.exists(release.resolve("release.json.minisig")));
        assertTrue(Files.exists(release.resolve("minisign.pub")));
        assertTrue(Files.exists(repo.resolve("app/1.0.0/minisign.pub")));
        assertTrue(Files.exists(repo.resolve("app/minisign.pub")));
        String bootstrap = Files.readString(release.resolve("install.sh"));
        assertTrue(bootstrap.contains("minisign -V") || bootstrap.contains("\"$MINISIGN\" -V"),
                bootstrap);
        assertTrue(bootstrap.contains("RWTfakePUBLICkeyLINE"), bootstrap);
        var json = JsonUtils.parseObject(
                Files.readString(release.resolve("release.json")).strip());
        assertEquals(true, json.getBoolean("signed"));
        assertEquals("RWTfakePUBLICkeyLINE", json.getString("minisign.publicKey"));
    }

    @Test
    void aMissingKeyIsRefusedUnlessUnsignedIsExplicit() throws Exception {
        Result refused = runRaw();

        assertEquals(1, refused.exit());
        assertTrue(refused.err().contains("JENO_MINISIGN_KEY is not set"), refused.err());

        Result allowed = run("--allow-unsigned");
        assertEquals(0, allowed.exit(), allowed.out() + allowed.err());
        String bootstrap = Files.readString(dir.resolve("target/dist/release/install.sh"));
        assertTrue(bootstrap.contains("unsigned"), bootstrap);
        assertFalse(Files.exists(dir.resolve("target/dist/release/minisign.pub")));
    }

    @Test
    void aSigningFailureStopsBeforeAnyUpload() throws Exception {
        fakeSigner();
        Path bin = dir.resolve("failing-minisign.sh");
        Files.writeString(bin, "#!/bin/sh\nexit 1\n");
        bin.toFile().setExecutable(true);
        System.setProperty("jeno.minisign.bin", bin.toString());
        Path repo = dir.resolve("never");

        Result result = runRaw("jeno:local:" + repo);

        assertEquals(1, result.exit());
        assertTrue(result.err().contains("signing"), result.err());
        assertFalse(Files.exists(repo), "nothing is uploaded after a signing failure");
    }

    private static void resetOffline() {
        try {
            var field = ArtifactStore.class.getDeclaredField("offline");
            field.setAccessible(true);
            field.set(null, false);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private Result run(String... args) {
        List<String> withUnsigned = new java.util.ArrayList<>(List.of(args));
        withUnsigned.add("--allow-unsigned");
        return runRaw(withUnsigned.toArray(String[]::new));
    }

    private Result runRaw(String... args) {
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
                dir.resolve("target/dist/release/install.sh").toString())
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
    void anJenoTargetThatRejectsTheUploadFails() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> respond(exchange, 500, "no"));
        server.start();
        String base = "jeno:http://localhost:" + server.getAddress().getPort() + "/repo/";

        Result result = run(base);

        assertEquals(1, result.exit(), result.out() + result.err());
        assertTrue(result.err().contains("release upload failed"), result.err());
    }

    @Test
    void aGitHubTargetWithoutATokenFailsBeforeUploading() throws Exception {
        Result result = run("gh:owner/repo");

        assertEquals(1, result.exit());
        assertTrue(result.err().contains("JENO_GITHUB_TOKEN"), result.err());
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
