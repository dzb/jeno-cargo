package com.jujin.mjm.cli;

import com.jujin.mjm.project.Species;
import com.jujin.mjm.resolve.ResolvedGraph;
import com.jujin.mjm.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The uber-jar's merge rules (DESIGN.md: 内置零依赖): services merge
 * line-by-line, signatures and module-info drop, the manifest is mj's own,
 * and {@code java -jar} runs it.
 */
class PackageTest {

    @TempDir
    Path dir;

    @Test
    void packagesAndRuns(@TempDir Path storeDir) throws Exception {
        Path repo = dir.resolve("fix-repo");
        writeProject();
        fixtureJar(repo.resolve("com/example/lib-a/1.0.0/lib-a-1.0.0.jar"),
                Map.of("com/example/lib/a/Dep.class", "dep bytes"));
        fixturePom(repo.resolve("com/example/lib-a/1.0.0/lib-a-1.0.0.pom"),
                "com.example", "lib-a", "1.0.0");

        // the store takes the closure jar; the lock pins the session digest
        ArtifactStore store = new ArtifactStore(storeDir);
        Path fixture = repo.resolve("com/example/lib-a/1.0.0/lib-a-1.0.0.jar");
        String fixtureUrl = fixture.toUri().toString();
        store.ensure(fixtureUrl, ArtifactStore.Kind.JAR);
        ResolvedGraph.ResolvedArtifact libA = new ResolvedGraph.ResolvedArtifact(
                "com.example:lib-a", "1.0.0", Species.CENTRAL, "local:fix-repo",
                fixtureUrl, null, 1, true);
        writeLock(store, List.of(libA));

        // mjm build's equivalent: real bytecode must be in target/classes
        Files.createDirectories(dir.resolve("target/classes"));
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", dir.resolve("target/classes").toString(),
                dir.resolve("src/main/java/demo/Main.java").toString()));

        int exit = new PackageCommand(new ArtifactStore(storeDir), dir)
                .execute(List.of("--uber", "--main", "demo.Main"));

        assertEquals(0, exit);
        Path uber = dir.resolve("dist/demo-1.0.0.jar");
        assertTrue(Files.exists(uber), "the uber-jar carries the versioned name");
        try (ZipFile zip = new ZipFile(uber.toFile())) {
            assertNotNull(zip.getEntry("demo/Main.class"), "project classes are in");
            assertNotNull(zip.getEntry("com/example/lib/a/Dep.class"),
                    "the closure jar's classes are in");
            String manifest = read(zip, "META-INF/MANIFEST.MF");
            assertTrue(manifest.contains("Main-Class: demo.Main"), "got: " + manifest);
            assertTrue(manifest.contains("mjm-Dependencies"), "the build-info fact");
        }

        Process process = new ProcessBuilder("java", "-jar", uber.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor());
        assertTrue(output.contains("main-ok"), "java -jar runs the uber-jar, got: " + output);
    }

    @Test
    void servicesMergeAndSignaturesAndModuleInfoDrop() throws Exception {
        Path repo = dir.resolve("fix-repo");
        fixtureJar(repo.resolve("com/example/lib-a/1.0.0/lib-a-1.0.0.jar"), Map.of(
                "META-INF/services/com.example.Svc", "com.example.ImplA\n# comment\n",
                "META-INF/lib-a.SF", "signature-junk",
                "com/example/a/A.class", "a"));
        fixtureJar(repo.resolve("com/example/lib-b/1.0.0/lib-b-1.0.0.jar"), Map.of(
                "META-INF/services/com.example.Svc", "com.example.ImplB\n",
                "module-info.class", "module lib.b {}",
                "com/example/b/B.class", "b"));
        fixturePom(repo.resolve("com/example/lib-a/1.0.0/lib-a-1.0.0.pom"),
                "com.example", "lib-a", "1.0.0");
        fixturePom(repo.resolve("com/example/lib-b/1.0.0/lib-b-1.0.0.pom"),
                "com.example", "lib-b", "1.0.0");
        Files.writeString(dir.resolve("mjm.toml"), """
            [project]
            name = "demo"
            version = "1.0.0"

            [repos]
            fix = "local:fix-repo"

            [deps.fix]
            "com.example:lib-a" = "1.0.0"
            "com.example:lib-b" = "1.0.0"
            """);
        Files.createDirectories(dir.resolve("target/classes"));

        // both closure jars into the store; the lock pins their digests
        ArtifactStore store = new ArtifactStore(dir.resolve("store"));
        Path libA = repo.resolve("com/example/lib-a/1.0.0/lib-a-1.0.0.jar");
        Path libB = repo.resolve("com/example/lib-b/1.0.0/lib-b-1.0.0.jar");
        store.ensure(libA.toUri().toString(), ArtifactStore.Kind.JAR);
        store.ensure(libB.toUri().toString(), ArtifactStore.Kind.JAR);
        Files.writeString(dir.resolve("mjm.lock"), """
            [[artifacts]]
            id = "com.example:lib-a"
            version = "1.0.0"
            source = "local:fix-repo"
            url = "%s"
            sha256 = "%s"
            depth = 1

            [[artifacts]]
            id = "com.example:lib-b"
            version = "1.0.0"
            source = "local:fix-repo"
            url = "%s"
            sha256 = "%s"
            depth = 1
            """.formatted(libA.toUri().toString(), ArtifactStore.sha256(libA),
                libB.toUri().toString(), ArtifactStore.sha256(libB)));

        int exit = new PackageCommand(store, dir).execute(List.of("--uber"));

        assertEquals(0, exit);
        try (ZipFile zip = new ZipFile(dir.resolve("dist/demo-1.0.0.jar").toFile())) {
            String services = read(zip, "META-INF/services/com.example.Svc");
            assertTrue(services.contains("com.example.ImplA"), "line-merged from lib-a");
            assertTrue(services.contains("com.example.ImplB"), "line-merged from lib-b");
            assertFalse(services.contains("# comment"), "comments do not survive");
            assertTrue(zip.getEntry("META-INF/lib-a.SF") == null, "signatures drop");
            assertTrue(zip.getEntry("module-info.class") == null, "module-info drops");
            assertNotNull(zip.getEntry("com/example/a/A.class"));
            assertNotNull(zip.getEntry("com/example/b/B.class"));
        }
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private void writeProject() throws IOException {
        Files.writeString(dir.resolve("mjm.toml"), """
            [project]
            name = "demo"
            version = "1.0.0"

            [deps]
            "com.example:lib-a" = "1.0.0"
            """);
        Files.createDirectories(dir.resolve("src/main/java/demo"));
        Files.writeString(dir.resolve("src/main/java/demo/Main.java"), """
            package demo;

            public final class Main {
                public static void main(String[] args) {
                    System.out.println("main-ok");
                }
            }
            """);
    }

    private ResolvedGraph.ResolvedArtifact locked(String id, String version, String source,
                                                  String url) {
        return new ResolvedGraph.ResolvedArtifact(id, version, Species.CENTRAL, source,
                url, null, 1, true);
    }

    private void writeLock(ArtifactStore store, List<ResolvedGraph.ResolvedArtifact> artifacts)
            throws IOException {
        StringBuilder lock = new StringBuilder("# generated by mj\n");
        for (ResolvedGraph.ResolvedArtifact artifact : artifacts) {
            String sha = store.digestOf(artifact.jarUrl());
            if (sha == null) {
                throw new IOException("no digest recorded for " + artifact.jarUrl());
            }
            lock.append("""
                [[artifacts]]
                id = "%s"
                version = "%s"
                source = "%s"
                url = "%s"
                sha256 = "%s"
                depth = %d

                """.formatted(artifact.id(), artifact.version(), artifact.source(),
                    artifact.jarUrl(), sha, artifact.depth()));
        }
        Files.writeString(dir.resolve("mjm.lock"), lock.toString());
    }

    private void fixtureJar(Path target, Map<String, String> entries) throws IOException {
        Files.createDirectories(target.getParent());
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(target))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes());
                out.closeEntry();
            }
        }
    }

    private void fixturePom(Path target, String group, String artifactId, String version)
            throws IOException {
        Files.createDirectories(target.getParent());
        Files.writeString(target, """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>%s</groupId>
              <artifactId>%s</artifactId>
              <version>%s</version>
            </project>
            """.formatted(group, artifactId, version));
    }

    private static String read(ZipFile zip, String name) throws IOException {
        try (InputStream in = zip.getInputStream(zip.getEntry(name))) {
            return new String(in.readAllBytes());
        }
    }
}
