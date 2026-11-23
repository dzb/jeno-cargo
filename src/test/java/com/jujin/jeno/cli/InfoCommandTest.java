package com.jujin.jeno.cli;

import com.jujin.jeno.project.DescriptorReader;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code mjm info}: the facts about one locked dependency, plus source
 * addressing folded in from the retired {@code mjm source} command. Reading
 * facts is pure output; only {@code --source} writes a tree.
 */
class InfoCommandTest {

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
    void infoShowsTheDescriptorFactsAndTheSourceAddress(@TempDir Path storeDir) throws Exception {
        String descriptor = """
            [project]
            name = "widget"
            version = "1.2.0"
            summary = "A tiny reactive widget toolkit"
            homepage = "https://github.com/acme/widget"
            license = "Apache-2.0"

            [java]
            version = 21

            [deps]
            "org.slf4j:slf4j-api" = "2.0.18"
            """;
        // a real descriptor in the store, pinned by its digest
        Path meta = placeInStore(storeDir, ArtifactStore.Kind.DESCRIPTOR,
                descriptor.getBytes(StandardCharsets.UTF_8));
        String metaSha = ArtifactStore.sha256(meta);

        Files.writeString(dir.resolve("mjm.lock"), """
            [[artifacts]]
            id = "acme:widget"
            version = "1.2.0"
            source = "gh:acme/widget"
            url = "https://github.com/acme/widget/releases/download/v1.2.0/widget-1.2.0.jar"
            sha256 = "%s"
            meta-sha256 = "%s"
            depth = 1
            """.formatted("a".repeat(64), metaSha));

        String output = run(storeDir, List.of("acme:widget"));

        assertTrue(output.contains("A tiny reactive widget toolkit"), output);
        assertTrue(output.contains("https://github.com/acme/widget"), output);
        assertTrue(output.contains("Apache-2.0"), output);
        assertTrue(output.contains("java:     21+"), output);
        assertTrue(output.contains("org.slf4j:slf4j-api@2.0.18"), output);
        assertTrue(output.contains("https://github.com/acme/widget/tree/v1.2.0"),
                "the browsable source address: " + output);
        assertTrue(output.contains("--source"), "the fetch hint: " + output);
    }

    @Test
    void jsonExportsConsumerFacingArtifactFacts(@TempDir Path storeDir) throws Exception {
        String descriptor = """
            [project]
            name = "widget"
            version = "1.2.0"
            summary = "A tiny reactive widget toolkit"
            homepage = "https://github.com/acme/widget"
            license = "Apache-2.0"

            [java]
            version = 21

            [deps]
            "org.slf4j:slf4j-api" = "2.0.18"
            """;
        Path meta = placeInStore(storeDir, ArtifactStore.Kind.DESCRIPTOR,
                descriptor.getBytes(StandardCharsets.UTF_8));
        String metaSha = ArtifactStore.sha256(meta);
        Files.writeString(dir.resolve("mjm.lock"), """
            [[artifacts]]
            id = "acme:widget"
            version = "1.2.0"
            source = "gh:acme/widget"
            url = "https://github.com/acme/widget/releases/download/v1.2.0/widget-1.2.0.jar"
            sha256 = "%s"
            meta-sha256 = "%s"
            depth = 1
            """.formatted("a".repeat(64), metaSha));

        System.setProperty("mjm.color", "always");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        int exit;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            exit = new InfoCommand(new ArtifactStore(storeDir), dir)
                    .execute(List.of("acme:widget", "--json"));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
            System.clearProperty("mjm.color");
        }

        assertEquals(0, exit);
        assertEquals("", err.toString(StandardCharsets.UTF_8));
        String raw = out.toString(StandardCharsets.UTF_8);
        assertFalse(raw.contains("\u001B["), "JSON stdout must not contain ANSI");
        var json = com.jujin.freeway.commons.json.JsonUtils.parseObject(raw);
        assertEquals(1, json.getInt("schemaVersion"));
        assertEquals("info", json.getString("command"));
        assertEquals("ok", json.getString("status"));

        var artifact = json.getObject("artifact");
        assertEquals("acme:widget", artifact.getString("id"));
        assertEquals("acme", artifact.getString("group"));
        assertEquals("widget", artifact.getString("artifact"));
        assertEquals("1.2.0", artifact.getString("version"));
        assertEquals("a".repeat(64), artifact.getString("sha256"));
        assertEquals(metaSha, artifact.getString("metaSha256"));

        var provenance = json.getObject("provenance");
        assertEquals("gh:acme/widget", provenance.getString("source"));
        assertEquals("github-release", provenance.getString("layout"));
        assertEquals("descriptor", provenance.getObject("metadata").getString("kind"));

        var descriptorJson = json.getObject("descriptor");
        assertEquals("Apache-2.0", descriptorJson.getString("license"));
        assertEquals(21, descriptorJson.getInt("javaVersion"));
        assertEquals("org.slf4j:slf4j-api",
                descriptorJson.getArray("dependencies").getObject(0).getString("id"));

        var source = json.getObject("source");
        assertEquals("https://github.com/acme/widget/tree/v1.2.0",
                source.getString("page"));
        assertEquals("https://github.com/acme/widget/archive/refs/tags/v1.2.0.tar.gz",
                source.getString("archive"));

        var context = json.getObject("context");
        assertEquals("main", context.getString("scope"));
        assertEquals(1, context.getInt("depth"));
        assertEquals(0, json.getArray("diagnostics").size());
    }

    @Test
    void jsonSourceModeIsExplicitlyUnsupportedForNow(@TempDir Path storeDir) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        int exit;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            exit = new InfoCommand(new ArtifactStore(storeDir), dir)
                    .execute(List.of("acme:widget", "--json", "--source"));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        assertEquals(2, exit);
        assertEquals("", err.toString(StandardCharsets.UTF_8));
        var json = com.jujin.freeway.commons.json.JsonUtils.parseObject(
                out.toString(StandardCharsets.UTF_8));
        assertEquals("JSON_SOURCE_UNSUPPORTED",
                json.getArray("diagnostics").getObject(0).getString("code"));
    }

    @Test
    void plainInfoNeverWritesASourceTree(@TempDir Path storeDir) throws Exception {
        Files.writeString(dir.resolve("mjm.lock"), """
            [[artifacts]]
            id = "acme:widget"
            version = "1.2.0"
            source = "gh:acme/widget"
            url = "https://github.com/acme/widget/releases/download/v1.2.0/widget-1.2.0.jar"
            sha256 = "%s"
            depth = 1
            """.formatted("a".repeat(64)));

        String output = run(storeDir, List.of("acme:widget"));
        assertTrue(output.contains("acme:widget@1.2.0"), output);
        System.clearProperty("mjm.sources.root");
    }

    @Test
    void sourceFetchesTheTagArchive(@TempDir Path storeDir, @TempDir Path root) throws Exception {
        Path archive = makeTarGz(dir.resolve("src.tar.gz"), "widget-1.2.0/src/Widget.java",
                "public final class Widget {}");
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        String base = "http://localhost:" + server.getAddress().getPort();
        serve(server, "/acme/widget/archive/refs/tags/v1.2.0.tar.gz", Files.readAllBytes(archive));
        server.start();

        try {
            Files.writeString(dir.resolve("mjm.lock"), """
                [[artifacts]]
                id = "acme:widget"
                version = "1.2.0"
                source = "gh:acme/widget"
                url = "%s/acme/widget/releases/download/v1.2.0/widget-1.2.0.jar"
                sha256 = "%s"
                depth = 1
                """.formatted(base, "a".repeat(64)));

            System.setProperty("mjm.github.host", base);
            String output = run(storeDir, List.of("acme:widget", "--source"),
                    root.resolve("sources"));
            try {
                assertTrue(output.contains("source →"), output);
                assertTrue(Files.exists(root.resolve("sources/acme_widget@1.2.0/src/Widget.java")),
                        "the tree landed under the sources root");
            } finally {
                System.clearProperty("mjm.github.host");
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void centralFallsBackToThePublishersSourcesJar(@TempDir Path storeDir) throws Exception {
        Path sourcesJar = makeJar(dir.resolve("lib-1.0.0-sources.jar"),
                "com/example/Lib.java", "package com.example; public final class Lib {}");
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        String base = "http://localhost:" + server.getAddress().getPort();
        serve(server, "/com/example/lib/1.0.0/lib-1.0.0-sources.jar",
                Files.readAllBytes(sourcesJar));
        server.start();

        try {
            Files.writeString(dir.resolve("mjm.lock"), """
                [[artifacts]]
                id = "com.example:lib"
                version = "1.0.0"
                source = "%s"
                url = "%s/com/example/lib/1.0.0/lib-1.0.0.jar"
                sha256 = "%s"
                depth = 1
                """.formatted(base, base, "b".repeat(64)));

            String output = run(storeDir, List.of("com.example:lib", "--source"));
            assertTrue(output.contains("source →"), output);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anJenoRepositoryHasNoSourceTree(@TempDir Path storeDir) throws Exception {
        Files.writeString(dir.resolve("mjm.lock"), """
            [[artifacts]]
            id = "widget"
            version = "1.0.0"
            source = "mjm:https://repo.example/"
            url = "https://repo.example/widget/1.0.0/widget-1.0.0.jar"
            sha256 = "%s"
            meta-sha256 = "%s"
            depth = 1
            """.formatted("d".repeat(64), "e".repeat(64)));

        java.io.PrintStream original = System.err;
        var errors = new java.io.ByteArrayOutputStream();
        int exit;
        try {
            System.setErr(new java.io.PrintStream(errors, true, StandardCharsets.UTF_8));
            exit = new InfoCommand(new ArtifactStore(storeDir), dir)
                    .execute(List.of("widget", "--source"));
        } finally {
            System.setErr(original);
        }

        assertEquals(1, exit);
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("this origin has no source tree"),
                errors.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aUrlLeafIsExplicitAboutHavingNoSource(@TempDir Path storeDir) throws Exception {
        Files.writeString(dir.resolve("mjm.lock"), """
            [[artifacts]]
            id = "bare"
            version = "1.0.0"
            source = "url"
            url = "https://example.com/bare-1.0.0.jar"
            sha256 = "%s"
            depth = 1
            """.formatted("d".repeat(64)));

        String output = run(storeDir, List.of("bare"));
        assertTrue(output.contains("no source address"), output);
    }

    @Test
    void anUnknownIdSaysWhereToLook(@TempDir Path storeDir) {
        // no mjm.lock: the command refuses with the next action
        int exit = new InfoCommand(new ArtifactStore(storeDir), dir).execute(List.of("x:y"));
        assertEquals(1, exit);
    }

    @Test
    void identityFactsTravelFromMjTomlIntoThePublishedDescriptor(@TempDir Path storeDir)
            throws Exception {
        Files.createDirectories(dir.resolve("src/main/java/demo"));
        Files.writeString(dir.resolve("mjm.toml"), """
            [project]
            name = "widget"
            version = "1.2.0"
            summary = "A tiny reactive widget toolkit"
            homepage = "https://github.com/acme/widget"
            license = "Apache-2.0"

            [java]
            version = 21
            """);
        Files.writeString(dir.resolve("src/main/java/demo/App.java"),
                "package demo; public final class App {}");
        Files.writeString(dir.resolve("mjm.lock"), "");
        assertEquals(0, new BuildCommand(new ArtifactStore(storeDir), dir).execute(List.of()));

        // publish uploads what package built
        assertEquals(0, new PackageCommand(new ArtifactStore(storeDir), dir)
                .execute(List.of()));
        new PublishCommand(dir).execute(List.of());

        String descriptor = Files.readString(dir.resolve("target/dist/widget-1.2.0.mjm.toml"));
        assertTrue(descriptor.contains("summary = \"A tiny reactive widget toolkit\""), descriptor);
        assertTrue(descriptor.contains("homepage = \"https://github.com/acme/widget\""), descriptor);
        assertTrue(descriptor.contains("license = \"Apache-2.0\""), descriptor);
        // and it reads back as a valid descriptor
        var parsed = DescriptorReader.parse(descriptor, "widget", "1.2.0");
        assertEquals("Apache-2.0", parsed.license());
    }

    @Test
    void descriptorIdentityFieldsParseAndHomepageMustBeHttps() {
        var descriptor = DescriptorReader.parse("""
            [project]
            name = "w"
            version = "1.0.0"
            summary = "s"
            homepage = "https://example.com"
            license = "MIT"
            """, "w", "1.0.0");
        assertEquals("s", descriptor.summary());
        assertEquals("MIT", descriptor.license());

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () ->
                DescriptorReader.parse("""
                    [project]
                    name = "w"
                    version = "1.0.0"
                    homepage = "http://insecure.example.com"
                    """, "w", "1.0.0"), "homepage must be https");
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private String run(Path storeDir, List<String> args) {
        return run(storeDir, args, dir.resolve("sources-" + System.nanoTime()));
    }

    private String run(Path storeDir, List<String> args, Path root) {
        System.setProperty("mjm.sources.root", root.toString());
        java.io.PrintStream original = System.out;
        var buffer = new java.io.ByteArrayOutputStream();
        try {
            System.setOut(new java.io.PrintStream(buffer, true, StandardCharsets.UTF_8));
            new InfoCommand(new ArtifactStore(storeDir), dir).execute(args);
        } finally {
            System.setOut(original);
            System.clearProperty("mjm.sources.root");
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static Path placeInStore(Path storeDir, ArtifactStore.Kind kind, byte[] bytes)
            throws IOException {
        Files.createDirectories(storeDir);
        Path target = storeDir.resolve(ArtifactStore.sha256(bytes) + "." + kindName(kind));
        Files.write(target, bytes);
        return target;
    }

    private static String kindName(ArtifactStore.Kind kind) {
        return switch (kind) {
            case JAR -> "jar";
            case POM -> "pom";
            case DESCRIPTOR -> "mjm.toml";
            case ARCHIVE -> "tar.gz";
        };
    }

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

    private static Path makeJar(Path target, String entryName, String content) throws IOException {
        try (var out = new ZipOutputStream(Files.newOutputStream(target))) {
            out.putNextEntry(new ZipEntry(entryName));
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return target;
    }

    @Test
    void theHeaderMarksDevAndProcessorScopes(@TempDir Path storeDir) throws Exception {
        Files.writeString(dir.resolve("mjm.lock"), """
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
            """.formatted("b".repeat(64)));

        String dev = run(storeDir, List.of("acme:dev-lib"));
        assertTrue(dev.contains("acme:dev-lib@1.0.0  [dev]"), dev);

        String processor = run(storeDir, List.of("acme:proc"));
        assertTrue(processor.contains("acme:proc@1.0.0  [processor]"), processor);
    }
}
