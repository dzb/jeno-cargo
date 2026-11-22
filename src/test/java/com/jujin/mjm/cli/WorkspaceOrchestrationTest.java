package com.jujin.mjm.cli;

import com.jujin.mjm.store.ArtifactStore;
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
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The root directory runs each module, in dependency order, with a header each. */
class WorkspaceOrchestrationTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private Path core;
    private Path web;

    @BeforeEach
    void setUp() throws Exception {
        com.jujin.mjm.store.Paths.useForTesting(root.resolve(".cache/store"),
                root.resolve(".cache/sources"), root.resolve(".locks"));
        ProjectLock.useRoot(root.resolve(".locks"));
        com.jujin.mjm.resolve.ArtifactLocations.useProjectRoot(root);

        core = root.resolve("libs/core");
        web = root.resolve("apps/web");
        module(core, "core", "com.example", "1.0.0",
                """
                package core;

                public final class Core {
                    public static String message() {
                        return "from-local-core";
                    }
                }
                """, null);
        module(web, "web", "com.example", "0.1.0",
                """
                package web;

                public final class Main {
                    public static void main(String[] args) throws Exception {
                        java.nio.file.Files.writeString(
                                java.nio.file.Path.of("run-proof.txt"), core.Core.message());
                    }
                }
                """, "com.example:core");
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
        // deliberately the wrong order: the dependency must still come first
        Files.writeString(root.resolve("mjm.ws.toml"), """
            [ws]
            modules = ["apps/web", "libs/core"]
            """);
        store = new ArtifactStore(root.resolve(".cache/store"));
    }

    @AfterEach
    void tearDown() throws IOException {
        restore(core.resolve("target"));
        ProjectLock.useUserRoot();
        com.jujin.mjm.store.Paths.resetForTesting();
        com.jujin.mjm.resolve.ArtifactLocations.useProjectRoot(Path.of("").toAbsolutePath());
    }

    @Test
    void theRootBuildsDependenciesFirstDespiteTheFileOrder() {
        Result result = run(root, new BuildCommand(store, root), List.of());

        assertEquals(0, result.exit());
        int coreHeader = result.out().indexOf("libs/core\n");
        int webHeader = result.out().indexOf("apps/web\n");
        assertTrue(coreHeader >= 0 && webHeader > coreHeader,
                "dependency header comes first: " + result.out());
        assertTrue(result.out().contains("built: 2, failed: 0"), result.out());
        assertTrue(Files.isRegularFile(core.resolve("target/classes/core/Core.class")));
        assertTrue(Files.isRegularFile(web.resolve("target/classes/web/Main.class")));
    }

    @Test
    void aDependencyCycleIsNamedNotLooped() throws Exception {
        appendTo(core.resolve("mjm.toml"), """

            [deps]
            "com.example:web" = "0.1.0"
            """);

        Result result = run(root, new BuildCommand(store, root), List.of());

        assertEquals(1, result.exit());
        assertTrue(result.err().contains("cycle"), result.err());
        assertTrue(result.err().contains("libs/core") && result.err().contains("apps/web"),
                "the cycle names both ends: " + result.err());
    }

    @Test
    void moduleSelectsOneModuleOnly() {
        Result result = run(root, new BuildCommand(store, root),
                List.of("--module", "libs/core"));

        assertEquals(0, result.exit());
        assertTrue(result.out().contains("libs/core\n"), result.out());
        assertFalse(result.out().contains("apps/web"), "only the named module ran: " + result.out());
        assertTrue(result.out().contains("built: 1, failed: 0"), result.out());
    }

    @Test
    void aFailingTestModuleDoesNotStopTheOthers() throws Exception {
        Files.delete(core.resolve("mjm.lock"));  // core fails at its first check

        Result result = run(root, new TestCommand(store, root), List.of());

        assertEquals(1, result.exit());
        assertTrue(result.out().contains("apps/web"), "the later module still ran: " + result.out());
        assertTrue(result.out().contains("failed modules: libs/core"), result.out());
        assertTrue(result.out().contains("modules: 1, failed: 1"), result.out());
    }

    @Test
    void aTargetThatCannotBeRemovedDoesNotStopTheOthers() throws Exception {
        Files.createDirectories(core.resolve("target/classes"));
        Files.createDirectories(web.resolve("target/classes"));
        Files.setPosixFilePermissions(core.resolve("target"),
                PosixFilePermissions.fromString("r-xr-xr-x"));

        Result result = run(root, new CleanCommand(root), List.of());

        assertEquals(1, result.exit(), result.out() + result.err());
        assertFalse(Files.exists(web.resolve("target")), "the later module was cleaned");
        assertTrue(result.out().contains("failed modules: libs/core"), result.out());
    }

    @Test
    void runAtTheRootDemandsAModule() {
        Result bare = run(root, new RunCommand(store, root), List.of());

        assertEquals(2, bare.exit());
        assertTrue(bare.err().contains("this directory is a workspace root — say which module:"),
                bare.err());

        Result built = run(root, new BuildCommand(store, root), List.of());
        assertEquals(0, built.exit(), built.out() + built.err());

        Result selected = run(root, new RunCommand(store, root),
                List.of("--module", "apps/web", "web.Main"));

        assertEquals(0, selected.exit(), selected.out() + selected.err());
        try {
            assertEquals("from-local-core",
                    Files.readString(web.resolve("run-proof.txt")).strip());
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void withoutAWorkspaceFileNothingChanges(@TempDir Path plain) throws Exception {
        Files.writeString(plain.resolve("mjm.toml"), """
            [project]
            name = "solo"
            version = "0.1.0"
            """);
        Files.createDirectories(plain.resolve("src/main/java/solo"));
        Files.writeString(plain.resolve("src/main/java/solo/App.java"), """
            package solo;

            public final class App {
                public static void main(String[] args) {
                }
            }
            """);
        Files.writeString(plain.resolve("mjm.lock"),
                "# generated by mjm — commit to version control, do not edit by hand\n");

        Result result = run(plain, new BuildCommand(store, plain), List.of());

        assertEquals(0, result.exit());
        assertEquals("compiled 1 source file(s) into " + plain.resolve("target/classes") + "\n",
                result.out(), "no header, no summary, exactly the old line");
    }

    // ------------------------------------------------------------------

    private void module(Path dir, String name, String group, String version, String source,
                        String dependency) throws IOException {
        StringBuilder toml = new StringBuilder("[project]\n")
                .append("name = \"").append(name).append("\"\n");
        if (group != null) {
            toml.append("group = \"").append(group).append("\"\n");
        }
        toml.append("version = \"").append(version).append("\"\n");
        if (dependency != null) {
            toml.append("\n[deps]\n\"").append(dependency).append("\" = \"1.0.0\"\n");
        }
        Files.createDirectories(dir.resolve("src/main/java"));
        Files.writeString(dir.resolve("mjm.toml"), toml.toString());
        String packageName = name;
        Files.createDirectories(dir.resolve("src/main/java/" + packageName));
        Files.writeString(dir.resolve("src/main/java/" + packageName + "/"
                + (name.equals("core") ? "Core" : "Main") + ".java"), source);
    }

    private static void appendTo(Path file, String text) throws IOException {
        Files.writeString(file, Files.readString(file) + text);
    }

    private static void writeLock(Path dir, String rows) throws IOException {
        Files.writeString(dir.resolve("mjm.lock"),
                "# generated by mjm — commit to version control, do not edit by hand\n" + rows);
    }

    private static void restore(Path target) {
        if (!Files.exists(target)) {
            return;
        }
        try {
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (IOException ignored) {
            // best effort: the temp dir cleanup will report the real problem
        }
    }

    private static Result run(Path dir, Command command, List<String> args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            exit = command.execute(args);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new Result(exit, out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
    }

    private record Result(int exit, String out, String err) {}
}
