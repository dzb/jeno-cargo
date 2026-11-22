package com.jujin.mjm.cli;

import com.jujin.mjm.pom.PomSource;
import com.jujin.mjm.project.DescriptorSource;
import com.jujin.mjm.store.ArtifactStore;
import com.jujin.mjm.store.LockFileReader;
import com.jujin.mjm.store.LockedArtifact;
import com.jujin.mjm.store.StoreDescriptorSource;
import com.jujin.mjm.store.StorePomSource;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mj add: one line into [deps], an immediate re-lock against a loopback
 * Central (the {@code mj.central.base} override is the mirror/test channel),
 * and the shape/rule errors surface before anything is written.
 */
class AddCommandTest {

    @TempDir
    Path dir;

    @TempDir
    Path storeDir;

    private HttpServer central;

    @AfterEach
    void stopCentral() {
        if (central != null) {
            central.stop(0);
        }
        System.clearProperty("mjm.central.base");
        resetOffline();
    }

    /**
     * Offline is process-wide static state: leaving it set would poison every
     * later test that expects the network (the same reset {@code OfflineModeTest}
     * uses).
     */
    private static void resetOffline() {
        try {
            java.lang.reflect.Field field = ArtifactStore.class.getDeclaredField("offline");
            field.setAccessible(true);
            field.set(null, false);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot reset the offline flag", e);
        }
    }

    /** A loopback Central serving one pom; the property points mj at it. */
    private void serveCentralPom(String pomXml) throws IOException {
        byte[] body = pomXml.getBytes();
        central = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        central.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        central.start();
        System.setProperty("mjm.central.base",
                "http://localhost:" + central.getAddress().getPort() + "/");
    }

    private static final String LIB_A_POM = """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>lib-a</artifactId>
          <version>1.0.0</version>
        </project>
        """;

    private AddCommand add() throws IOException {
        Files.writeString(dir.resolve("mjm.toml"), """
            [project]
            name = "demo"
            version = "0.1.0"
            """);
        // the served pom is the one being locked; digests come from the store session
        LockService lockService = new LockService(new ArtifactStore(storeDir),
                (g, a, v, base) -> LIB_A_POM,
                text -> {
                    throw new IllegalStateException("no natives here");
                });
        return new AddCommand(lockService, new ArtifactStore(storeDir), dir);
    }

    @Test
    void addWritesTheLineAndReLocks() throws Exception {
        serveCentralPom(LIB_A_POM);

        int exit = add().execute(List.of("com.example:lib-a", "1.0.0"));

        assertEquals(0, exit);
        String mjToml = Files.readString(dir.resolve("mjm.toml"));
        assertTrue(mjToml.contains("\"com.example:lib-a\" = \"1.0.0\""),
                "the line landed in [deps], got:\n" + mjToml);

        List<LockedArtifact> locked = LockFileReader.read(
                Files.readString(dir.resolve("mjm.lock")));
        assertEquals(1, locked.size());
        assertEquals("com.example:lib-a", locked.getFirst().id());
        assertEquals(64, locked.getFirst().sha256().length(), "the re-lock pinned the digest");
        try (var files = Files.list(dir)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")),
                    "a successful add leaves no temporary file behind");
        }
    }

    @Test
    void aFailedAddLeavesTheProjectByteForByte(@TempDir Path projectStore) throws Exception {
        Path project = Files.createDirectories(dir.resolve("project"));
        vendorProject(project);
        ArtifactStore store = new ArtifactStore(projectStore);
        LockService lockService = new LockService(store, new StorePomSource(store),
                new StoreDescriptorSource(store));
        lockService.refresh(project);
        byte[] toml = Files.readAllBytes(project.resolve("mjm.toml"));
        byte[] locked = Files.readAllBytes(project.resolve("mjm.lock"));

        // nothing serves this coordinate: the fetch fails inside prepare
        central = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        central.createContext("/", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        central.start();
        System.setProperty("mjm.central.base",
                "http://localhost:" + central.getAddress().getPort() + "/");

        int exit = new AddCommand(lockService, store, project)
                .execute(List.of("com.example:absent", "1.0.0"));

        assertEquals(1, exit, "the dependency cannot be resolved");
        assertArrayEquals(toml, Files.readAllBytes(project.resolve("mjm.toml")),
                "mjm.toml is byte-for-byte what it was");
        assertArrayEquals(locked, Files.readAllBytes(project.resolve("mjm.lock")),
                "mjm.lock is byte-for-byte what it was");
    }

    /** A project whose one dependency comes from a local Maven-layout repo. */
    private static void vendorProject(Path project) throws IOException {
        Path pom = project.resolve("vendor/com/example/lib-a/1.0.0/lib-a-1.0.0.pom");
        Files.createDirectories(pom.getParent());
        Files.writeString(pom, LIB_A_POM);
        Files.write(pom.resolveSibling("lib-a-1.0.0.jar"), "lib-a".getBytes());
        Files.writeString(project.resolve("mjm.toml"), """
            [project]
            name = "demo"
            version = "0.1.0"

            [repos]
            vendor = "local:%s"

            [deps.vendor]
            "com.example:lib-a" = "1.0.0"
            """.formatted(project.resolve("vendor").toAbsolutePath()));
    }

    @Test
    void aSecondAddOfTheSameIdIsRejected() throws Exception {
        serveCentralPom(LIB_A_POM);
        AddCommand add = add();
        assertEquals(0, add.execute(List.of("com.example:lib-a", "1.0.0")));

        int exit = add.execute(List.of("com.example:lib-a", "2.0.0"));

        assertEquals(1, exit, "an id may appear once — edit mjm.toml instead");
    }

    @Test
    void aMalformedShorthandIsRejectedBeforeWriting() throws Exception {
        AddCommand add = add();

        int exit = add.execute(List.of("mylib", "gh:acme/mylib@latest"));

        assertEquals(1, exit);
        assertTrue(!Files.readString(dir.resolve("mjm.toml")).contains("mylib"),
                "nothing was written");
    }

    @Test
    void discoveringVersionsUnderOfflineFailsBeforeAnyRequest() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        central = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        central.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        central.start();
        System.setProperty("mjm.central.base",
                "http://localhost:" + central.getAddress().getPort() + "/");

        ArtifactStore.goOffline();
        int exit = add().execute(List.of("com.example:lib-a"));

        assertEquals(2, exit, "a version list is a network call — offline refuses it");
        assertEquals(0, requests.get(), "offline means no request is attempted at all");
    }

    @Test
    void anExplicitVersionStillResolvesOfflineFromTheStore() throws Exception {
        serveCentralPom(LIB_A_POM);
        ArtifactStore store = new ArtifactStore(storeDir);
        LockService lockService = new LockService(store, new StorePomSource(store),
                new StoreDescriptorSource(store));

        // warm the store and the URL index online: one real resolution
        Files.writeString(dir.resolve("mjm.toml"), """
            [project]
            name = "demo"
            version = "0.1.0"

            [deps]
            "com.example:lib-a" = "1.0.0"
            """);
        lockService.refresh(dir);

        // a second project on the same store: the explicit-version add must
        // need no network at all
        Path second = Files.createDirectories(dir.resolve("second"));
        Files.writeString(second.resolve("mjm.toml"), """
            [project]
            name = "demo"
            version = "0.1.0"
            """);
        ArtifactStore.goOffline();

        int exit = new AddCommand(lockService, store, second)
                .execute(List.of("com.example:lib-a", "1.0.0"));

        assertEquals(0, exit, "an explicit version resolves from the store");
        assertTrue(Files.readString(second.resolve("mjm.toml"))
                        .contains("\"com.example:lib-a\" = \"1.0.0\""),
                "the line landed in the second project's [deps]");
    }

    @Test
    void appendsIntoTheDepsTableNotItsSubTables() {
        String text = """
            [project]
            name = "demo"
            version = "0.1.0"

            [deps]
            "com.example:lib-a" = "1.0.0"

            [deps.corp]
            "com.corp:internal" = "2.1.0"
            """;
        String updated = AddCommand.appendToDeps(text, "com.example:new", "9.9.9");

        int depsEnd = updated.indexOf("[deps.corp]");
        assertTrue(updated.indexOf("com.example:new") < depsEnd,
                "the new line belongs to [deps] proper, not the corp group");
        assertTrue(updated.contains("\"com.corp:internal\" = \"2.1.0\""),
                "the group survives untouched");
    }

    @Test
    void appendsANewDepsSectionWhenMissing() {
        String text = "[project]\nname = \"demo\"\nversion = \"0.1.0\"\n";
        String updated = AddCommand.appendToDeps(text, "com.example:lib-a", "1.0.0");

        assertTrue(updated.contains("[deps]"));
        assertTrue(updated.contains("\"com.example:lib-a\" = \"1.0.0\""));
    }
}
