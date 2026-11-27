package com.jujin.jeno.cli;

import com.jujin.jeno.resolve.ArtifactLocations;
import com.jujin.jeno.store.ArtifactStore;
import com.jujin.jeno.store.StoreDescriptorSource;
import com.jujin.jeno.store.StorePomSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code jeno classpath}: strict visibility by default (the direct jars only),
 * the whole closure with --test, store paths never copied.
 */
class ClasspathCommandTest {

    @TempDir
    Path dir;

    private ArtifactStore store;

    @BeforeEach
    void setUp() throws Exception {
        PathsOverride.install(dir);
        ProjectLock.useRoot(dir.resolve(".locks"));
        for (String artifact : List.of("lib-a", "lib-b")) {
            Path pom = dir.resolve("vendor/com/example/" + artifact + "/1.0.0/"
                    + artifact + "-1.0.0.pom");
            Files.createDirectories(pom.getParent());
            String dependencies = artifact.equals("lib-a") ? """
                <dependencies>
                  <dependency>
                    <groupId>com.example</groupId>
                    <artifactId>lib-b</artifactId>
                    <version>1.0.0</version>
                  </dependency>
                </dependencies>
                """ : "";
            Files.writeString(pom, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>%s</artifactId>
                  <version>1.0.0</version>
                  %s
                </project>
                """.formatted(artifact, dependencies));
            Files.write(pom.resolveSibling(artifact + "-1.0.0.jar"), artifact.getBytes());
        }
        Files.writeString(dir.resolve("jeno.toml"), """
            [project]
            name = "demo"
            version = "0.1.0"

            [repos.vendor]
            type = "maven"
            url = "vendor"

            [deps]
            "com.example:lib-a" = "vendor:1.0.0"
            """);
        ArtifactLocations.useProjectRoot(dir);
        store = new ArtifactStore(dir.resolve("store"));
        new LockService(store, new StorePomSource(store), new StoreDescriptorSource(store))
                .refresh(dir);
    }

    @AfterEach
    void tearDown() {
        ProjectLock.useUserRoot();
        PathsOverride.clear();
        ArtifactLocations.useProjectRoot(Path.of("").toAbsolutePath());
    }

    @Test
    void theDefaultIsStrictVisibility() throws Exception {
        List<String> entries = entries();

        assertEquals(dir.resolve("target/classes").toString(), entries.get(0));
        assertEquals(2, entries.size(),
                "target/classes plus the one direct dependency: " + entries);
        assertTrue(entries.get(1).endsWith(".jar"));
        assertTrue(entries.get(1).contains("/store/"), "the store path, not a copy");
    }

    @Test
    void testAddsTheClosureAndTheTestClasses() throws Exception {
        List<String> entries = entries("--test");

        assertEquals(dir.resolve("target/test-classes").toString(), entries.get(0));
        assertEquals(dir.resolve("target/classes").toString(), entries.get(1));
        assertEquals(4, entries.size(), "two closure jars join: " + entries);
    }

    @Test
    void fileHoldsExactlyWhatWouldBePrinted(@TempDir Path out) throws Exception {
        Path file = out.resolve("cp.txt");

        String printed = run("--file", file.toString());

        assertEquals("", printed, "--file prints nothing");
        String written = Files.readString(file);
        assertTrue(written.endsWith(System.lineSeparator()));
        assertEquals(String.join(File.pathSeparator, entries()), written.strip());
    }

    @Test
    void jsonReportCarriesTheSameEntriesAndMode() throws Exception {
        String out = run("--json");

        var json = com.jujin.freeway.commons.json.JsonUtils.parseObject(out);
        assertEquals(2, json.getInt("schemaVersion"));
        assertEquals("classpath", json.getString("command"));
        assertEquals("compile", json.getString("mode"));
        assertEquals(entries().size(), json.getArray("entries").size());
        assertEquals(String.join(File.pathSeparator, entries()),
                json.getString("classpath"));
    }

    @Test
    void jsonMissingLockIsOneStructuredError() throws Exception {
        Files.delete(dir.resolve("jeno.lock"));
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            exit = new ClasspathCommand(store, dir).execute(List.of("--json"));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        assertEquals(1, exit);
        assertEquals("", err.toString(StandardCharsets.UTF_8));
        var json = com.jujin.freeway.commons.json.JsonUtils.parseObject(
                out.toString(StandardCharsets.UTF_8));
        assertEquals("failed", json.getString("status"));
        assertEquals("LOCK_MISSING",
                json.getArray("diagnostics").getObject(0).getString("code"));
    }

    @Test
    void aMissingLockSaysWhatToRun() throws Exception {
        Files.delete(dir.resolve("jeno.lock"));

        String errors = errorsOf("--test");

        assertTrue(errors.contains("run jeno lock first"), errors);
    }

    @Test
    void aJarThatLeftTheStoreIsNamedNotSkipped() throws Exception {
        // delete the direct dependency's jar from the store
        List<String> before = entries();
        Path jar = Path.of(before.get(1));
        Files.delete(jar);

        String errors = errorsOf();

        assertTrue(errors.contains("is not in the store — run jeno lock"), errors);
        assertFalse(errors.contains("lib-b"), "the transitive jar is not blamed");
    }

    private List<String> entries(String... args) throws Exception {
        String line = run(args);
        return Arrays.stream(line.strip().split(File.pathSeparator)).toList();
    }

    private String run(String... args) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            assertEquals(0, new ClasspathCommand(store, dir).execute(List.of(args)));
        } finally {
            System.setOut(original);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private String errorsOf(String... args) {
        PrintStream original = System.err;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            new ClasspathCommand(store, dir).execute(List.of(args));
        } finally {
            System.setErr(original);
        }
        return err.toString(StandardCharsets.UTF_8);
    }

    /** The store's Paths override, named so the test reads clearly. */
    private static final class PathsOverride {
        static void install(Path dir) {
            com.jujin.jeno.store.Paths.useForTesting(dir.resolve("store"),
                    dir.resolve("sources"), dir.resolve("locks"));
        }

        static void clear() {
            com.jujin.jeno.store.Paths.resetForTesting();
        }
    }
}
