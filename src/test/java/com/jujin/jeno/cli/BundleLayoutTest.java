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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dist layout contract: {@code lib/} holds ONE jar — jeno merged with its
 * main runtime closure — and artifacts explicitly named in
 * {@code [toolchain].jars} ship apart under {@code lib/toolchain/}, loaded
 * only by the forked test JVM.
 *
 * <p>freeway is jeno's skeleton, not a swappable dependency: a user never picks
 * a different freeway-ioc. Shipping nine jars for that is the opposite of
 * light.
 */
class DistLayoutTest {

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
    void distShipsOneJarPlusTheDeclaredToolchain(@TempDir Path storeDir) throws Exception {
        Files.createDirectories(dir.resolve("src/main/java/demo"));
        Files.writeString(dir.resolve("jeno.toml"), """
            [project]
            name = "demo"
            version = "1.0.0"
            main = "demo.App"

            [toolchain]
            jars = ["com.acme:test-kit", "com.acme:proc-kit"]
            """);
        Files.writeString(dir.resolve("src/main/java/demo/App.java"), """
            package demo;

            public final class App {
                public static void main(String[] args) {
                    System.out.println("demo");
                }
            }
            """);
        // Both are MAIN rows. The split is declared by [toolchain].jars, not
        // inferred from the artifact id or package name.
        Path skeletonJar = jar(dir.resolve("jars/skeleton-1.0.0.jar"),
                "com/acme/Skeleton.class", "skeleton");
        Path toolchainJar = jar(dir.resolve("jars/test-kit-1.0.0.jar"),
                "com/acme/TestKit.class", "test-kit");
        Path processorJar = jar(dir.resolve("jars/proc-kit-1.0.0.jar"),
                "com/acme/ProcKit.class", "proc-kit");
        Path placedSkeleton = place(storeDir, skeletonJar);
        Path placedToolchain = place(storeDir, toolchainJar);
        Path placedProcessor = place(storeDir, processorJar);

        Files.writeString(dir.resolve("jeno.lock"), """
            [[artifacts]]
            id = "com.acme:skeleton"
            version = "1.0.0"
            source = "local"
            url = "%s"
            sha256 = "%s"
            depth = 1

            [[artifacts]]
            id = "com.acme:test-kit"
            version = "1.0.0"
            source = "local"
            url = "%s"
            sha256 = "%s"
            depth = 1

            [[artifacts]]
            id = "com.acme:proc-kit"
            version = "1.0.0"
            source = "local"
            url = "%s"
            sha256 = "%s"
            depth = 1
            scope = "processor"
            """.formatted(placedSkeleton.toUri(), ArtifactStore.sha256(skeletonJar),
                placedToolchain.toUri(), ArtifactStore.sha256(toolchainJar),
                placedProcessor.toUri(), ArtifactStore.sha256(processorJar)));

        ArtifactStore store = new ArtifactStore(storeDir);
        assertEquals(0, new BuildCommand(store, dir).execute(List.of()));
        assertEquals(0, new BundleCommand(store, dir).execute(List.of()));
        assertFalse(Files.exists(dir.resolve("target/dist/demo-1.0.0/lib/toolchain")),
                "the toolchain is opt-in");

        assertEquals(0, new BundleCommand(store, dir).execute(List.of("--toolchain")));

        Path lib = dir.resolve("target/dist/demo-1.0.0/lib");
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
            assertTrue(zip.getEntry("com/acme/TestKit.class") == null,
                    "toolchain classes never merge into the running jar");
        }
        Path cmd = lib.getParent().resolve("bin/demo.cmd");
        assertTrue(Files.exists(cmd), "the Windows launcher ships beside the POSIX one");
        String cmdText = Files.readString(cmd);
        assertTrue(cmdText.contains("set \"JENO_HOME=%~dp0..\""), cmdText);
        assertTrue(cmdText.contains("lib\\demo-1.0.0.jar"), cmdText);
        assertTrue(cmdText.contains(" demo.App "), cmdText);
        String sums = Files.readString(dir.resolve("target/dist/demo-1.0.0/SHA256SUMS"));
        assertTrue(sums.contains("  bin/demo\n"), sums);
        assertTrue(sums.contains("  bin/demo.cmd\n"), sums);

        String labels = labels(uber);
        assertTrue(labels.contains("com.acme:skeleton@1.0.0"), labels);
        assertFalse(labels.contains("com.acme:test-kit"), labels);
        assertFalse(labels.contains("com.acme:proc-kit"), labels);
        assertTrue(Files.exists(lib.resolve("toolchain/test-kit-1.0.0.jar")),
                "the declared toolchain ships under lib/toolchain/ with a readable name");
        assertTrue(Files.exists(lib.resolve("toolchain/proc-kit-1.0.0.jar")),
                "a PROCESSOR artifact named in [toolchain] ships there too");
        assertFalse(Files.exists(lib.resolve("toolchain/skeleton-1.0.0.jar")),
                "non-toolchain jars never land in the toolchain directory");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        int jsonExit;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            jsonExit = new BundleCommand(store, dir)
                    .execute(List.of("--json", "--toolchain"));
        } finally {
            System.setOut(original);
        }
        assertEquals(0, jsonExit);
        var json = com.jujin.freeway.commons.json.JsonUtils.parseObject(
                out.toString(StandardCharsets.UTF_8));
        assertEquals("bundle", json.getString("command"));
        var result = json.getArray("modules").getObject(0).getObject("result");
        assertEquals(true, result.getBoolean("toolchain"));
        var artifacts = result.getArray("artifacts");
        assertEquals("bundle-directory", artifacts.getObject(0).getString("kind"));
        assertEquals("tar.gz", artifacts.getObject(1).getString("kind"));
        assertTrue(artifacts.getObject(1).getString("sha256").matches("[0-9a-f]{64}"));

        // package --uber keeps its old label set: every MAIN row, including
        // [toolchain] (package has no toolchain concept).
        assertEquals(0, new PackageCommand(store, dir).execute(List.of("--uber")));
        String packageLabels = labels(
                dir.resolve("target/dist/demo-1.0.0-uber.jar"));
        assertTrue(packageLabels.contains("com.acme:skeleton@1.0.0"), packageLabels);
        assertTrue(packageLabels.contains("com.acme:test-kit@1.0.0"), packageLabels);
        assertFalse(packageLabels.contains("com.acme:proc-kit"), packageLabels);
    }

    @Test
    void theLauncherPutsOnlyTheLibraryOnTheClasspath(@TempDir Path storeDir) throws Exception {
        Files.createDirectories(dir.resolve("src/main/java/demo"));
        Files.writeString(dir.resolve("jeno.toml"), """
            [project]
            name = "demo"
            version = "1.0.0"
            main = "demo.App"
            """);
        Files.writeString(dir.resolve("src/main/java/demo/App.java"),
                "package demo; public final class App {}");
        Files.writeString(dir.resolve("jeno.lock"), "");

        ArtifactStore store = new ArtifactStore(storeDir);
        assertEquals(0, new BuildCommand(store, dir).execute(List.of()));
        assertEquals(0, new BundleCommand(store, dir).execute(List.of()));

        String launcher = Files.readString(dir.resolve("target/dist/demo-1.0.0/bin/demo"));
        assertTrue(launcher.startsWith("#!/bin/sh\n# demo 1.0.0 launcher\n"),
                "the launcher names the program, not jeno: " + launcher);
        assertFalse(launcher.contains(Jeno.TAGLINE), "no jeno branding");
        assertTrue(launcher.contains("-cp \"$JENO_HOME/lib/*\""),
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
        Files.writeString(dir.resolve("jeno.toml"), """
            [project]
            name = "demo"
            version = "1.0.0"
            main = "demo.App"
            """);
        Files.writeString(dir.resolve("src/main/java/demo/App.java"),
                "package demo; public final class App {}");
        Files.writeString(dir.resolve("jeno.lock"), "");

        ArtifactStore store = new ArtifactStore(storeDir);
        assertEquals(0, new BuildCommand(store, dir).execute(List.of()));
        assertEquals(0, new BundleCommand(store, dir).execute(List.of()));
        assertEquals(0, new PackageCommand(store, dir).execute(List.of("--uber")));

        // everything the build produces lives under target/, deliverables
        // included — `rm -rf target` is still the one safe gesture
        assertTrue(Files.isDirectory(dir.resolve("target/classes")),
                "compiled classes stay in target/");
        try (var stream = Files.list(dir.resolve("target"))) {
            for (Path entry : stream.toList()) {
                String name = entry.getFileName().toString();
                assertTrue(name.equals("classes") || name.equals("test-classes")
                                || name.equals("dist"),
                        "target/ holds build output only, found: " + name);
            }
        }

        // every deliverable is under target/dist/
        assertTrue(Files.exists(dir.resolve("target/dist/demo-1.0.0.tar.gz")),
                "the tool distribution archive is a deliverable");
        assertTrue(Files.isDirectory(dir.resolve("target/dist/demo-1.0.0")),
                "the tool distribution directory is a deliverable");
        assertTrue(Files.exists(dir.resolve("target/dist/demo-1.0.0-uber.jar")),
                "the packaged jar is a deliverable");
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static String labels(Path jar) throws Exception {
        try (var file = new java.util.jar.JarFile(jar.toFile())) {
            return String.valueOf(file.getManifest().getMainAttributes()
                    .getValue("jeno-Dependencies"));
        }
    }

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

    @Test
    void aToolchainIdMissingFromTheLockIsNamed(@TempDir Path storeDir) throws Exception {
        Files.createDirectories(dir.resolve("src/main/java/demo"));
        Files.writeString(dir.resolve("jeno.toml"), """
            [project]
            name = "demo"
            version = "1.0.0"
            main = "demo.App"

            [toolchain]
            jars = ["com.acme:missing-kit"]
            """);
        Files.writeString(dir.resolve("src/main/java/demo/App.java"),
                "package demo; public final class App {}");
        Files.writeString(dir.resolve("jeno.lock"), "");

        ArtifactStore store = new ArtifactStore(storeDir);
        assertEquals(0, new BuildCommand(store, dir).execute(List.of()));

        PrintStream originalErr = System.err;
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int exit;
        try {
            System.setErr(new PrintStream(errors, true, StandardCharsets.UTF_8));
            exit = new BundleCommand(store, dir).execute(List.of("--toolchain"));
        } finally {
            System.setErr(originalErr);
        }

        assertEquals(1, exit);
        String message = errors.toString(StandardCharsets.UTF_8);
        assertTrue(message.contains("[toolchain].jars names 'com.acme:missing-kit'"), message);
        assertTrue(message.contains("run jeno lock"), message);
    }

    @Test
    void bundleNeedsAMainClass(@TempDir Path storeDir) throws Exception {
        Files.createDirectories(dir.resolve("src/main/java/demo"));
        Files.writeString(dir.resolve("jeno.toml"), """
            [project]
            name = "demo"
            version = "1.0.0"
            """);
        Files.writeString(dir.resolve("src/main/java/demo/App.java"),
                "package demo; public final class App {}");
        Files.writeString(dir.resolve("jeno.lock"), "");

        ArtifactStore store = new ArtifactStore(storeDir);
        assertEquals(0, new BuildCommand(store, dir).execute(List.of()));

        int exit = new BundleCommand(store, dir).execute(List.of());

        assertEquals(1, exit, "a distribution without an entry point is not runnable");
        assertFalse(Files.exists(dir.resolve("target/dist/demo-1.0.0")), "nothing assembled");
    }

    @Test
    void setupScriptUsesTheDeclaredJavaVersionAndVerifiesChecksums(@TempDir Path storeDir)
            throws Exception {
        Files.createDirectories(dir.resolve("src/main/java/demo"));
        Files.writeString(dir.resolve("jeno.toml"), """
            [project]
            name = "demo"
            version = "2.0.0"
            main = "demo.App"

            [java]
            version = 17
            """);
        Files.writeString(dir.resolve("src/main/java/demo/App.java"),
                "package demo; public final class App {}");
        Files.writeString(dir.resolve("jeno.lock"), "");

        ArtifactStore store = new ArtifactStore(storeDir);
        assertEquals(0, new BuildCommand(store, dir).execute(List.of()));
        assertEquals(0, new BundleCommand(store, dir).execute(List.of()));

        String setup = Files.readString(dir.resolve("target/dist/demo-2.0.0/install.sh"));
        assertTrue(setup.contains("requires JDK 17+"), setup);
        assertTrue(setup.contains("\"$JAVA_MAJOR\" -lt 17"), setup);
        assertTrue(setup.contains("sha256sum -c"), setup);
        assertTrue(setup.contains("shasum -a 256 -c"), "a macOS fallback exists");
        assertTrue(setup.contains("SHA256SUMS verification failed"), setup);
        assertTrue(setup.contains(".staging."), "lib/bin are swapped via staging");
        assertTrue(setup.contains("uninstall.sh"), "the uninstaller is installed, not left in the download");

        Path uninstall = dir.resolve("target/dist/demo-2.0.0/uninstall.sh");
        assertTrue(Files.exists(uninstall), "an uninstaller ships with the dist");
        String script = Files.readString(uninstall);
        assertTrue(script.contains("$HOME/.demo"), script);
        assertTrue(script.contains("shell rc"), "the PATH line is called out");
    }

    @Test
    void withoutJavaVersionSetupUsesTheRunningJdkMajor(@TempDir Path storeDir) throws Exception {
        Files.createDirectories(dir.resolve("src/main/java/demo"));
        Files.writeString(dir.resolve("jeno.toml"), """
            [project]
            name = "demo"
            version = "1.0.0"
            main = "demo.App"
            """);
        Files.writeString(dir.resolve("src/main/java/demo/App.java"),
                "package demo; public final class App {}");
        Files.writeString(dir.resolve("jeno.lock"), "");

        ArtifactStore store = new ArtifactStore(storeDir);
        assertEquals(0, new BuildCommand(store, dir).execute(List.of()));
        assertEquals(0, new BundleCommand(store, dir).execute(List.of()));

        String setup = Files.readString(dir.resolve("target/dist/demo-1.0.0/install.sh"));
        assertTrue(setup.contains("requires JDK " + Runtime.version().feature() + "+"), setup);
    }
}
