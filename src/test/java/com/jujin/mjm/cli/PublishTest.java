package com.jujin.mjm.cli;

import com.jujin.mjm.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The decentralized release (DESIGN.md §7.2): one command, three files, no
 * credentials — and repo-bound dependencies are refused before they can
 * produce an unresolvable descriptor.
 */
class PublishTest {

    @TempDir
    Path dir;

    @Test
    void anOldDistAloneGetsTheMigrationHint() throws Exception {
        writeProject(dir);
        Files.createDirectories(dir.resolve("target/classes/demo"));
        Files.writeString(dir.resolve("target/classes/demo/A.class"), "class bytes");
        Files.createDirectories(dir.resolve("dist"));
        Files.writeString(dir.resolve("dist/demo-1.0.0.jar"), "old deliverable");

        java.io.PrintStream original = System.err;
        var errors = new java.io.ByteArrayOutputStream();
        int exit;
        try {
            System.setErr(new java.io.PrintStream(errors, true,
                    java.nio.charset.StandardCharsets.UTF_8));
            exit = new PublishCommand(dir).execute(List.of());
        } finally {
            System.setErr(original);
        }

        String message = errors.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(1, exit);
        assertTrue(message.contains("deliverables moved to target/dist"), message);
        assertTrue(message.contains("rerun mjm package"), message);
    }

    @Test
    void publishGeneratesTheTriple() throws Exception {
        writeProject(dir);
        Files.createDirectories(dir.resolve("target/classes/demo"));
        Files.writeString(dir.resolve("target/classes/demo/A.class"), "class bytes");

        // publish uploads what package built — it never builds its own jar,
        // so the fixture provides a real jar where package would have put one
        Path dist = Files.createDirectories(dir.resolve("target/dist"));
        try (var out = new java.util.zip.ZipOutputStream(
                Files.newOutputStream(dist.resolve("demo-1.0.0.jar")))) {
            out.putNextEntry(new java.util.zip.ZipEntry("demo/A.class"));
            out.write("class bytes".getBytes());
            out.closeEntry();
        }

        int exit = new PublishCommand(dir).execute(List.of());

        assertEquals(0, exit);
        Path jar = dir.resolve("target/dist/demo-1.0.0.jar");
        assertTrue(Files.exists(jar));
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            assertTrue(zip.getEntry("demo/A.class") != null, "the jar packs the build output");
        }
        String sha = Files.readString(dir.resolve("target/dist/demo-1.0.0.sha256")).strip();
        assertEquals(64, sha.length(), "the sha file holds the 64-hex digest");

        String descriptor = Files.readString(dir.resolve("target/dist/demo-1.0.0.mjm.toml"));
        assertTrue(descriptor.contains("name = \"demo\""));
        assertTrue(descriptor.contains("version = \"1.0.0\""));
        assertTrue(descriptor.contains("\"com.example:lib-a\" = \"1.0.0\""));
        assertTrue(descriptor.contains("\"mylib\" = \"gh:acme/mylib@v1.2.0\""),
                "native requirements round-trip with their tag");
        assertFalse(descriptor.contains("[repos]"), "a descriptor carries facts, not rules");

        // a target without a token triggers the upload path and fails —
        // the checklist is now printed only when no target is given
        assertEquals(1, new PublishCommand(dir).execute(
                        List.of("gh:acme/demo")),
                "a target without a token prompts for MJM_GITHUB_TOKEN");
    }

    @Test
    void jsonDryRunPlansTheTripleWithoutWritingIt() throws Exception {
        writeProject(dir);
        Files.createDirectories(dir.resolve("target/classes/demo"));
        Files.writeString(dir.resolve("target/classes/demo/A.class"), "class bytes");
        Path dist = Files.createDirectories(dir.resolve("target/dist"));
        Files.writeString(dist.resolve("demo-1.0.0.jar"), "jar bytes");

        Result result = publishJson("--json", "--dry-run");

        assertEquals(0, result.exit());
        assertFalse(Files.exists(dist.resolve("demo-1.0.0.mjm.toml")),
                "dry-run never writes the descriptor");
        assertFalse(Files.exists(dist.resolve("demo-1.0.0.sha256")),
                "dry-run never writes the checksum");
        var json = com.jujin.freeway.commons.json.JsonUtils.parseObject(result.out());
        assertEquals("publish", json.getString("command"));
        assertEquals(true, json.getBoolean("dryRun"));
        assertEquals(false, json.getBoolean("uploaded"));
        assertEquals(3, json.getArray("artifacts").size());
        assertEquals("descriptor", json.getArray("artifacts").getObject(1).getString("kind"));
        assertEquals(true, json.getArray("artifacts").getObject(1).getBoolean("wouldWrite"));
    }

    @Test
    void jsonPrivateLanDryRunCarriesThePlainHttpWarning() throws Exception {
        writeProject(dir);
        Files.createDirectories(dir.resolve("target/classes/demo"));
        Files.writeString(dir.resolve("target/classes/demo/A.class"), "class bytes");
        Path dist = Files.createDirectories(dir.resolve("target/dist"));
        Files.writeString(dist.resolve("demo-1.0.0.jar"), "jar bytes");

        Result result = publishJson("--json", "--dry-run",
                "mjm:http://192.168.1.39:18080/upload/");

        assertEquals(0, result.exit(), result.err());
        var json = com.jujin.freeway.commons.json.JsonUtils.parseObject(result.out());
        assertEquals("PLAIN_HTTP_INSECURE",
                json.getArray("diagnostics").getObject(0).getString("code"));
        assertEquals("warning",
                json.getArray("diagnostics").getObject(0).getString("severity"));
        assertFalse(Files.exists(dist.resolve("demo-1.0.0.mjm.toml")),
                "the warning path is still a side-effect-free dry-run");
    }

    @Test
    void jsonPublishReportsTheWrittenTriple() throws Exception {
        writeProject(dir);
        Files.createDirectories(dir.resolve("target/classes/demo"));
        Files.writeString(dir.resolve("target/classes/demo/A.class"), "class bytes");
        Path dist = Files.createDirectories(dir.resolve("target/dist"));
        Files.writeString(dist.resolve("demo-1.0.0.jar"), "jar bytes");

        Result result = publishJson("--json");

        assertEquals(0, result.exit());
        assertTrue(Files.exists(dist.resolve("demo-1.0.0.mjm.toml")));
        assertTrue(Files.exists(dist.resolve("demo-1.0.0.sha256")));
        var json = com.jujin.freeway.commons.json.JsonUtils.parseObject(result.out());
        assertEquals(false, json.getBoolean("dryRun"));
        assertEquals(false, json.getBoolean("uploaded"));
        var artifacts = json.getArray("artifacts");
        assertEquals(3, artifacts.size());
        assertTrue(artifacts.getObject(1).getString("sha256").matches("[0-9a-f]{64}"));
    }

    @Test
    void jsonRemotePublishUnderOfflineIsRefusedBeforeUpload() throws Exception {
        writeProject(dir);
        Files.createDirectories(dir.resolve("target/classes/demo"));
        Files.writeString(dir.resolve("target/classes/demo/A.class"), "class bytes");
        Files.createDirectories(dir.resolve("target/dist"));
        Files.writeString(dir.resolve("target/dist/demo-1.0.0.jar"), "jar bytes");
        ArtifactStore.goOffline();
        try {
            Result result = publishJson("--json", "gh:acme/demo");
            assertEquals(2, result.exit());
            var json = com.jujin.freeway.commons.json.JsonUtils.parseObject(result.out());
            assertEquals("OFFLINE_FORBIDDEN",
                    json.getArray("diagnostics").getObject(0).getString("code"));
        } finally {
            resetOffline();
        }
    }

    @Test
    void aRepoBoundDependencyCannotPublish() throws Exception {
        writeProject(dir);
        Files.createDirectories(dir.resolve("target/classes"));
        Files.writeString(dir.resolve("mjm.toml"), """
            [project]
            name = "demo"
            version = "1.0.0"

            [repos]
            corp = "https://nexus.corp.internal/repository/maven/"

            [deps.corp]
            "com.corp:internal" = "2.1.0"
            """);

        int exit = new PublishCommand(dir).execute(List.of());

        assertEquals(1, exit, "a repo-bound dependency produces an unresolvable descriptor");
    }

    @Test
    void buildOutputIsRequired() throws Exception {
        writeProject(dir);
        int exit = new PublishCommand(dir).execute(List.of());
        assertEquals(1, exit, "publish packs the build output; no build, no publish");
    }

    private static void writeProject(Path dir) throws IOException {
        Files.writeString(dir.resolve("mjm.toml"), """
            [project]
            name = "demo"
            version = "1.0.0"

            [deps]
            "com.example:lib-a" = "1.0.0"
            mylib = "gh:acme/mylib@v1.2.0"
            """);
    }
    private Result publishJson(String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            exit = new PublishCommand(dir).execute(List.of(args));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new Result(exit, out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
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

    private record Result(int exit, String out, String err) {}

}
