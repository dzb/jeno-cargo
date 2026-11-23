package com.jujin.jeno.cli;

import com.jujin.jeno.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code jeno verify}: stale declarations, store corruption and built outputs. */
class VerifyCommandTest {

    @TempDir
    Path root;

    @Test
    void aHealthyProjectWithAStoreAndBuiltArtifactsPasses() throws Exception {
        Fixture fixture = fixture("1.0.0", true);
        Path dist = Files.createDirectories(fixture.project.resolve("target/dist"));
        Path jar = dist.resolve("demo-0.1.0.jar");
        Files.writeString(jar, "built-jar");
        Files.writeString(dist.resolve("demo-0.1.0.jeno.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                """);
        Files.writeString(dist.resolve("demo-0.1.0.sha256"),
                ArtifactStore.sha256(jar) + "\n");
        Path bundle = Files.createDirectories(dist.resolve("demo-0.1.0"));
        Path setup = bundle.resolve("setup.sh");
        Files.writeString(setup, "#!/bin/sh\n");
        Files.writeString(bundle.resolve("SHA256SUMS"),
                ArtifactStore.sha256(setup) + "  setup.sh\n");

        Result result = verify(fixture);

        assertEquals(0, result.exit(), result.out() + result.err());
        assertTrue(result.out().contains("direct declarations match the lock (1)"),
                result.out());
        assertTrue(result.out().contains("store bytes verified (1 jars, 1 metadata, 0 local)"),
                result.out());
        assertTrue(result.out().contains("publish triple is internally consistent"), result.out());
        assertTrue(result.out().contains("bundle SHA256SUMS verified (1 files)"), result.out());
        assertTrue(result.out().contains("verify: ok"), result.out());
    }

    @Test
    void aMissingStoreJarFailsWithTheNextAction() throws Exception {
        Fixture fixture = fixture("1.0.0", false);

        Result result = verify(fixture);

        assertEquals(1, result.exit());
        assertTrue(result.out().contains("jar is not in the store — run jeno lock"), result.out());
        assertTrue(result.out().contains("verify: failed"), result.out());
    }

    @Test
    void aStaleDeclarationVersionIsNamed() throws Exception {
        Fixture fixture = fixture("1.0.0", true);
        Files.writeString(fixture.project.resolve("jeno.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"

                [deps]
                "com.example:lib" = "1.1.0"
                """);

        Result result = verify(fixture);

        assertEquals(1, result.exit());
        assertTrue(result.out().contains("jeno.toml 1.1.0, jeno.lock 1.0.0"), result.out());
    }

    @Test
    void aCorruptStoreJarIsNamed() throws Exception {
        Fixture fixture = fixture("1.0.0", true);
        Path stored = fixture.storeRoot().resolve(fixture.jarSha + ".jar");
        Files.writeString(stored, "tampered");

        Result result = verify(fixture);

        assertEquals(1, result.exit());
        assertTrue(result.out().contains("store jar digest does not match jeno.lock"),
                result.out());
    }

    @Test
    void missingBuiltOutputsAreSkippedNotFailed() throws Exception {
        Fixture fixture = fixture("1.0.0", true);

        Result result = verify(fixture);

        assertEquals(0, result.exit(), result.out());
        assertTrue(result.out().contains("artifacts: no target/dist (skipped)"), result.out());
    }

    @Test
    void aPackageOnlyJarIsSkippedAsNotPrepared() throws Exception {
        Fixture fixture = fixture("1.0.0", true);
        Path dist = Files.createDirectories(fixture.project.resolve("target/dist"));
        Files.writeString(dist.resolve("demo-0.1.0.jar"), "built-jar");

        Result result = verify(fixture);

        assertEquals(0, result.exit(), result.out());
        assertTrue(result.out().contains(
                "publish triple: not prepared (package artifact present;"),
                result.out());
        assertTrue(result.out().contains("jeno publish writes the descriptor and sha256"),
                result.out());
    }

    @Test
    void aBundleDirectoryWithoutSumsIsIncomplete() throws Exception {
        Fixture fixture = fixture("1.0.0", true);
        Path dist = Files.createDirectories(fixture.project.resolve("target/dist"));
        Files.createDirectories(dist.resolve("demo-0.1.0"));

        Result result = verify(fixture, "--json");

        assertEquals(1, result.exit(), result.out());
        var root = com.jujin.freeway.commons.json.JsonUtils.parseObject(
                result.out().strip());
        boolean incomplete = root.getArray("diagnostics").toList().stream()
                .anyMatch(value -> value instanceof java.util.Map<?, ?> diagnostic
                        && "artifacts.bundle-incomplete".equals(diagnostic.get("code")));
        assertTrue(incomplete, result.out());
        assertTrue(result.out().contains("run jeno bundle"), result.out());
    }

    @Test
    void aJarWithADescriptorButNoShaIsStillIncomplete() throws Exception {
        Fixture fixture = fixture("1.0.0", true);
        Path dist = Files.createDirectories(fixture.project.resolve("target/dist"));
        Files.writeString(dist.resolve("demo-0.1.0.jar"), "built-jar");
        Files.writeString(dist.resolve("demo-0.1.0.jeno.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                """);

        Result result = verify(fixture);

        assertEquals(1, result.exit(), result.out());
        assertTrue(result.out().contains("publish triple is incomplete"), result.out());
    }

    @Test
    void jsonReportCarriesStableChecksAndSummary() throws Exception {
        Fixture fixture = fixture("1.0.0", true);

        Result result = verify(fixture, "--json");

        assertEquals(0, result.exit(), result.out() + result.err());
        var root = com.jujin.freeway.commons.json.JsonUtils.parseObject(
                result.out().strip());
        assertEquals("verify", root.getString("command"));
        assertEquals("ok", root.getString("status"));
        assertEquals("single", root.getObject("workspace").getString("mode"));
        assertEquals(1, root.getArray("modules").size());
        var module = root.getArray("modules").getObject(0);
        assertEquals("ok", module.getString("status"));
        var checks = module.getArray("checks");
        assertTrue(checks.toList().stream()
                .map(value -> ((java.util.Map<?, ?>) value).get("id"))
                .anyMatch("declarations"::equals));
        assertEquals(0, root.getObject("summary").getInt("failedChecks"));
    }

    @Test
    void jsonWorkspaceIsOneDocumentWithDeterministicModules() throws Exception {
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        for (String name : List.of("libs/core", "apps/web")) {
            Path module = Files.createDirectories(workspace.resolve(name));
            Files.writeString(module.resolve("jeno.toml"), """
                    [project]
                    name = "%s"
                    version = "0.1.0"
                    """.formatted(name.substring(name.indexOf('/') + 1)));
            Files.writeString(module.resolve("jeno.lock"),
                    "# generated by jeno\n");
        }
        Files.writeString(workspace.resolve("jeno.ws.toml"), """
                [ws]
                modules = ["libs/core", "apps/web"]
                """);
        ArtifactStore store = new ArtifactStore(root.resolve("store"));

        Result result = verifyAt(store, workspace, "--json");

        assertEquals(0, result.exit(), result.out() + result.err());
        var rootJson = com.jujin.freeway.commons.json.JsonUtils.parseObject(
                result.out().strip());
        assertEquals("all", rootJson.getObject("workspace").getString("mode"));
        var modules = rootJson.getArray("modules");
        assertEquals(2, modules.size());
        assertEquals("apps/web", modules.getObject(0).getString("module"));
        assertEquals("libs/core", modules.getObject(1).getString("module"));
        assertEquals(2, rootJson.getObject("summary").getInt("modules"));
        assertEquals(0, rootJson.getObject("summary").getInt("failedModules"));
    }

    @Test
    void jsonFailureIsStillOneDocumentWithADiagnosticCode() throws Exception {
        Fixture fixture = fixture("1.0.0", false);

        Result result = verify(fixture, "--json");

        assertEquals(1, result.exit());
        var root = com.jujin.freeway.commons.json.JsonUtils.parseObject(
                result.out().strip());
        assertEquals("failed", root.getString("status"));
        var diagnostics = root.getArray("diagnostics");
        boolean storeMissing = false;
        for (Object value : diagnostics.toList()) {
            if (value instanceof java.util.Map<?, ?> diagnostic
                    && "store.jar-missing".equals(diagnostic.get("code"))) {
                storeMissing = true;
            }
        }
        assertTrue(storeMissing, result.out());
        assertEquals("", result.err());
    }

    // ------------------------------------------------------------------

    private Fixture fixture(String lockedVersion, boolean putBytesInStore) throws Exception {
        Path project = Files.createDirectories(root.resolve("project"));
        Files.writeString(project.resolve("jeno.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"

                [deps]
                "com.example:lib" = "1.0.0"
                """);

        byte[] jarBytes = "dependency-jar".getBytes(StandardCharsets.UTF_8);
        byte[] pomBytes = "<project/>".getBytes(StandardCharsets.UTF_8);
        String jarSha = ArtifactStore.sha256(jarBytes);
        String pomSha = ArtifactStore.sha256(pomBytes);
        Path store = Files.createDirectories(root.resolve("store"));
        ArtifactStore artifactStore = new ArtifactStore(store);
        if (putBytesInStore) {
            Files.write(store.resolve(jarSha + ".jar"), jarBytes);
            Files.write(store.resolve(pomSha + ".pom"), pomBytes);
        }
        Files.writeString(project.resolve("jeno.lock"), """
                [[artifacts]]
                id = "com.example:lib"
                version = "%s"
                source = "central"
                url = "https://repo.example/lib-1.0.0.jar"
                sha256 = "%s"
                meta-sha256 = "%s"
                depth = 1
                """.formatted(lockedVersion, jarSha, pomSha));
        return new Fixture(project, artifactStore, store, jarSha);
    }

    private static Result verify(Fixture fixture, String... args) {
        return verifyAt(fixture.store(), fixture.project(), args);
    }

    private static Result verifyAt(ArtifactStore store, Path project, String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            exit = new VerifyCommand(store, project).execute(List.of(args));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new Result(exit, out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
    }

    private record Fixture(Path project, ArtifactStore store, Path storeRoot, String jarSha) {}

    private record Result(int exit, String out, String err) {}
}
