package com.jujin.mjm.cli;

import com.jujin.mjm.project.MjProject;
import com.jujin.mjm.project.ProjectReader;
import com.jujin.mjm.resolve.ArtifactLocations;
import com.jujin.mjm.resolve.Scope;
import com.jujin.mjm.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Java-specific gaps closed in one pass: compile targets, classifiers,
 * annotation processors, test filtering, and multi-release jars. Each test
 * pins the behaviour that used to be wrong or missing.
 */
class JavaFeaturesTest {

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

    // ------------------------------------------------------------------
    // [java].version → javac --release
    // ------------------------------------------------------------------

    @Test
    void javaVersionProducesBytecodeForThatTarget(@TempDir Path storeDir) throws Exception {
        Files.createDirectories(dir.resolve("src/main/java/demo"));
        Files.writeString(dir.resolve("mjm.toml"), """
            [project]
            name = "demo"
            version = "1.0.0"

            [java]
            version = 17
            """);
        Files.writeString(dir.resolve("src/main/java/demo/App.java"),
                "package demo; public final class App {}");
        Files.writeString(dir.resolve("mjm.lock"), "");

        assertEquals(0, new BuildCommand(new ArtifactStore(storeDir), dir).execute(List.of()));

        // class file major 61 == Java 17
        byte[] bytes = Files.readAllBytes(dir.resolve("target/classes/demo/App.class"));
        int major = ((bytes[6] & 0xff) << 8) | (bytes[7] & 0xff);
        assertEquals(61, major,
                "[java].version must reach javac as --release, producing Java 17 bytecode");
    }

    @Test
    void aTargetNewerThanTheRunningJdkIsRefused(@TempDir Path storeDir) throws Exception {
        Files.createDirectories(dir.resolve("src/main/java/demo"));
        Files.writeString(dir.resolve("mjm.toml"), """
            [project]
            name = "demo"
            version = "1.0.0"

            [java]
            version = 99
            """);
        Files.writeString(dir.resolve("src/main/java/demo/App.java"),
                "package demo; public final class App {}");
        Files.writeString(dir.resolve("mjm.lock"), "");

        assertEquals(1, new BuildCommand(new ArtifactStore(storeDir), dir).execute(List.of()),
                "a target above the running JDK cannot be honored and must not silently pass");
    }

    // ------------------------------------------------------------------
    // classifiers
    // ------------------------------------------------------------------

    @Test
    void aClassifierIsPartOfTheArtifactUrl() {
        String url = ArtifactLocations.mavenFile(ArtifactLocations.CENTRAL_BASE,
                "io.netty", "netty-transport-native-epoll", "4.1.0", "linux-x86_64", "jar");
        assertTrue(url.endsWith("netty-transport-native-epoll-4.1.0-linux-x86_64.jar"), url);
    }

    // ------------------------------------------------------------------
    // annotation processors
    // ------------------------------------------------------------------

    @Test
    void processorsParseIntoTheirOwnScope() {
        MjProject project = ProjectReader.parse("""
            [project]
            name = "demo"
            version = "1.0.0"

            [build]
            processors = ["org.mapstruct:mapstruct-processor:1.6.0"]
            """);

        assertEquals(1, project.processorRequirements().size());
        assertEquals("org.mapstruct:mapstruct-processor",
                project.processorRequirements().getFirst().id());
        assertEquals("1.6.0", project.processorRequirements().getFirst().version());
    }

    @Test
    void aProcessorCoordinateWithoutAVersionIsRefused() {
        assertThrows(RuntimeException.class, () -> ProjectReader.parse("""
            [project]
            name = "demo"
            version = "1.0.0"

            [build]
            processors = ["org.mapstruct:mapstruct-processor"]
            """), "a processor is a build tool — its version is mandatory");
    }

    @Test
    void processorAndDevAreDifferentScopes() {
        assertFalse(Scope.PROCESSOR == Scope.DEV);
        assertFalse(Scope.PROCESSOR == Scope.MAIN,
                "a processor is never on the compile classpath or the runtime closure");
    }

    // ------------------------------------------------------------------
    // [test] include / exclude
    // ------------------------------------------------------------------

    @Test
    void testExcludesSkipMatchingSources(@TempDir Path storeDir) throws Exception {
        Files.createDirectories(dir.resolve("src/main/java/demo"));
        Files.createDirectories(dir.resolve("src/test/java/demo"));
        Files.writeString(dir.resolve("mjm.toml"), """
            [project]
            name = "demo"
            version = "1.0.0"

            [test]
            exclude = ["*Slow*"]
            """);
        Files.writeString(dir.resolve("src/main/java/demo/App.java"),
                "package demo; public final class App {}");
        Files.writeString(dir.resolve("src/test/java/demo/FastTest.java"), """
            package demo;

            import com.jujin.mjm.test.MjmTest;
            import com.jujin.mjm.test.MjmAssert;

            public class FastTest {
                @MjmTest
                void ok() { MjmAssert.true_(true); }
            }
            """);
        Files.writeString(dir.resolve("src/test/java/demo/SlowTest.java"), """
            package demo;

            import com.jujin.mjm.test.MjmTest;
            import com.jujin.mjm.test.MjmAssert;

            public class SlowTest {
                @MjmTest
                void fails() { MjmAssert.equals(1, 2); }
            }
            """);
        Files.writeString(dir.resolve("mjm.lock"), "");

        ArtifactStore store = new ArtifactStore(storeDir);
        assertEquals(0, new BuildCommand(store, dir).execute(List.of()));

        int exit;
        try (var central = VendoredToolchain.start()) {
            exit = new TestCommand(store, dir).execute(List.of());
        }
        assertEquals(0, exit, "the excluded failing test never ran");
        assertFalse(Files.exists(dir.resolve("target/test-classes/demo/SlowTest.class")),
                "the excluded source was not even compiled");
    }

    // ------------------------------------------------------------------
    // multi-release jars
    // ------------------------------------------------------------------

    @Test
    void aMergedVersionedJarGetsTheMultiReleaseFlag(@TempDir Path storeDir) throws Exception {
        Files.createDirectories(dir.resolve("src/main/java/demo"));
        Files.writeString(dir.resolve("mjm.toml"), """
            [project]
            name = "demo"
            version = "1.0.0"
            """);
        Files.writeString(dir.resolve("src/main/java/demo/App.java"),
                "package demo; public final class App {}");

        // a dependency carrying META-INF/versions/17/ classes
        Path dep = dir.resolve("jars/mr-1.0.0.jar");
        Files.createDirectories(dep.getParent());
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(dep))) {
            out.putNextEntry(new ZipEntry("com/acme/Deep.class"));
            out.write("base".getBytes());
            out.closeEntry();
            out.putNextEntry(new ZipEntry("META-INF/versions/17/com/acme/Deep.class"));
            out.write("v17".getBytes());
            out.closeEntry();
        }
        Path placed = storeDir.resolve(ArtifactStore.sha256(dep) + ".jar");
        Files.createDirectories(storeDir);
        Files.copy(dep, placed);
        Files.writeString(dir.resolve("mjm.lock"), """
            [[artifacts]]
            id = "com.acme:mr"
            version = "1.0.0"
            source = "local"
            url = "%s"
            sha256 = "%s"
            depth = 1
            """.formatted(placed.toUri(), ArtifactStore.sha256(dep)));

        ArtifactStore store = new ArtifactStore(storeDir);
        assertEquals(0, new BuildCommand(store, dir).execute(List.of()));
        assertEquals(0, new PackageCommand(store, dir).execute(List.of("--uber")));

        Path jar = dir.resolve("dist/demo-1.0.0-uber.jar");
        try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
            var manifest = new java.util.jar.Manifest(
                    zip.getInputStream(zip.getEntry("META-INF/MANIFEST.MF")));
            assertEquals("true", manifest.getMainAttributes().getValue("Multi-Release"),
                    "versioned classes are only honored with this flag");
            assertTrue(zip.getEntry("META-INF/versions/17/com/acme/Deep.class") != null,
                    "the versioned classes survive the merge");
        }
    }
}
