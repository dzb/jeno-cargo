package com.jujin.mj.cli;

import com.jujin.mj.store.ArtifactStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code mj source}: source is addressed by the tag the lockfile already pins,
 * never published as a separate artifact. Native releases come from the tag
 * archive; Central dependencies fall back to the publisher's own sources jar.
 */
class SourceCommandTest {

    @TempDir
    Path dir;

    @Test
    void aNativeReleaseYieldsItsTagArchive(@TempDir Path storeDir, @TempDir Path home) throws Exception {
        // a real tar.gz containing one source file
        Path archive = makeTarGz(dir.resolve("src.tar.gz"), "widget-1.2.0/src/Widget.java",
                "public final class Widget {}");
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        String base = "http://localhost:" + server.getAddress().getPort();
        serve(server, "/acme/widget/archive/refs/tags/v1.2.0.tar.gz", Files.readAllBytes(archive));
        server.start();

        try {
            Files.writeString(dir.resolve("mj.lock"), """
                [[artifacts]]
                id = "acme:widget"
                version = "1.2.0"
                source = "gh:acme/widget"
                url = "%s/acme/widget/releases/download/v1.2.0/widget-1.2.0.jar"
                sha256 = "%s"
                depth = 1
                """.formatted(base, "a".repeat(64)));

            System.setProperty("mj.github.host", base);
            System.setProperty("mj.sources.root", home.toString());
            int exit;
            try {
                exit = new SourceCommand(new ArtifactStore(storeDir), dir)
                        .execute(List.of("acme:widget"));
            } finally {
                System.clearProperty("mj.github.host");
            }

            assertEquals(0, exit, "the tag archive was fetched and unpacked");
            Path source = home.resolve("acme_widget@1.2.0/src/Widget.java");
            assertTrue(Files.exists(source), "the source tree landed under ~/.mj/sources");
            assertTrue(Files.readString(source).contains("class Widget"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aCentralDependencyFallsBackToItsSourcesJar(@TempDir Path storeDir, @TempDir Path home)
            throws Exception {
        Path sourcesJar = makeJar(dir.resolve("lib-1.0.0-sources.jar"),
                "com/example/Lib.java", "package com.example; public final class Lib {}");
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        String base = "http://localhost:" + server.getAddress().getPort();
        serve(server, "/com/example/lib/1.0.0/lib-1.0.0-sources.jar",
                Files.readAllBytes(sourcesJar));
        server.start();

        try {
            Files.writeString(dir.resolve("mj.lock"), """
                [[artifacts]]
                id = "com.example:lib"
                version = "1.0.0"
                source = "%s"
                url = "%s/com/example/lib/1.0.0/lib-1.0.0.jar"
                sha256 = "%s"
                depth = 1
                """.formatted(base, base, "b".repeat(64)));

            System.setProperty("mj.sources.root", home.toString());
            int exit = new SourceCommand(new ArtifactStore(storeDir), dir)
                    .execute(List.of("com.example:lib"));

            assertEquals(0, exit, "the publisher's sources jar was fetched");
            assertTrue(Files.exists(home.resolve("com.example_lib@1.0.0/"
                    + "com/example/Lib.java")), "the jar was unpacked");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aPublisherWithoutSourcesIsRefusedHelpfully(@TempDir Path storeDir, @TempDir Path home)
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        String base = "http://localhost:" + server.getAddress().getPort();
        serve(server, "/com/example/lib/1.0.0/lib-1.0.0-sources.jar", null);  // 404
        server.start();

        try {
            Files.writeString(dir.resolve("mj.lock"), """
                [[artifacts]]
                id = "com.example:lib"
                version = "1.0.0"
                source = "%s"
                url = "%s/com/example/lib/1.0.0/lib-1.0.0.jar"
                sha256 = "%s"
                depth = 1
                """.formatted(base, base, "c".repeat(64)));

            System.setProperty("mj.sources.root", home.toString());
            int exit = new SourceCommand(new ArtifactStore(storeDir), dir)
                    .execute(List.of("com.example:lib"));

            assertEquals(1, exit, "no sources jar is a failure, not a silent empty tree");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aUrlLeafHasNoSourceToAddress(@TempDir Path storeDir, @TempDir Path home) throws Exception {
        Files.writeString(dir.resolve("mj.lock"), """
            [[artifacts]]
            id = "bare"
            version = "1.0.0"
            source = "url"
            url = "https://example.com/bare-1.0.0.jar"
            sha256 = "%s"
            depth = 1
            """.formatted("d".repeat(64)));

        System.setProperty("mj.sources.root", home.toString());
        int exit = new SourceCommand(new ArtifactStore(storeDir), dir).execute(List.of("bare"));
        assertEquals(1, exit, "a URL leaf carries no tag to resolve source from");
        assertFalse(Files.exists(home.resolve("bare@1.0.0")));
    }

    @Test
    void anUnknownIdNamesTheLookupTool(@TempDir Path storeDir, @TempDir Path home) throws Exception {
        Files.writeString(dir.resolve("mj.lock"), "");
        System.setProperty("mj.sources.root", home.toString());
        assertEquals(1, new SourceCommand(new ArtifactStore(storeDir), dir)
                .execute(List.of("com.example:absent")));
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static void serve(HttpServer server, String path, byte[] body) {
        server.createContext(path, exchange -> {
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
    }

    /** A real gzipped tar containing one file. */
    private static Path makeTarGz(Path target, String entryName, String content) throws Exception {
        Path staging = Files.createTempDirectory("src-staging");
        Path file = staging.resolve(entryName);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        Process tar = new ProcessBuilder("tar", "-czf", target.toString(),
                "-C", staging.toString(), entryName.split("/")[0])
                .inheritIO().start();
        assertEquals(0, tar.waitFor(), "fixture tar built");
        return target;
    }

    /** A zip (jar) containing one file. */
    private static Path makeJar(Path target, String entryName, String content) throws IOException {
        try (var out = new ZipOutputStream(Files.newOutputStream(target))) {
            out.putNextEntry(new ZipEntry(entryName));
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return target;
    }
}
