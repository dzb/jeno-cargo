package com.jujin.jeno.cli;

import com.jujin.jeno.resolve.LockFile;
import com.jujin.jeno.resolve.ResolvedGraph;
import com.jujin.jeno.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The resources contract: {@code src/main/resources} lands in {@code
 * target/classes}, {@code src/test/resources} in {@code target/test-classes}
 * — otherwise classpath resources only exist when a previous Maven build
 * left them behind (the freeway-boot CI failure that found this gap).
 */
class SourceResourcesTest {

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
    void buildAndTestCarryTheirResources(@TempDir Path storeDir) throws Exception {
        Files.createDirectories(dir.resolve("src/main/java/demo"));
        Files.createDirectories(dir.resolve("src/main/resources/demo"));
        Files.createDirectories(dir.resolve("src/test/java/demo"));
        Files.createDirectories(dir.resolve("src/test/resources/demo"));
        Files.writeString(dir.resolve("jeno.toml"), """
            [project]
            name = "demo"
            version = "0.1.0"
            """);
        Files.writeString(dir.resolve("src/main/java/demo/App.java"), """
            package demo;

            public final class App {
            }
            """);
        Files.writeString(dir.resolve("src/main/resources/demo/app.properties"),
                "greeting=hello");
        Files.writeString(dir.resolve("src/test/resources/demo/test.properties"),
                "scope=test");
        Files.writeString(dir.resolve("src/test/java/demo/Tests.java"), """
            package demo;

            import com.jujin.jeno.test.JenoTest;
            import com.jujin.jeno.test.JenoAssert;

            public class Tests {
                @JenoTest
                void mainResourceIsOnTheClasspath() throws Exception {
                    JenoAssert.equals("greeting=hello", resource("demo/app.properties"));
                }

                @JenoTest
                void testResourceIsOnTheClasspath() throws Exception {
                    JenoAssert.equals("scope=test", resource("demo/test.properties"));
                }

                private String resource(String name) throws Exception {
                    try (var in = Thread.currentThread().getContextClassLoader()
                            .getResourceAsStream(name)) {
                        return new String(in.readAllBytes()).strip();
                    }
                }
            }
            """);
        Files.writeString(dir.resolve("jeno.lock"),
                LockFile.serialize(new ResolvedGraph(List.of(), List.of(), Map.of()),
                        url -> "e".repeat(64)));

        ArtifactStore store = new ArtifactStore(storeDir);
        assertEquals(0, new BuildCommand(store, dir).execute(List.of()), "build exits zero");
        assertTrue(Files.exists(dir.resolve("target/classes/demo/app.properties")),
                "main resources landed in target/classes");

        int exit;
        try (var central = VendoredToolchain.start()) {
            exit = new TestCommand(store, dir).execute(List.of());
        }
        assertEquals(0, exit, "the fixture read both resources on the forked classpath");
    }

    @Test
    void aResourceOnlyModuleBuilds(@TempDir Path storeDir) throws Exception {
        Files.createDirectories(dir.resolve("src/main/resources/demo"));
        Files.writeString(dir.resolve("jeno.toml"), """
            [project]
            name = "assets"
            version = "0.1.0"
            """);
        Files.writeString(dir.resolve("src/main/resources/demo/data.txt"), "payload");
        Files.writeString(dir.resolve("jeno.lock"),
                LockFile.serialize(new ResolvedGraph(List.of(), List.of(), Map.of()),
                        url -> "e".repeat(64)));

        int exit = new BuildCommand(new ArtifactStore(storeDir), dir).execute(List.of());

        assertEquals(0, exit, "resources are a build product even without Java sources");
        assertEquals("payload",
                Files.readString(dir.resolve("target/classes/demo/data.txt")).strip());
    }

    @Test
    void aProjectWithoutSourcesOrResourcesIsStillAnError(@TempDir Path storeDir) throws Exception {
        Files.writeString(dir.resolve("jeno.toml"), """
            [project]
            name = "empty"
            version = "0.1.0"
            """);
        Files.writeString(dir.resolve("jeno.lock"),
                LockFile.serialize(new ResolvedGraph(List.of(), List.of(), Map.of()),
                        url -> "e".repeat(64)));

        int exit = new BuildCommand(new ArtifactStore(storeDir), dir).execute(List.of());

        assertEquals(1, exit);
    }
}
