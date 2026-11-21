package com.jujin.mjm.cli;

import com.jujin.mjm.resolve.LockFile;
import com.jujin.mjm.resolve.ResolvedGraph;
import com.jujin.mjm.store.ArtifactStore;
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

    @Test
    void buildAndTestCarryTheirResources(@TempDir Path storeDir) throws Exception {
        Files.createDirectories(dir.resolve("src/main/java/demo"));
        Files.createDirectories(dir.resolve("src/main/resources/demo"));
        Files.createDirectories(dir.resolve("src/test/java/demo"));
        Files.createDirectories(dir.resolve("src/test/resources/demo"));
        Files.writeString(dir.resolve("mjm.toml"), """
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

            import com.jujin.mjm.test.MjmTest;
            import com.jujin.mjm.test.MjmAssert;

            public class Tests {
                @MjmTest
                void mainResourceIsOnTheClasspath() throws Exception {
                    MjmAssert.equals("greeting=hello", resource("demo/app.properties"));
                }

                @MjmTest
                void testResourceIsOnTheClasspath() throws Exception {
                    MjmAssert.equals("scope=test", resource("demo/test.properties"));
                }

                private String resource(String name) throws Exception {
                    try (var in = Thread.currentThread().getContextClassLoader()
                            .getResourceAsStream(name)) {
                        return new String(in.readAllBytes()).strip();
                    }
                }
            }
            """);
        Files.writeString(dir.resolve("mjm.lock"),
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
}
