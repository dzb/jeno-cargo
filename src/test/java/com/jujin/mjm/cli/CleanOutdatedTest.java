package com.jujin.mjm.cli;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code mjm clean} and {@code mjm outdated} — the two commands that close the
 * "housekeeping" gap. Neither is clever; what matters is what they refuse to
 * touch and how they classify what they cannot check.
 */
class CleanOutdatedTest {

    @TempDir
    Path dir;

    // ------------------------------------------------------------------
    // clean
    // ------------------------------------------------------------------

    @Test
    void cleanRemovesTargetAndDist() throws Exception {
        Files.createDirectories(dir.resolve("target/classes/demo"));
        Files.createDirectories(dir.resolve("dist/demo-1.0.0/lib"));
        Files.writeString(dir.resolve("target/classes/demo/A.class"), "x");
        Files.writeString(dir.resolve("dist/demo-1.0.0.tar.gz"), "y");

        assertEquals(0, new CleanCommand(dir).execute(List.of()));

        assertFalse(Files.exists(dir.resolve("target")), "target/ is gone");
        assertFalse(Files.exists(dir.resolve("dist")), "dist/ is gone");
    }

    @Test
    void cleanTargetKeepsDeliverables() throws Exception {
        Files.createDirectories(dir.resolve("target/classes"));
        Files.createDirectories(dir.resolve("dist"));
        Files.writeString(dir.resolve("dist/demo-1.0.0.jar"), "ship me");

        assertEquals(0, new CleanCommand(dir).execute(List.of("--target")));

        assertFalse(Files.exists(dir.resolve("target")));
        assertTrue(Files.exists(dir.resolve("dist/demo-1.0.0.jar")),
                "--target exists so a build can be cleaned without losing what you ship");
    }

    @Test
    void cleanDistKeepsTheBuild() throws Exception {
        Files.createDirectories(dir.resolve("target/classes"));
        Files.createDirectories(dir.resolve("dist"));

        assertEquals(0, new CleanCommand(dir).execute(List.of("--dist")));

        assertTrue(Files.isDirectory(dir.resolve("target/classes")));
        assertFalse(Files.exists(dir.resolve("dist")));
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
                "mjm refuses snapshots, so it must not offer one");
    }

    @Test
    void aDashMarksAPreRelease() {
        assertTrue(OutdatedCommand.allowsPrerelease("2.0.0-rc1"));
        assertTrue(OutdatedCommand.allowsPrerelease("2.0.0-alpha1"));
        assertFalse(OutdatedCommand.allowsPrerelease("2.0.0"));
        assertFalse(OutdatedCommand.allowsPrerelease("30.0-jre"),
                "a qualifier like -jre is not a pre-release marker");
    }

    // ------------------------------------------------------------------
    // outdated: the report
    // ------------------------------------------------------------------

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
        System.setProperty("mjm.central.base",
                "http://localhost:" + server.getAddress().getPort() + "/");
        try {
            projectWithLock(centralRow("com.example:lib-a", "1.0.0"));

            String out = runOutdated(1);

            assertTrue(out.contains("could not check"), out);
            assertFalse(out.contains("everything is up to date"),
                    "a failed comparison is not an up-to-date answer: " + out);
        } finally {
            server.stop(0);
            System.clearProperty("mjm.central.base");
        }
    }

    @Test
    void aFreshPinSaysUpToDateWhenSomethingWasActuallyChecked() throws Exception {
        HttpServer server = server(200, metadata("1.0.0"));
        System.setProperty("mjm.central.base",
                "http://localhost:" + server.getAddress().getPort() + "/");
        try {
            projectWithLock(centralRow("com.example:lib-a", "1.0.0"));

            String out = runOutdated(0);

            assertTrue(out.contains("everything is up to date"), out);
        } finally {
            server.stop(0);
            System.clearProperty("mjm.central.base");
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
        System.setProperty("mjm.central.base",
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
            System.clearProperty("mjm.central.base");
        }
    }

    // ------------------------------------------------------------------
    // fixtures for the report tests
    // ------------------------------------------------------------------

    private static final String SHA = "0".repeat(64);

    private void projectWithLock(String rows) throws Exception {
        Files.writeString(dir.resolve("mjm.toml"), """
            [project]
            name = "demo"
            version = "0.1.0"
            """);
        Files.writeString(dir.resolve("mjm.lock"), """
            # generated by mjm
            %s
            """.formatted(rows));
    }

    private String centralRow(String id, String version) {
        String base = System.getProperty("mjm.central.base");
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
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int exit;
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            exit = new OutdatedCommand(dir).execute(List.of());
        } finally {
            System.setOut(original);
        }
        assertEquals(expectedExit, exit);
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
