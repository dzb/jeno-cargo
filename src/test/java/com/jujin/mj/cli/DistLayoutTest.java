package com.jujin.mj.cli;

import com.jujin.mj.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dist layout contract: {@code lib/} holds ONE jar — mj merged with its
 * skeleton (freeway, slf4j) — and the lifecycle toolchain (junit) ships apart
 * under {@code lib/toolchain/}, loaded only by the forked test JVM.
 *
 * <p>freeway is mj's skeleton, not a swappable dependency: a user never picks
 * a different freeway-ioc. Shipping nine jars for that is the opposite of
 * light.
 */
class DistLayoutTest {

    @TempDir
    Path dir;

    @Test
    void distShipsOneJarPlusTheToolchainDirectory(@TempDir Path storeDir) throws Exception {
        Files.createDirectories(dir.resolve("src/main/java/demo"));
        Files.writeString(dir.resolve("mj.toml"), """
            [project]
            name = "demo"
            version = "1.0.0"
            main = "demo.App"
            """);
        Files.writeString(dir.resolve("src/main/java/demo/App.java"), """
            package demo;

            public final class App {
                public static void main(String[] args) {
                    System.out.println("demo");
                }
            }
            """);
        // two jars: a skeleton one and a junit one, both MAIN (as mj's own
        // mj.toml declares them)
        Path skeletonJar = jar(dir.resolve("jars/skeleton-1.0.0.jar"),
                "com/acme/Skeleton.class", "skeleton");
        Path junitJar = jar(dir.resolve("jars/junit-jupiter-api-6.1.3.jar"),
                "org/junit/jupiter/api/Test.class", "junit");
        Path placedSkeleton = place(storeDir, skeletonJar);
        Path placedJunit = place(storeDir, junitJar);

        Files.writeString(dir.resolve("mj.lock"), """
            [[artifacts]]
            id = "com.acme:skeleton"
            version = "1.0.0"
            source = "local"
            url = "%s"
            sha256 = "%s"
            depth = 1

            [[artifacts]]
            id = "org.junit.jupiter:junit-jupiter-api"
            version = "6.1.3"
            source = "local"
            url = "%s"
            sha256 = "%s"
            depth = 1
            """.formatted(placedSkeleton.toUri(), ArtifactStore.sha256(skeletonJar),
                placedJunit.toUri(), ArtifactStore.sha256(junitJar)));

        ArtifactStore store = new ArtifactStore(storeDir);
        assertEquals(0, new BuildCommand(store, dir).execute(List.of()));
        assertEquals(0, new DistCommand(store, dir).execute(List.of()));

        Path lib = dir.resolve("dist/demo-1.0.0/lib");
        List<Path> topLevel;
        try (var stream = Files.list(lib)) {
            topLevel = stream.toList();
        }
        assertEquals(2, topLevel.size(), "exactly two entries: the jar and toolchain/");
        Path uber = lib.resolve("demo-1.0.0.jar");
        assertTrue(Files.exists(uber), "the project jar is the only jar at lib/ top level");
        assertTrue(Files.isDirectory(lib.resolve("toolchain")), "toolchain is its own directory");

        try (var zip = new java.util.zip.ZipFile(uber.toFile())) {
            assertTrue(zip.getEntry("com/acme/Skeleton.class") != null,
                    "the skeleton's classes are merged into the one jar");
            assertTrue(zip.getEntry("org/junit/jupiter/api/Test.class") == null,
                    "junit never merges into the running jar");
        }
        assertTrue(Files.exists(lib.resolve("toolchain/junit-jupiter-api-6.1.3.jar")),
                "junit ships under lib/toolchain/ with its readable name");
        assertFalse(Files.exists(lib.resolve("toolchain/skeleton-1.0.0.jar")),
                "non-junit jars never land in the toolchain directory");
    }

    @Test
    void theLauncherPutsOnlyTheLibraryOnTheClasspath(@TempDir Path storeDir) throws Exception {
        Files.createDirectories(dir.resolve("src/main/java/demo"));
        Files.writeString(dir.resolve("mj.toml"), """
            [project]
            name = "demo"
            version = "1.0.0"
            """);
        Files.writeString(dir.resolve("src/main/java/demo/App.java"),
                "package demo; public final class App {}");
        Files.writeString(dir.resolve("mj.lock"), "");

        ArtifactStore store = new ArtifactStore(storeDir);
        assertEquals(0, new BuildCommand(store, dir).execute(List.of()));
        assertEquals(0, new DistCommand(store, dir).execute(List.of()));

        String launcher = Files.readString(dir.resolve("dist/demo-1.0.0/bin/demo"));
        assertTrue(launcher.contains("-cp \"$MJ_HOME/lib/*\""),
                "one classpath wildcard, no toolchain: " + launcher);
        String execLine = launcher.lines()
                .filter(line -> line.strip().startsWith("exec "))
                .findFirst().orElseThrow();
        assertFalse(execLine.contains("toolchain"),
                "the running program never needs the test toolchain: " + execLine);
    }

    @Test
    void deliverablesLiveInDistNotTarget(@TempDir Path storeDir) throws Exception {
        Files.createDirectories(dir.resolve("src/main/java/demo"));
        Files.writeString(dir.resolve("mj.toml"), """
            [project]
            name = "demo"
            version = "1.0.0"
            """);
        Files.writeString(dir.resolve("src/main/java/demo/App.java"),
                "package demo; public final class App {}");
        Files.writeString(dir.resolve("mj.lock"), "");

        ArtifactStore store = new ArtifactStore(storeDir);
        assertEquals(0, new BuildCommand(store, dir).execute(List.of()));
        assertEquals(0, new DistCommand(store, dir).execute(List.of()));
        assertEquals(0, new PackageCommand(store, dir).execute(List.of("--uber")));

        // the agreement: target/ is disposable intermediates only, so
        // `rm -rf target` stays the safe, familiar gesture
        assertTrue(Files.isDirectory(dir.resolve("target/classes")),
                "compiled classes stay in target/");
        try (var stream = Files.list(dir.resolve("target"))) {
            for (Path entry : stream.toList()) {
                String name = entry.getFileName().toString();
                assertTrue(name.equals("classes") || name.equals("test-classes"),
                        "target/ holds intermediates only, found: " + name);
            }
        }

        // every deliverable is under dist/
        assertTrue(Files.exists(dir.resolve("dist/demo-1.0.0.tar.gz")),
                "the tool distribution archive is a deliverable");
        assertTrue(Files.isDirectory(dir.resolve("dist/demo-1.0.0")),
                "the tool distribution directory is a deliverable");
        assertTrue(Files.exists(dir.resolve("dist/demo-1.0.0.jar")),
                "the packaged jar is a deliverable");
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static Path jar(Path target, String entry, String content) throws Exception {
        Files.createDirectories(target.getParent());
        try (var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(target))) {
            out.putNextEntry(new java.util.zip.ZipEntry(entry));
            out.write(content.getBytes());
            out.closeEntry();
        }
        return target;
    }

    private static Path place(Path storeDir, Path jar) throws Exception {
        Files.createDirectories(storeDir);
        Path placed = storeDir.resolve(ArtifactStore.sha256(jar) + ".jar");
        Files.copy(jar, placed);
        return placed;
    }
}
