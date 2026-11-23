package com.jujin.jeno.cli;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code jeno clean} and {@code jeno outdated} — the two commands that close the
 * "housekeeping" gap. Neither is clever; what matters is what they refuse to
 * touch and how they classify what they cannot check.
 */
class CleanOutdatedTest {

    @TempDir
    Path dir;

    @org.junit.jupiter.api.BeforeEach
    void isolateProjectLocks() {
        ProjectLock.useRoot(dir.resolve(".locks"));
    }

    @org.junit.jupiter.api.AfterEach
    void restoreGuards() {
        CleanCommand.useKernelMountDetector();
        ProjectLock.useUserRoot();
    }

    // ------------------------------------------------------------------
    // clean
    // ------------------------------------------------------------------

    @Test
    void cleanRemovesTheWholeTargetTreeByDefault() throws Exception {
        Files.createDirectories(dir.resolve("target/classes/demo"));
        Files.createDirectories(dir.resolve("target/dist/demo-1.0.0/lib"));
        Files.writeString(dir.resolve("target/classes/demo/A.class"), "x");
        Files.writeString(dir.resolve("target/dist/demo-1.0.0.tar.gz"), "y");

        assertEquals(0, new CleanCommand(dir).execute(List.of()));

        assertFalse(Files.exists(dir.resolve("target")),
                "the whole build output, deliverables included");
    }

    @Test
    void cleanTargetIsTheSameAsNoFlag() throws Exception {
        Files.createDirectories(dir.resolve("target/classes"));
        Files.createDirectories(dir.resolve("target/dist"));
        Files.writeString(dir.resolve("target/dist/demo-1.0.0.jar"), "ship me");

        assertEquals(0, new CleanCommand(dir).execute(List.of("--target")));

        assertFalse(Files.exists(dir.resolve("target")),
                "--target is the default, spelled out for scripts");
    }

    @Test
    void cleanDistKeepsTheBuild() throws Exception {
        Files.createDirectories(dir.resolve("target/classes"));
        Files.createDirectories(dir.resolve("target/dist"));

        assertEquals(0, new CleanCommand(dir).execute(List.of("--dist")));

        assertTrue(Files.isDirectory(dir.resolve("target/classes")));
        assertFalse(Files.exists(dir.resolve("target/dist")));
    }

    @Test
    void bothFlagsRemoveBothDirectories() throws Exception {
        Files.createDirectories(dir.resolve("target/classes"));
        Files.createDirectories(dir.resolve("target/dist"));

        assertEquals(0, new CleanCommand(dir).execute(List.of("--target", "--dist")));

        assertFalse(Files.exists(dir.resolve("target")), "target/ is gone");
        assertFalse(Files.exists(dir.resolve("target/dist")), "--dist said so explicitly");
    }

    @Test
    void cleanDistRemovesTheSymlinkNotItsTarget() throws Exception {
        Files.createDirectories(dir.resolve("target/classes"));
        Path victim = Files.createDirectories(dir.resolve("victim"));
        Files.writeString(victim.resolve("keep.txt"), "keep");
        Files.createSymbolicLink(dir.resolve("target/dist"), victim);

        assertEquals(0, new CleanCommand(dir).execute(List.of("--dist")));

        assertFalse(Files.exists(dir.resolve("target/dist"), LinkOption.NOFOLLOW_LINKS),
                "the link itself is removed");
        assertTrue(Files.exists(victim.resolve("keep.txt")),
                "what it pointed at is not ours to delete");
        assertTrue(Files.isDirectory(dir.resolve("target/classes")), "classes are kept");
    }

    @Test
    void cleanRemovesASymlinkWithoutFollowingIt() throws Exception {
        Path victim = Files.createDirectories(dir.resolve("victim"));
        Files.writeString(victim.resolve("keep.txt"), "keep");
        Files.createSymbolicLink(dir.resolve("target"), victim);

        assertEquals(0, new CleanCommand(dir).execute(List.of()));

        assertFalse(Files.exists(dir.resolve("target"), LinkOption.NOFOLLOW_LINKS),
                "the link itself is removed");
        assertTrue(Files.exists(victim.resolve("keep.txt")),
                "what the link pointed at is not ours to delete");
    }

    @Test
    void aMountInEitherRequestedTreeRefusesTheWholeRun() throws Exception {
        Files.createDirectories(dir.resolve("target/classes"));
        Files.createDirectories(dir.resolve("target/dist"));
        Files.writeString(dir.resolve("target/classes/A.class"), "x");
        CleanCommand.useMountDetector(tree ->
                tree.getFileName().toString().equals("dist") ? List.of(tree) : List.of());

        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        PrintStream original = System.err;
        int exit;
        try {
            System.setErr(new PrintStream(errors, true, StandardCharsets.UTF_8));
            exit = new CleanCommand(dir).execute(List.of("--target", "--dist"));
        } finally {
            System.setErr(original);
        }

        String message = errors.toString(StandardCharsets.UTF_8);
        assertEquals(1, exit);
        assertTrue(message.contains("dist"), "the refusal names the tree with the mount: " + message);
        assertTrue(Files.exists(dir.resolve("target/classes/A.class")),
                "nothing is removed when the other requested tree contains a mount");
    }

    @Test
    void aMountInATreeNobodyAskedForDoesNotBlock() throws Exception {
        Files.createDirectories(dir.resolve("target/classes"));
        Files.createDirectories(dir.resolve("target/dist"));
        // target/ is a mount, but only --dist was requested
        CleanCommand.useMountDetector(tree ->
                tree.getFileName().toString().equals("target") ? List.of(tree) : List.of());

        assertEquals(0, new CleanCommand(dir).execute(List.of("--dist")));

        assertFalse(Files.exists(dir.resolve("target/dist")), "the requested tree is removed");
        assertTrue(Files.isDirectory(dir.resolve("target/classes")),
                "an unrequested tree is not inspected, so a mount there blocks nothing");
    }

    @Test
    void theFallbackFindsANestedFileStore() throws Exception {
        Path root = Files.createDirectories(dir.resolve("target"));
        Files.createDirectories(root.resolve("classes"));
        Files.createDirectories(root.resolve("cache"));
        Map<String, Object> stores = Map.of(
                "target", "dev1",
                "target/classes", "dev1",
                "target/cache", "tmpfs");

        List<Path> mounts = CleanCommand.nestedFileStores(root,
                path -> stores.getOrDefault(dir.relativize(path).toString(), "dev1"));

        assertEquals(List.of(root.resolve("cache")), mounts,
                "a nested directory on another store is a mount; siblings on the same one are not");
    }

    @Test
    void onlyRemovingTheRunningClassesAsksForTheEarlyExit() {
        Path target = dir.resolve("target");
        assertTrue(CleanCommand.removedOwnClasses(true, target, target.resolve("classes")),
                "target/ removed and the JVM ran from it");
        assertFalse(CleanCommand.removedOwnClasses(false, target, target.resolve("classes")),
                "--dist did not remove target/, so nothing to escape");
        assertFalse(CleanCommand.removedOwnClasses(true, target, dir.resolve("elsewhere")),
                "the JVM did not run from target/");
        assertFalse(CleanCommand.removedOwnClasses(true, target, null),
                "unknown self location: let the container close normally");
    }

    @Test
    void cleanLeavesTheProjectLockAlone() throws Exception {
        Files.createDirectories(dir.resolve("target/classes"));
        Path lock = ProjectLock.lockFile(dir);
        assertFalse(lock.startsWith(dir.resolve("target")),
                "a lock inside target/ would be deleted by clean itself");

        assertEquals(0, new CleanCommand(dir).execute(List.of()));

        assertFalse(Files.exists(dir.resolve("target")), "target/ is gone");
        assertTrue(Files.exists(lock), "the lock file survives clean — it is not target/'s to delete");
    }

    @Test
    void jsonCleanReportsTheRemovedTargetTree() throws Exception {
        Files.createDirectories(dir.resolve("target/classes/demo"));
        Files.writeString(dir.resolve("target/classes/demo/A.class"), "class-bytes");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        int exit;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            exit = new CleanCommand(dir).execute(List.of("--json"));
        } finally {
            System.setOut(original);
        }

        assertEquals(0, exit);
        assertFalse(Files.exists(dir.resolve("target")));
        var json = com.jujin.freeway.commons.json.JsonUtils.parseObject(
                out.toString(StandardCharsets.UTF_8));
        assertEquals("clean", json.getString("command"));
        var result = json.getArray("modules").getObject(0).getObject("result");
        assertEquals(1, result.getInt("removedCount"));
        assertEquals("target", result.getArray("removed").getObject(0).getString("kind"));
        assertTrue(result.getArray("removed").getObject(0).getLong("bytes") > 0);
    }

    @Test
    void cleanSaysWhenThereIsNothingToDo() throws Exception {
        java.io.PrintStream original = System.out;
        var buffer = new java.io.ByteArrayOutputStream();
        try {
            System.setOut(new java.io.PrintStream(buffer, true,
                    java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(0, new CleanCommand(dir).execute(List.of()));
        } finally {
            System.setOut(original);
        }
        assertTrue(buffer.toString(java.nio.charset.StandardCharsets.UTF_8)
                        .contains("nothing to clean"),
                "saying so beats pretending work happened");
    }

    @Test
    void cleanRejectsUnknownOptions() {
        assertEquals(2, new CleanCommand(dir).execute(List.of("--everything")));
    }

    // ------------------------------------------------------------------
    // outdated: version selection
    // ------------------------------------------------------------------

    @Test
    void aStablePinNeverSeesAPrereleaseAsAnUpdate() {
        List<String> available = List.of("2.0.0", "2.0.20", "2.1.0-alpha1", "2.1.0");
        assertEquals("2.1.0", OutdatedCommand.newest(available, false));
    }

    @Test
    void aPrereleasePinMaySeePrereleases() {
        List<String> available = List.of("2.0.0", "2.1.0-alpha1");
        assertEquals("2.1.0-alpha1", OutdatedCommand.newest(available, true));
        assertEquals("2.0.0", OutdatedCommand.newest(available, false));
    }

    @Test
    void snapshotsAreNeverCandidates() {
        List<String> available = List.of("2.0.0", "2.5.0-SNAPSHOT");
        assertEquals("2.0.0", OutdatedCommand.newest(available, true),
                "jeno refuses snapshots, so it must not offer one");
    }

    @Test
    void qualifierTokensDecidePrereleases() {
        assertTrue(OutdatedCommand.allowsPrerelease("2.0.0-rc1"), "rc1 is a release candidate");
        assertTrue(OutdatedCommand.allowsPrerelease("2.0.0-m1"), "m1 is a milestone");
        assertTrue(OutdatedCommand.allowsPrerelease("2.0.0-alpha1"));
        assertFalse(OutdatedCommand.allowsPrerelease("1.0.0-main"),
                "-main starts with m but is not a milestone");
        assertFalse(OutdatedCommand.allowsPrerelease("30.0-jre"),
                "a qualifier like -jre is not a pre-release marker");
        assertFalse(OutdatedCommand.allowsPrerelease("2.0.0.Final"),
                "a dot suffix is part of the number, not a qualifier");
    }

    @Test
    void aLowercaseSnapshotIsNeverACandidate() {
        assertEquals("2.0.0", OutdatedCommand.newest(
                        List.of("2.0.0", "2.5.0-snapshot"), true),
                "the snapshot suffix is case-insensitive");
    }

    // ------------------------------------------------------------------
    // outdated: the report
    // ------------------------------------------------------------------

    @Test
    void anJenoRepositoryIsNotCheckedAndSaysWhy() throws Exception {
        projectWithLock("""
            [[artifacts]]
            id = "widget"
            version = "1.0.0"
            source = "jeno:https://repo.example/"
            url = "https://repo.example/widget/1.0.0/widget-1.0.0.jar"
            sha256 = "%s"
            meta-sha256 = "%s"
            depth = 1
            """.formatted(SHA, SHA));

        String out = runOutdated(0);

        assertTrue(out.contains("not checked"), out);
        assertTrue(out.contains("no version index"), out);
        assertFalse(out.contains("everything is up to date"), out);
    }

    @Test
    void theJsonReportHasTheSameThreeSections() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        byte[] minor = metadata("1.0.0", "1.1.0");
        server.createContext("/com/example/lib-a/maven-metadata.xml", exchange -> {
            exchange.sendResponseHeaders(200, minor.length);
            exchange.getResponseBody().write(minor);
            exchange.close();
        });
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        System.setProperty("jeno.central.base",
                "http://localhost:" + server.getAddress().getPort() + "/");
        try {
            projectWithLock(centralRow("com.example:lib-a", "1.0.0")
                    + centralRow("com.example:lib-c", "1.0.0") + """
                [[artifacts]]
                id = "acme:mylib"
                version = "1.0.0"
                source = "gh:acme/mylib"
                url = "https://github.com/acme/mylib/releases/download/v1.0.0/mylib-1.0.0.jar"
                sha256 = "%s"
                meta-sha256 = "%s"
                depth = 1
                """.formatted(SHA, SHA));

            String out = runOutdated(1, "--json");

            var json = com.jujin.freeway.commons.json.JsonUtils.parseObject(out);
            assertEquals(2, json.getInt("schemaVersion"));
            assertEquals("outdated", json.getString("command"));
            assertEquals("ok", json.getString("status"));
            assertEquals(0, json.getInt("exitCode"));
            assertEquals(false, json.getBoolean("upToDate"));
            var updates = json.getArray("updates");
            assertEquals(1, updates.size());
            assertEquals("com.example:lib-a", updates.getObject(0).getString("id"));
            assertEquals("1.0.0", updates.getObject(0).getString("pinned"));
            assertEquals("1.1.0", updates.getObject(0).getString("available"));
            assertEquals(false, updates.getObject(0).getBoolean("major"));
            var skipped = json.getArray("notChecked");
            assertEquals(1, skipped.size());
            assertEquals("native-release", skipped.getObject(0).getString("reason"));
            var failures = json.getArray("failures");
            assertEquals(1, failures.size());
            assertEquals("com.example:lib-c", failures.getObject(0).getString("id"));
        } finally {
            server.stop(0);
            System.clearProperty("jeno.central.base");
        }
    }

    @Test
    void theJsonReportSaysUpToDateWhenItIs() throws Exception {
        HttpServer server = server(200, metadata("1.0.0"));
        System.setProperty("jeno.central.base",
                "http://localhost:" + server.getAddress().getPort() + "/");
        try {
            projectWithLock(centralRow("com.example:lib-a", "1.0.0"));

            var json = com.jujin.freeway.commons.json.JsonUtils.parseObject(
                    runOutdated(0, "--json"));

            assertEquals(true, json.getBoolean("upToDate"));
            assertEquals(0, json.getArray("updates").size());
            assertEquals(0, json.getArray("notChecked").size());
            assertEquals(0, json.getArray("failures").size());
        } finally {
            server.stop(0);
            System.clearProperty("jeno.central.base");
        }
    }

    @Test
    void nothingCheckedMeansNoUpToDateClaim() throws Exception {
        projectWithLock("""
            [[artifacts]]
            id = "acme:mylib"
            version = "1.0.0"
            source = "gh:acme/mylib"
            url = "https://github.com/acme/mylib/releases/download/v1.0.0/mylib-1.0.0.jar"
            sha256 = "%s"
            meta-sha256 = "%s"
            depth = 1
            """.formatted(SHA, SHA));

        String out = runOutdated(0);

        assertTrue(out.contains("not checked"), out);
        assertFalse(out.contains("everything is up to date"),
                "nothing was compared, so nothing is 'up to date': " + out);
    }

    @Test
    void aFailedLookupAlsoSuppressesTheUpToDateClaim() throws Exception {
        HttpServer server = server(404, null);
        System.setProperty("jeno.central.base",
                "http://localhost:" + server.getAddress().getPort() + "/");
        try {
            projectWithLock(centralRow("com.example:lib-a", "1.0.0"));

            String out = runOutdated(1);

            assertTrue(out.contains("could not check"), out);
            assertFalse(out.contains("everything is up to date"),
                    "a failed comparison is not an up-to-date answer: " + out);
        } finally {
            server.stop(0);
            System.clearProperty("jeno.central.base");
        }
    }

    @Test
    void aFreshPinSaysUpToDateWhenSomethingWasActuallyChecked() throws Exception {
        HttpServer server = server(200, metadata("1.0.0"));
        System.setProperty("jeno.central.base",
                "http://localhost:" + server.getAddress().getPort() + "/");
        try {
            projectWithLock(centralRow("com.example:lib-a", "1.0.0"));

            String out = runOutdated(0);

            assertTrue(out.contains("everything is up to date"), out);
        } finally {
            server.stop(0);
            System.clearProperty("jeno.central.base");
        }
    }

    @Test
    void updatesAndCrossMajorChangesHaveTheirOwnSections() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        byte[] minor = metadata("1.0.0", "1.1.0");
        byte[] major = metadata("1.0.0", "2.0.0");
        server.createContext("/com/example/lib-a/maven-metadata.xml", exchange -> {
            exchange.sendResponseHeaders(200, minor.length);
            exchange.getResponseBody().write(minor);
            exchange.close();
        });
        server.createContext("/com/example/lib-b/maven-metadata.xml", exchange -> {
            exchange.sendResponseHeaders(200, major.length);
            exchange.getResponseBody().write(major);
            exchange.close();
        });
        server.start();
        System.setProperty("jeno.central.base",
                "http://localhost:" + server.getAddress().getPort() + "/");
        try {
            projectWithLock(centralRow("com.example:lib-a", "1.0.0")
                    + centralRow("com.example:lib-b", "1.0.0"));

            String out = runOutdated(0);

            assertTrue(out.contains("updates available:"), out);
            assertTrue(out.contains("com.example:lib-a  1.0.0 → 1.1.0"), out);
            assertTrue(out.contains("major version changes"), out);
            assertTrue(out.contains("com.example:lib-b  1.0.0 → 2.0.0"), out);
            assertFalse(out.contains("everything is up to date"), out);
        } finally {
            server.stop(0);
            System.clearProperty("jeno.central.base");
        }
    }

    @Test
    void aMixedClosureIsNeverCalledUpToDate() throws Exception {
        HttpServer server = server(200, metadata("1.0.0"));
        System.setProperty("jeno.central.base",
                "http://localhost:" + server.getAddress().getPort() + "/");
        try {
            projectWithLock(centralRow("com.example:lib-a", "1.0.0") + """
                [[artifacts]]
                id = "acme:mylib"
                version = "1.0.0"
                source = "gh:acme/mylib"
                url = "https://github.com/acme/mylib/releases/download/v1.0.0/mylib-1.0.0.jar"
                sha256 = "%s"
                meta-sha256 = "%s"
                depth = 1
                """.formatted(SHA, SHA));

            String out = runOutdated(0);

            assertTrue(out.contains("not checked"), out);
            assertFalse(out.contains("everything is up to date"),
                    "one row was not compared, so the closure is not \"up to date\": " + out);
        } finally {
            server.stop(0);
            System.clearProperty("jeno.central.base");
        }
    }

    @Test
    void aClassifierRowChecksTheBaseArtifact() throws Exception {
        HttpServer server = server(200, metadata("1.0.0", "1.1.0"));
        System.setProperty("jeno.central.base",
                "http://localhost:" + server.getAddress().getPort() + "/");
        try {
            // the classifier is part of the identity, never of the metadata path
            projectWithLock(centralRow("com.example:lib-a:linux", "1.0.0"));

            String out = runOutdated(0);

            assertTrue(out.contains("com.example:lib-a:linux  1.0.0 → 1.1.0"),
                    "the metadata is the base artifact's, got: " + out);
        } finally {
            server.stop(0);
            System.clearProperty("jeno.central.base");
        }
    }

    @Test
    void anEmptyVersionListIsAFailureNotAnUpdate() throws Exception {
        HttpServer server = server(200, metadata());
        System.setProperty("jeno.central.base",
                "http://localhost:" + server.getAddress().getPort() + "/");
        try {
            projectWithLock(centralRow("com.example:lib-a", "1.0.0"));

            String out = runOutdated(1);

            assertTrue(out.contains("no versions listed"), out);
            assertFalse(out.contains("everything is up to date"), out);
        } finally {
            server.stop(0);
            System.clearProperty("jeno.central.base");
        }
    }

    @Test
    void theRequestTimeoutIsClamped() {
        try {
            System.setProperty("jeno.http.request.timeout.ms", "0");
            assertEquals(java.time.Duration.ofMillis(100), MetadataVersions.requestTimeout(),
                    "0 would mean no deadline at all: raised to the floor");

            System.setProperty("jeno.http.request.timeout.ms", "999999999");
            assertEquals(java.time.Duration.ofMillis(120_000), MetadataVersions.requestTimeout(),
                    "a huge value is a hang by another name: capped at the ceiling");

            System.clearProperty("jeno.http.request.timeout.ms");
            assertEquals(java.time.Duration.ofMillis(30_000), MetadataVersions.requestTimeout(),
                    "the shipped default");
        } finally {
            System.clearProperty("jeno.http.request.timeout.ms");
        }
    }

    @Test
    void aServerThatNeverAnswersFailsInBoundedTime() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread acceptor = new Thread(() -> {
                try (Socket held = socket.accept()) {
                    Thread.sleep(30_000);  // accept, then never write a byte
                } catch (Exception ignored) {
                    // the client gave up; nothing to clean here
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            System.setProperty("jeno.central.base",
                    "http://127.0.0.1:" + socket.getLocalPort() + "/");
            System.setProperty("jeno.http.request.timeout.ms", "700");
            projectWithLock(centralRow("com.example:lib-a", "1.0.0"));

            long start = System.nanoTime();
            String out = runOutdated(1);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertTrue(elapsedMs < 10_000,
                    "a silent server must not hang the command; took " + elapsedMs + "ms");
            assertTrue(out.contains("could not check"), out);
        } finally {
            System.clearProperty("jeno.central.base");
            System.clearProperty("jeno.http.request.timeout.ms");
        }
    }

    // ------------------------------------------------------------------
    // fixtures for the report tests
    // ------------------------------------------------------------------

    private static final String SHA = "0".repeat(64);

    private void projectWithLock(String rows) throws Exception {
        Files.writeString(dir.resolve("jeno.toml"), """
            [project]
            name = "demo"
            version = "0.1.0"
            """);
        Files.writeString(dir.resolve("jeno.lock"), """
            # generated by jeno
            %s
            """.formatted(rows));
    }

    private String centralRow(String id, String version) {
        String base = System.getProperty("jeno.central.base");
        int colon = id.indexOf(':');
        String group = id.substring(0, colon);
        String artifact = id.substring(colon + 1);
        String url = base + group.replace('.', '/') + "/" + artifact + "/" + version + "/"
                + artifact + "-" + version + ".jar";
        return """
            [[artifacts]]
            id = "%s"
            version = "%s"
            source = "central"
            url = "%s"
            sha256 = "%s"
            meta-sha256 = "%s"
            depth = 1
            """.formatted(id, version, url, SHA, SHA);
    }

    private static byte[] metadata(String... versions) {
        StringBuilder body = new StringBuilder("<metadata><versioning><versions>");
        for (String version : versions) {
            body.append("<version>").append(version).append("</version>");
        }
        return body.append("</versions></versioning></metadata>").toString().getBytes();
    }

    private static HttpServer server(int status, byte[] body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            if (status != 200) {
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    private String runOutdated(int expectedExit) {
        return runOutdated(expectedExit, new String[0]);
    }

    private String runOutdated(int expectedExit, String... args) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int exit;
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            exit = new OutdatedCommand(dir).execute(List.of(args));
        } finally {
            System.setOut(original);
        }
        assertEquals(expectedExit, exit);
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @Test
    void outdatedChecksMainOnlyUnlessDevIsGiven() throws Exception {
        projectWithLock("""
            [[artifacts]]
            id = "acme:main-lib"
            version = "1.0.0"
            source = "gh:acme/main-lib"
            url = "https://github.com/acme/main-lib/releases/download/v1.0.0/main-lib-1.0.0.jar"
            sha256 = "%1$s"
            depth = 1

            [[artifacts]]
            id = "acme:dev-lib"
            version = "1.0.0"
            source = "gh:acme/dev-lib"
            url = "https://github.com/acme/dev-lib/releases/download/v1.0.0/dev-lib-1.0.0.jar"
            sha256 = "%1$s"
            depth = 1
            scope = "dev"

            [[artifacts]]
            id = "acme:proc"
            version = "1.0.0"
            source = "gh:acme/proc"
            url = "https://github.com/acme/proc/releases/download/v1.0.0/proc-1.0.0.jar"
            sha256 = "%1$s"
            depth = 1
            scope = "processor"
            """.formatted(SHA));

        var byDefault = com.jujin.freeway.commons.json.JsonUtils.parseObject(
                runOutdated(0, "--json"));
        assertEquals(1, byDefault.getArray("notChecked").size(),
                "only the MAIN row is in scope by default");

        var withDev = com.jujin.freeway.commons.json.JsonUtils.parseObject(
                runOutdated(0, "--json", "--dev"));
        assertEquals(3, withDev.getArray("notChecked").size(),
                "--dev includes dev and processor rows");
    }

    @Test
    void anUnrecognizedSourceIsNotCheckedNotAFailure() throws Exception {
        projectWithLock("""
            [[artifacts]]
            id = "acme:weird"
            version = "1.0.0"
            source = "something-unparseable"
            url = "https://host/weird-1.0.0.jar"
            sha256 = "%s"
            depth = 1
            """.formatted(SHA));

        var json = com.jujin.freeway.commons.json.JsonUtils.parseObject(
                runOutdated(0, "--json"));

        var skipped = json.getArray("notChecked");
        assertEquals(1, skipped.size(), "the row is reported, not dropped");
        assertEquals("acme:weird", skipped.getObject(0).getString("id"));
        assertEquals("named-repository", skipped.getObject(0).getString("reason"));
        assertEquals(0, json.getArray("failures").size(), "not a failure");
    }

    @Test
    void outdatedNeedsOnlyTheLock() throws Exception {
        Files.writeString(dir.resolve("jeno.lock"), """
            # generated by jeno
            [[artifacts]]
            id = "acme:lib"
            version = "1.0.0"
            source = "gh:acme/lib"
            url = "https://github.com/acme/lib/releases/download/v1.0.0/lib-1.0.0.jar"
            sha256 = "%s"
            depth = 1
            """.formatted(SHA));

        var json = com.jujin.freeway.commons.json.JsonUtils.parseObject(
                runOutdated(0, "--json"));

        assertEquals(1, json.getArray("notChecked").size(),
                "a directory with a lock but no jeno.toml still answers");
    }
}
