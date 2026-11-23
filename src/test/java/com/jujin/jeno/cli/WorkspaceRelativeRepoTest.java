package com.jujin.jeno.cli;

import com.jujin.jeno.store.ArtifactStore;
import com.jujin.jeno.store.LockFileReader;
import com.jujin.jeno.store.LockedArtifact;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Relative {@code local:} repositories inside a workspace: each module resolves
 * against its own directory, exactly as it does when that module is the working
 * directory — the case absolute-path fixtures never covered.
 */
class WorkspaceRelativeRepoTest {

    @TempDir
    Path root;

    private Path a;
    private Path b;
    private ArtifactStore store;

    @BeforeEach
    void setUp() throws Exception {
        ProjectLock.useRoot(root.resolve(".locks"));
        com.jujin.jeno.store.Paths.useForTesting(root.resolve(".cache/store"),
                root.resolve(".cache/sources"), root.resolve(".locks"));
        com.jujin.jeno.resolve.ArtifactLocations.useProjectRoot(root);

        b = root.resolve("libs/b");
        a = root.resolve("apps/a");
        module(b, "b", "com.example", """
            [repos]
            v = "local:../vendor"

            [deps]
            "org.example:lib" = "v:1.0.0"
            """, """
            package b;

            public final class B {
                public static String message() {
                    return "from-b";
                }
            }
            """);
        module(a, "a", "com.example", """
            [repos]
            v = "local:../vendor-a"

            [deps]
            "com.example:b" = "v:0.1.0"
            """, """
            package a;

            public final class A {
                public static String message() {
                    return b.B.message();
                }
            }
            """);
        // b's vendor lives beside it; a's vendor serves the published-looking b
        vendor(root.resolve("libs/vendor"), "org.example", "lib", "1.0.0", "lib-bytes");
        vendor(root.resolve("apps/vendor-a"), "com.example", "b", "0.1.0", "b-jar-bytes");
        Files.writeString(root.resolve("jeno.ws.toml"), """
            [ws]
            modules = ["apps/a", "libs/b"]
            """);
        store = new ArtifactStore(root.resolve(".cache/store"));
    }

    @AfterEach
    void tearDown() {
        ProjectLock.useUserRoot();
        com.jujin.jeno.store.Paths.resetForTesting();
        com.jujin.jeno.resolve.ArtifactLocations.useProjectRoot(Path.of("").toAbsolutePath());
    }

    @Test
    void aRootLockResolvesEachModulesRelativeRepositoryAgainstItsOwnDirectory() throws Exception {
        Result result = run(root, lock());

        assertEquals(0, result.exit(), result.out() + result.err());
        List<LockedArtifact> locked = LockFileReader.read(
                Files.readString(b.resolve("jeno.lock")));
        LockedArtifact lib = locked.stream()
                .filter(artifact -> artifact.id().equals("org.example:lib"))
                .findFirst().orElseThrow();
        assertEquals("local:../vendor", lib.source(),
                "the declaration is kept as the module wrote it");
        assertTrue(lib.url().contains("/libs/vendor/org/example/lib/1.0.0/lib-1.0.0.jar"),
                "the URL points at libs/b/../vendor, not at the workspace root: " + lib.url());
        assertTrue(Files.exists(b.resolve("jeno.lock")), "b's own lock was written");
        assertTrue(Files.exists(a.resolve("jeno.lock")), "and a's was too");
    }

    @Test
    void aRootBuildCompilesAgainstTheLocalModuleClasses() throws Exception {
        assertEquals(0, run(root, lock()).exit());

        Result build = run(root, new BuildCommand(store, root));

        assertEquals(0, build.exit(), build.out() + build.err());
        assertTrue(Files.isRegularFile(b.resolve("target/classes/b/B.class")));
        assertTrue(Files.isRegularFile(a.resolve("target/classes/a/A.class")),
                "a compiled against b's classes: " + build.out());
    }

    @Test
    void aSingleProjectWithARelativeRepositoryIsUnchanged() throws Exception {
        Path solo = root.resolve("solo");
        module(solo, "solo", null, """
            [repos]
            v = "local:../vendor-solo"

            [deps]
            "org.example:lib" = "v:1.0.0"
            """, """
            package solo;

            public final class Solo {
            }
            """);
        vendor(root.resolve("vendor-solo"), "org.example", "lib", "1.0.0", "solo-bytes");

        // a single project: the CLI points the process at it (no orchestration)
        com.jujin.jeno.resolve.ArtifactLocations.useProjectRoot(solo);
        Result result = run(solo, lock(solo));

        assertEquals(0, result.exit(), result.out() + result.err());
        List<LockedArtifact> locked = LockFileReader.read(
                Files.readString(solo.resolve("jeno.lock")));
        assertTrue(locked.getFirst().url().contains("/vendor-solo/org/example/lib"),
                locked.getFirst().url());
    }

    @Test
    void twoModulesWithTheSameRelativeTailKeepTheirOwnBytes() throws Exception {
        // same relative lock URL shape in both modules, different bytes behind it
        Path one = root.resolve("one");
        Path two = root.resolve("two");
        module(one, "one", null, """
            [repos]
            v = "local:vendor"

            [deps]
            "org.example:lib" = "v:1.0.0"
            """, """
            package one;

            public final class One {
            }
            """);
        module(two, "two", null, """
            [repos]
            v = "local:vendor"

            [deps]
            "org.example:lib" = "v:1.0.0"
            """, """
            package two;

            public final class Two {
            }
            """);
        vendor(one.resolve("vendor"), "org.example", "lib", "1.0.0", "one-bytes");
        vendor(two.resolve("vendor"), "org.example", "lib", "1.0.0", "two-bytes");
        Files.writeString(root.resolve("jeno.ws.toml"), """
            [ws]
            modules = ["one", "two"]
            """);

        Result result = run(root, lock());

        assertEquals(0, result.exit(), result.out() + result.err());
        String oneSha = firstSha(one);
        String twoSha = firstSha(two);
        assertEquals(sha(one.resolve("vendor/org/example/lib/1.0.0/lib-1.0.0.jar")), oneSha);
        assertEquals(sha(two.resolve("vendor/org/example/lib/1.0.0/lib-1.0.0.jar")), twoSha);
        assertTrue(!oneSha.equals(twoSha),
                "identical relative URLs must not share one cached digest");
    }

    // ------------------------------------------------------------------

    private LockCommand lock() {
        return lock(root);
    }

    private LockCommand lock(Path dir) {
        return new LockCommand(
                new LockService(store, new StorePomSource(store), new StoreDescriptorSource(store)),
                dir);
    }

    private static String firstSha(Path module) throws IOException {
        return LockFileReader.read(Files.readString(module.resolve("jeno.lock")))
                .getFirst().sha256();
    }

    private static String sha(Path file) throws IOException {
        return ArtifactStore.sha256(file);
    }

    private static void module(Path dir, String name, String group, String body,
                               String source) throws IOException {
        Files.createDirectories(dir.resolve("src/main/java/" + name));
        String header = "[project]\nname = \"" + name + "\"\n"
                + (group == null ? "" : "group = \"" + group + "\"\n")
                + "version = \"" + (name.equals("b") ? "0.1.0" : "0.1.0") + "\"\n";
        Files.writeString(dir.resolve("jeno.toml"), header + body);
        Files.writeString(dir.resolve("src/main/java/" + name + "/"
                + name.substring(0, 1).toUpperCase() + name.substring(1) + ".java"), source);
    }

    private static void vendor(Path base, String group, String artifact, String version,
                               String jarBytes) throws IOException {
        Path dir = base.resolve(group.replace('.', '/')).resolve(artifact).resolve(version);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(artifact + "-" + version + ".pom"), """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>%s</groupId>
              <artifactId>%s</artifactId>
              <version>%s</version>
            </project>
            """.formatted(group, artifact, version));
        writeJar(dir.resolve(artifact + "-" + version + ".jar"), jarBytes);
    }

    /** A valid (readable by javac) jar whose bytes differ per marker. */
    private static void writeJar(Path path, String marker) throws IOException {
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("marker.txt"));
            zip.write(marker.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private static Result run(Path dir, Command command) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            exit = command.execute(List.of());
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new Result(exit, out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
    }

    private record Result(int exit, String out, String err) {}
}
