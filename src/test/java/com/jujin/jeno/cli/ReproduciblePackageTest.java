package com.jujin.jeno.cli;

import com.jujin.jeno.store.ArtifactStore;
import com.jujin.jeno.store.StoreDescriptorSource;
import com.jujin.jeno.store.StorePomSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Packaged bytes depend on the inputs only: the same fixture packages to the
 * same jar twice, even across a clean rebuild.
 */
class ReproduciblePackageTest {

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        ProjectLock.useRoot(root.resolve(".locks"));
        com.jujin.jeno.store.Paths.useForTesting(root.resolve(".cache/store"),
                root.resolve(".cache/sources"), root.resolve(".locks"));
        store = new ArtifactStore(root.resolve(".cache/store"));
    }

    @AfterEach
    void tearDown() {
        ProjectLock.useUserRoot();
        com.jujin.jeno.store.Paths.resetForTesting();
        com.jujin.jeno.resolve.ArtifactLocations.useProjectRoot(Path.of("").toAbsolutePath());
    }

    @Test
    void twoPlainPackagesAreByteIdentical() throws Exception {
        Path project = project("plain", null, """
            package demo;

            public final class App {
            }
            """);
        assertEquals(0, new BuildCommand(store, project).execute(List.of()));
        Path jar = project.resolve("target/dist/plain-0.1.0.jar");

        assertEquals(0, new PackageCommand(store, project).execute(List.of()));
        byte[] first = Files.readAllBytes(jar);
        assertEquals(0, new PackageCommand(store, project).execute(List.of()));

        assertArrayEquals(first, Files.readAllBytes(jar),
                "the same classes must package to the same bytes");
    }

    @Test
    void aCleanRebuildPackagesToTheSameBytes() throws Exception {
        Path project = project("plain", null, """
            package demo;

            public final class App {
            }
            """);
        assertEquals(0, new BuildCommand(store, project).execute(List.of()));
        assertEquals(0, new PackageCommand(store, project).execute(List.of()));
        byte[] first = Files.readAllBytes(project.resolve("target/dist/plain-0.1.0.jar"));

        assertEquals(0, new CleanCommand(project).execute(List.of()));
        assertEquals(0, new BuildCommand(store, project).execute(List.of()));
        assertEquals(0, new PackageCommand(store, project).execute(List.of()));

        assertArrayEquals(first,
                Files.readAllBytes(project.resolve("target/dist/plain-0.1.0.jar")),
                "recompiling the same sources must not change the jar bytes");
    }

    @Test
    void twoUberPackagesWithMergedServicesAreByteIdentical() throws Exception {
        Path vendor = root.resolve("vendor");
        dependency(vendor);
        Path project = project("uber", """
            [repos]
            v = "local:../vendor"

            [deps]
            "org.example:dep" = "v:1.0.0"
            """, """
            package demo;

            public final class App {
                public static void main(String[] args) {
                    System.out.println("uber-ok");
                }
            }
            """);
        com.jujin.jeno.resolve.ArtifactLocations.useProjectRoot(project);
        new LockService(store, new StorePomSource(store), new StoreDescriptorSource(store))
                .refresh(project);
        assertEquals(0, new BuildCommand(store, project).execute(List.of()));
        Path jar = project.resolve("target/dist/uber-0.1.0-uber.jar");

        assertEquals(0, new PackageCommand(store, project).execute(List.of("--uber")));
        byte[] first = Files.readAllBytes(jar);
        assertEquals(0, new PackageCommand(store, project).execute(List.of("--uber")));

        assertArrayEquals(first, Files.readAllBytes(jar),
                "merged closure and services must land in the same order every time");
    }

    @Test
    void jsonPackageExportsAJarArtifactWithItsDigest() throws Exception {
        Path project = project("jsonpkg", null, """
            package demo;

            public final class App {
            }
            """);
        assertEquals(0, new BuildCommand(store, project).execute(List.of()));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        int exit;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            exit = new PackageCommand(store, project).execute(List.of("--json"));
        } finally {
            System.setOut(original);
        }

        assertEquals(0, exit);
        var json = com.jujin.freeway.commons.json.JsonUtils.parseObject(
                out.toString(StandardCharsets.UTF_8));
        assertEquals("package", json.getString("command"));
        var result = json.getArray("modules").getObject(0).getObject("result");
        var artifact = result.getArray("artifacts").getObject(0);
        assertEquals("jar", artifact.getString("kind"));
        assertEquals("plain", artifact.getString("variant"));
        assertTrue(artifact.getString("sha256").matches("[0-9a-f]{64}"));
        assertTrue(artifact.getLong("bytes") > 0);
        assertTrue(Files.isRegularFile(Path.of(artifact.getString("path"))));
    }

    @Test
    void theReproducibleJarStillRuns() throws Exception {
        Path project = project("run", null, """
            package demo;

            public final class App {
                public static void main(String[] args) {
                    System.out.println("repro-ok");
                }
            }
            """);
        assertEquals(0, new BuildCommand(store, project).execute(List.of()));
        assertEquals(0, new PackageCommand(store, project).execute(List.of("--uber")));
        Path jar = project.resolve("target/dist/run-0.1.0-uber.jar");

        Path output = root.resolve("run-out.txt");
        Process process = new ProcessBuilder("java", "-jar", jar.toString())
                .redirectOutput(output.toFile())
                .redirectErrorStream(true)
                .start();
        assertEquals(0, process.waitFor());
        assertTrue(Files.readString(output).contains("repro-ok"),
                "the jar still runs: " + Files.readString(output));
    }

    // ------------------------------------------------------------------

    private Path project(String name, String extra, String source) throws IOException {
        Path project = root.resolve(name);
        Path packageDir = project.resolve("src/main/java/demo");
        Files.createDirectories(packageDir);
        String body = extra == null ? "" : "\n" + extra + "\n";
        Files.writeString(project.resolve("jeno.toml"), """
            [project]
            name = "%s"
            version = "0.1.0"
            main = "demo.App"
            %s
            """.formatted(name, body));
        Files.writeString(packageDir.resolve("App.java"), source);
        Files.writeString(project.resolve("jeno.lock"),
                "# generated by jeno — commit to version control, do not edit by hand\n");
        return project;
    }

    /** A Maven-layout dependency whose jar carries a service file. */
    private static void dependency(Path vendor) throws IOException {
        Path dir = vendor.resolve("org/example/dep/1.0.0");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("dep-1.0.0.pom"), """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>org.example</groupId>
              <artifactId>dep</artifactId>
              <version>1.0.0</version>
            </project>
            """);
        try (ZipOutputStream zip = new ZipOutputStream(
                Files.newOutputStream(dir.resolve("dep-1.0.0.jar")))) {
            zip.putNextEntry(new ZipEntry("META-INF/services/org.example.Spi"));
            zip.write("org.example.Impl\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("org/example/dep-marker.txt"));
            zip.write("dep".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
