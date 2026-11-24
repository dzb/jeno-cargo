package com.jujin.jeno.cli;

import com.jujin.jeno.store.ArtifactStore;
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
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A workspace module stands in for its locked jar: build, classpath, run and
 * bundle all see the local classes, and a checkout jeno cannot trust is named
 * with the next action rather than silently swapped for the store copy.
 */
class WorkspaceSubstitutionTest {

    @TempDir
    Path root;

    private Path core;
    private Path web;
    private ArtifactStore store;

    @BeforeEach
    void setUp() throws Exception {
        com.jujin.jeno.store.Paths.useForTesting(root.resolve(".cache/store"),
                root.resolve(".cache/sources"), root.resolve(".locks"));
        ProjectLock.useRoot(root.resolve(".locks"));
        com.jujin.jeno.resolve.ArtifactLocations.useProjectRoot(root);

        core = root.resolve("libs/core");
        web = root.resolve("apps/web");
        module(core, "core", "1.0.0", """
            package core;

            public final class Core {
                public static String message() {
                    return "from-local-core";
                }
            }
            """, null, "com.example");
        module(web, "web", "0.1.0", """
            package web;

            public final class Main {
                public static void main(String[] args) throws Exception {
                    java.nio.file.Files.writeString(
                            java.nio.file.Path.of("run-proof.txt"), core.Core.message());
                }
            }
            """, "\"com.example:core\" = \"1.0.0\"", null);
        writeLock(core, "");
        writeLock(web, """
            [[artifacts]]
            id = "com.example:core"
            version = "1.0.0"
            source = "central"
            url = "https://repo.maven.apache.org/maven2/core/core/1.0.0/core-1.0.0.jar"
            sha256 = "%s"
            depth = 1
            """.formatted("c".repeat(64)));
        Files.writeString(root.resolve("jeno.ws.toml"), """
            [ws]
            modules = ["libs/core", "apps/web"]
            """);
        store = new ArtifactStore(root.resolve(".cache/store"));
    }

    @AfterEach
    void tearDown() {
        Substitutions.useLocalOverrides(true);
        ProjectLock.useUserRoot();
        com.jujin.jeno.store.Paths.resetForTesting();
        com.jujin.jeno.resolve.ArtifactLocations.useProjectRoot(Path.of("").toAbsolutePath());
    }

    @Test
    void aLocalModuleReplacesItsStoreJarOnTheClasspath() throws Exception {
        assertEquals(0, new BuildCommand(store, core).execute(List.of()));

        List<String> entries = classpath(web);

        // Workspaces records module directories by real path. On macOS /var is
        // a symlink to /private/var, so compare against the same canonical form
        // jeno uses instead of the temp-dir spelling.
        assertTrue(entries.contains(core.toRealPath().resolve("target/classes").toString()),
                entries.toString());
        assertFalse(entries.stream().anyMatch(entry -> entry.contains("c".repeat(64))),
                "the locked jar never appears: " + entries);
        assertEquals(web.resolve("target/classes").toString(), entries.get(0));
    }

    @Test
    void anUnbuiltModuleIsNamedWithTheNextAction() throws Exception {
        String errors = run(web, new BuildCommand(store, web));

        assertTrue(errors.contains("run jeno build in libs/core first"), errors);
    }

    @Test
    void classesBuiltForAnotherVersionAreRefused() throws Exception {
        assertEquals(0, new BuildCommand(store, core).execute(List.of()));
        Files.writeString(core.resolve("target/classes/META-INF/jeno/version.properties"),
                "version=9.9.9\n");

        String errors = run(web, new BuildCommand(store, web));

        assertTrue(errors.contains("was built as 9.9.9"), errors);
        assertTrue(errors.contains("run jeno build in libs/core"), errors);
    }

    @Test
    void aLocalVersionDifferentFromTheLockOnlyNotesTheDifference() throws Exception {
        Files.writeString(core.resolve("jeno.toml"), """
            [project]
            name = "core"
            group = "com.example"
            version = "1.2.1"
            """);
        assertEquals(0, new BuildCommand(store, core).execute(List.of()));

        String errors = run(web, new BuildCommand(store, web));

        assertTrue(errors.contains("com.example:core: local 1.2.1, locked 1.0.0"), errors);
    }

    @Test
    void lockedModeUsesStoreArtifactsInsteadOfTheLocalModule() throws Exception {
        byte[] bytes = "published-core-jar".getBytes(StandardCharsets.UTF_8);
        Path jar = root.resolve(".cache/store").resolve(digest(bytes) + ".jar");
        Files.createDirectories(jar.getParent());
        Files.write(jar, bytes);
        writeLock(web, """
            [[artifacts]]
            id = "com.example:core"
            version = "1.0.0"
            source = "central"
            url = "https://repo.maven.apache.org/maven2/core/core/1.0.0/core-1.0.0.jar"
            sha256 = "%s"
            depth = 1
            """.formatted(digest(bytes)));

        Substitutions.useLocalOverrides(false);
        List<String> entries = classpath(web);

        assertTrue(entries.contains(jar.toString()), entries.toString());
        assertFalse(entries.contains(core.toRealPath().resolve("target/classes").toString()),
                "the workspace checkout must not replace the locked artifact: " + entries);
    }

    @Test
    void withoutAWorkspaceTheClasspathIsStillTheStore() throws Exception {
        Files.delete(root.resolve("jeno.ws.toml"));
        byte[] bytes = "not-a-real-jar".getBytes(StandardCharsets.UTF_8);
        Path jar = root.resolve(".cache/store").resolve(digest(bytes) + ".jar");
        Files.createDirectories(jar.getParent());
        Files.write(jar, bytes);
        writeLock(web, """
            [[artifacts]]
            id = "com.example:core"
            version = "1.0.0"
            source = "central"
            url = "https://repo.maven.apache.org/maven2/core/core/1.0.0/core-1.0.0.jar"
            sha256 = "%s"
            depth = 1
            """.formatted(digest(bytes)));

        List<String> entries = classpath(web);

        assertEquals(List.of(web.resolve("target/classes").toString(), jar.toString()), entries);
    }

    @Test
    void runExecutesTheLocalCodeAndBundleCarriesIt() throws Exception {
        assertEquals(0, new BuildCommand(store, core).execute(List.of()));
        assertEquals(0, new BuildCommand(store, web).execute(List.of()));

        assertEquals(0, new RunCommand(store, web).execute(List.of("web.Main")));
        assertEquals("from-local-core",
                Files.readString(web.resolve("run-proof.txt")).strip(),
                "run started the local class, not a store copy");

        int exit = new BundleCommand(store, web).execute(List.of());
        assertEquals(0, exit, "bundle assembles with a local module in the closure");
        Path shipped = web.resolve("target/dist/web-0.1.0/lib/web-0.1.0.jar");
        assertTrue(Files.isRegularFile(shipped), "the dist jar exists");
        try (JarFile jar = new JarFile(shipped.toFile())) {
            assertTrue(jar.getEntry("core/Core.class") != null,
                    "the local module's class is inside the shipped jar");
        }
        assertTrue(embeddedNote(web), "bundle says the artifact carries unpublished classes");
    }

    // ------------------------------------------------------------------

    private void module(Path dir, String name, String version, String source,
                        String dependency, String group) throws IOException {
        Files.createDirectories(dir.resolve("src/main/java"));
        Files.writeString(dir.resolve("jeno.toml"), """
            [project]
            name = "%s"
            version = "%s"
            %s%s%s
            """.formatted(name, version,
                group == null ? "" : "group = \"" + group + "\"\n",
                name.equals("web") ? "main = \"web.Main\"\n" : "",
                dependency == null ? "" : "\n[deps]\n" + dependency));
        String packageName = name.equals("core") ? "core" : "web";
        String className = name.equals("core") ? "Core" : "Main";
        Files.createDirectories(dir.resolve("src/main/java/" + packageName));
        Files.writeString(dir.resolve("src/main/java/" + packageName + "/" + className + ".java"),
                source);
    }

    private static void writeLock(Path dir, String rows) throws IOException {
        Files.writeString(dir.resolve("jeno.lock"),
                "# generated by jeno — commit to version control, do not edit by hand\n" + rows);
    }

    private List<String> classpath(Path project) {
        String line = stdout(project, new ClasspathCommand(store, project), List.of());
        return List.of(line.strip().split(java.io.File.pathSeparator));
    }

    private static String stdout(Path project, Command command, List<String> args) {
        PrintStream original = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            assertEquals(0, command.execute(args));
        } finally {
            System.setOut(original);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String run(Path project, Command command) {
        PrintStream original = System.err;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            command.execute(List.of());
        } finally {
            System.setErr(original);
        }
        return err.toString(StandardCharsets.UTF_8);
    }

    private static boolean embeddedNote(Path project) {
        PrintStream original = System.err;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            new BundleCommand(new ArtifactStore(project.resolve("target")), project)
                    .execute(List.of());
        } finally {
            System.setErr(original);
        }
        return err.toString(StandardCharsets.UTF_8)
                .contains("is a local checkout — this artifact contains unpublished classes");
    }

    private static String digest(byte[] bytes) throws Exception {
        var md = java.security.MessageDigest.getInstance("SHA-256");
        StringBuilder hex = new StringBuilder();
        for (byte b : md.digest(bytes)) {
            hex.append("%02x".formatted(b));
        }
        return hex.toString();
    }
}
