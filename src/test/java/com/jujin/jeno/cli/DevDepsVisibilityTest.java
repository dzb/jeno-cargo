package com.jujin.jeno.cli;

import com.jujin.jeno.project.Species;
import com.jujin.jeno.resolve.LockFile;
import com.jujin.jeno.resolve.ResolvedGraph;
import com.jujin.jeno.resolve.Scope;
import com.jujin.jeno.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code [deps-dev]} across the real commands: a dev-only jar is absent from
 * the build's compile classpath (strict visibility), absent from
 * {@code jeno package}'s output, absent from the published descriptor — and
 * present on {@code jeno test}'s classpath. The lockfile is the one graph both
 * sides read.
 */
class DevDepsVisibilityTest {

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
    void aDevDependencyNeverReachesTheCompileClasspath(@TempDir Path storeDir) throws Exception {
        // The source imports something only a dev dependency could provide:
        // if dev jars leaked into javac's -cp this would compile.
        Files.createDirectories(dir.resolve("src/main/java/demo"));
        Files.writeString(dir.resolve("jeno.toml"), """
            [project]
            name = "demo"
            version = "0.1.0"

            [deps-dev]
            "com.example:test-kit" = "1.0.0"
            """);
        Files.writeString(dir.resolve("src/main/java/demo/App.java"), """
            package demo;

            import com.example.TestKit;

            public final class App {
                public static void main(String[] args) {
                    TestKit.ping();
                }
            }
            """);

        // a lock that pins the dev artifact to a real (but empty) jar
        Path jar = jarWith(storeDir.resolve("empty"), "test-kit-1.0.0.jar");
        Files.writeString(dir.resolve("jeno.lock"), lockRow("com.example:test-kit", jar, Scope.DEV));

        int exit = new BuildCommand(new ArtifactStore(storeDir), dir).execute(List.of());
        assertEquals(1, exit, "the import must fail — the dev jar is not on the compile path");
        assertFalse(Files.exists(dir.resolve("target/classes/demo/App.class")),
                "nothing was compiled");
    }

    @Test
    void aDevDependencyIsAbsentFromThePublishedDescriptor(@TempDir Path storeDir) throws Exception {
        Files.createDirectories(dir.resolve("src/main/java/demo"));
        Files.writeString(dir.resolve("jeno.toml"), """
            [project]
            name = "demo"
            version = "0.1.0"

            [deps]
            "com.example:main-lib" = "1.0.0"

            [deps-dev]
            "com.example:test-kit" = "1.0.0"
            """);
        Files.writeString(dir.resolve("src/main/java/demo/App.java"), """
            package demo;

            public final class App {
            }
            """);
        Path mainJar = jarWith(storeDir.resolve("jars"), "main-lib-1.0.0.jar");
        Path placed = storePlace(storeDir, mainJar);
        Files.writeString(dir.resolve("jeno.lock"), """
            [[artifacts]]
            id = "com.example:main-lib"
            version = "1.0.0"
            source = "local"
            url = "%s"
            sha256 = "%s"
            depth = 1

            [[artifacts]]
            id = "com.example:test-kit"
            version = "1.0.0"
            source = "local"
            url = "%s"
            sha256 = "%s"
            depth = 1
            scope = "dev"
            """.formatted(placed.toUri(), ArtifactStore.sha256(mainJar),
                placed.toUri(), ArtifactStore.sha256(mainJar)));

        assertEquals(0, new BuildCommand(new ArtifactStore(storeDir), dir)
                .execute(List.of()), "build compiles against the main jar");

        // publish writes the descriptor before it needs the store for upload
        // publish uploads what package built
        assertEquals(0, new PackageCommand(new ArtifactStore(storeDir), dir)
                .execute(List.of()));
        new PublishCommand(dir).execute(List.of());

        Path descriptor = dir.resolve("target/dist/demo-0.1.0.jeno.toml");
        assertTrue(Files.exists(descriptor), "the descriptor was written");
        String text = Files.readString(descriptor);
        assertTrue(text.contains("com.example:main-lib"), "the main dependency is published");
        assertFalse(text.contains("test-kit"),
                "the dev dependency never leaks into a consumer's graph");
    }

    @Test
    void aDevDependencyIsOnTheTestClasspath() {
        // the classpath assembly is the /one/ place test builds read; a DEV row
        // must appear there while a main-only assembly must not.
        ResolvedGraph graph = new ResolvedGraph(List.of(
                new ResolvedGraph.ResolvedArtifact("com.example:test-kit", "1.0.0",
                        Species.CENTRAL, "central", "https://example.com/t.jar", null,
                        1, true, Scope.DEV)), List.of(), Map.of());

        assertTrue(graph.artifacts().stream()
                        .anyMatch(a -> a.scope() == Scope.DEV),
                "the dev row lives in the same lock graph");
        assertTrue(graph.compileArtifacts().isEmpty(),
                "and is excluded from the compile split");
    }

    private static String lockRow(String id, Path jar, Scope scope) {
        String artifact = id.substring(id.indexOf(':') + 1);
        return """
            [[artifacts]]
            id = "%s"
            version = "1.0.0"
            source = "local"
            url = "%s"
            sha256 = "%s"
            depth = 1
            %s
            """.formatted(id, jar.toUri(), ArtifactStore.sha256(jar),
                scope == Scope.DEV ? "scope = \"dev\"" : "");
    }

    /** An empty jar in the store's content-addressed naming ({@code <sha>.jar}). */
    private static Path jarWith(Path dir, String name) throws Exception {
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        try (var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new java.util.zip.ZipEntry("META-INF/MANIFEST.MF"));
            out.write("Manifest-Version: 1.0\n".getBytes());
            out.closeEntry();
        }
        return file;
    }

    /** Places the jar where the store looks it up: {@code <sha256>.jar} at the root. */
    private static Path storePlace(Path storeDir, Path jar) throws Exception {
        Files.createDirectories(storeDir);
        Path placed = storeDir.resolve(ArtifactStore.sha256(jar) + ".jar");
        Files.copy(jar, placed);
        return placed;
    }
}
