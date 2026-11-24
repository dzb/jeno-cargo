package com.jujin.jeno.cli;

import com.jujin.jeno.pom.PomSource;
import com.jujin.jeno.resolve.ArtifactLocations;
import com.jujin.jeno.project.DescriptorSource;
import com.jujin.jeno.store.ArtifactStore;
import com.jujin.jeno.store.LockFileReader;
import com.jujin.jeno.store.LockedArtifact;
import com.jujin.jeno.store.StoreDescriptorSource;
import com.jujin.jeno.store.StorePomSource;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * jeno add: one line into [deps], an immediate re-lock against a loopback
 * Central (the {@code jeno.central.base} override is the mirror/test channel),
 * and the shape/rule errors surface before anything is written.
 */
class AddCommandTest {

    @TempDir
    Path dir;

    @TempDir
    Path storeDir;

    private HttpServer central;

    @org.junit.jupiter.api.BeforeEach
    void isolateProjectLocks() {
        ProjectLock.useRoot(dir.resolve(".locks"));
    }

    @AfterEach
    void stopCentral() {
        if (central != null) {
            central.stop(0);
        }
        System.clearProperty("jeno.central.base");
        System.clearProperty("jeno.github.host");
        System.clearProperty("jeno.search.host");
        ProjectLock.useUserRoot();
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

    /** A loopback Central serving one pom; the property points jeno at it. */
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
        System.setProperty("jeno.central.base",
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
        Files.writeString(dir.resolve("jeno.toml"), """
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
        String mjToml = Files.readString(dir.resolve("jeno.toml"));
        assertTrue(mjToml.contains("\"com.example:lib-a\" = \"1.0.0\""),
                "the line landed in [deps], got:\n" + mjToml);

        List<LockedArtifact> locked = LockFileReader.read(
                Files.readString(dir.resolve("jeno.lock")));
        assertEquals(1, locked.size());
        assertEquals("com.example:lib-a", locked.getFirst().id());
        assertEquals(64, locked.getFirst().sha256().length(), "the re-lock pinned the digest");
        try (var files = Files.list(dir)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")),
                    "a successful add leaves no temporary file behind");
        }
    }

    @Test
    void jsonAddReportsTheDependencyAndUpdatedLock() throws Exception {
        serveCentralPom(LIB_A_POM);
        AddCommand command = add();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        int exit;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            exit = command.execute(List.of("com.example:lib-a", "1.0.0", "--json"));
        } finally {
            System.setOut(original);
        }

        assertEquals(0, exit);
        var json = com.jujin.freeway.commons.json.JsonUtils.parseObject(
                out.toString(StandardCharsets.UTF_8));
        assertEquals("add", json.getString("command"));
        assertEquals("mutate", json.getString("mode"));
        assertEquals("com.example:lib-a", json.getObject("dependency").getString("id"));
        assertEquals("1.0.0", json.getObject("dependency").getString("value"));
        assertEquals(1, json.getInt("artifactCount"));
        assertEquals(true, json.getBoolean("lockUpdated"));
        assertTrue(Files.readString(dir.resolve("jeno.toml"))
                .contains("\"com.example:lib-a\" = \"1.0.0\""));
    }

    @Test
    void aFailedAddLeavesTheProjectByteForByte(@TempDir Path projectStore) throws Exception {
        Path project = Files.createDirectories(dir.resolve("project"));
        vendorProject(project);
        ArtifactStore store = new ArtifactStore(projectStore);
        LockService lockService = new LockService(store, new StorePomSource(store),
                new StoreDescriptorSource(store));
        lockService.refresh(project);
        byte[] toml = Files.readAllBytes(project.resolve("jeno.toml"));
        byte[] locked = Files.readAllBytes(project.resolve("jeno.lock"));

        // nothing serves this coordinate: the fetch fails inside prepare
        central = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        central.createContext("/", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        central.start();
        System.setProperty("jeno.central.base",
                "http://localhost:" + central.getAddress().getPort() + "/");

        int exit = new AddCommand(lockService, store, project)
                .execute(List.of("com.example:absent", "1.0.0"));

        assertEquals(1, exit, "the dependency cannot be resolved");
        assertArrayEquals(toml, Files.readAllBytes(project.resolve("jeno.toml")),
                "jeno.toml is byte-for-byte what it was");
        assertArrayEquals(locked, Files.readAllBytes(project.resolve("jeno.lock")),
                "jeno.lock is byte-for-byte what it was");
    }

    /** A project whose one dependency comes from a local Maven-layout repo. */
    private static void vendorProject(Path project) throws IOException {
        Path pom = project.resolve("vendor/com/example/lib-a/1.0.0/lib-a-1.0.0.pom");
        Files.createDirectories(pom.getParent());
        Files.writeString(pom, LIB_A_POM);
        Files.write(pom.resolveSibling("lib-a-1.0.0.jar"), "lib-a".getBytes());
        Files.writeString(project.resolve("jeno.toml"), """
            [project]
            name = "demo"
            version = "0.1.0"

            [repos]
            vendor = "local:%s"

            [deps]
            "com.example:lib-a" = "vendor:1.0.0"
            """.formatted(project.resolve("vendor").toAbsolutePath()));
    }

    @Test
    void addWritesARepositoryValueShape(@TempDir Path projectStore) throws Exception {
        Path project = Files.createDirectories(dir.resolve("value-shape"));
        Files.createDirectories(project.resolve("vendor/com/example/lib-b/1.0.0"));
        Files.writeString(project.resolve("vendor/com/example/lib-b/1.0.0/lib-b-1.0.0.pom"),
                LIB_A_POM.replace("lib-a", "lib-b"));
        Files.write(project.resolve("vendor/com/example/lib-b/1.0.0/lib-b-1.0.0.jar"),
                "lib-b".getBytes());
        Files.writeString(project.resolve("jeno.toml"), """
            [project]
            name = "demo"
            version = "0.1.0"

            [repos]
            vendor = "local:vendor"
            """);

        ArtifactLocations.useProjectRoot(project);
        ArtifactStore store = new ArtifactStore(projectStore);
        int exit;
        try {
            exit = new AddCommand(new LockService(store, new StorePomSource(store),
                    new StoreDescriptorSource(store)), store, project)
                    .execute(List.of("com.example:lib-b", "vendor:1.0.0"));
        } finally {
            ArtifactLocations.useProjectRoot(Path.of("").toAbsolutePath());
        }

        assertEquals(0, exit);
        assertTrue(Files.readString(project.resolve("jeno.toml"))
                        .contains("\"com.example:lib-b\" = \"vendor:1.0.0\""),
                "the value shape lands in [deps]");
        assertTrue(Files.readString(project.resolve("jeno.lock"))
                        .contains("source = \"local:vendor\""),
                "and resolves through the named repository");
    }

    @Test
    void addUnderstandsTheNativeShorthand(@TempDir Path projectStore) throws Exception {
        String descriptor = """
            [project]
            name = "mylib"
            version = "1.0.0"
            """;
        HttpServer github = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        github.createContext("/acme/mylib/releases/download/v1.0.0/mylib-1.0.0.jeno.toml",
                exchange -> {
                    byte[] body = descriptor.getBytes();
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        github.createContext("/acme/mylib/releases/download/v1.0.0/mylib-1.0.0.jar",
                exchange -> {
                    byte[] body = "mylib-jar".getBytes();
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        github.start();
        String host = "http://localhost:" + github.getAddress().getPort();
        System.setProperty("jeno.github.host", host);
        try {
            Path project = Files.createDirectories(dir.resolve("native"));
            Files.writeString(project.resolve("jeno.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                """);
            ArtifactStore store = new ArtifactStore(projectStore);

            int exit = new AddCommand(new LockService(store, new StorePomSource(store),
                    new StoreDescriptorSource(store)), store, project)
                    .execute(List.of("acme/mylib@v1.0.0"));

            assertEquals(0, exit);
            assertTrue(Files.readString(project.resolve("jeno.toml"))
                            .contains("\"mylib\" = \"gh:acme/mylib@v1.0.0\""),
                    "owner/repo@tag becomes the gh: value, keyed by the repo name");
        } finally {
            github.stop(0);
            System.clearProperty("jeno.github.host");
        }
    }

    @Test
    void aNativeShorthandWithoutATagIsRefused() throws Exception {
        Path project = Files.createDirectories(dir.resolve("no-tag"));
        Files.writeString(project.resolve("jeno.toml"), """
            [project]
            name = "demo"
            version = "0.1.0"
            """);
        byte[] before = Files.readAllBytes(project.resolve("jeno.toml"));
        ArtifactStore store = new ArtifactStore(storeDir);

        int exit = new AddCommand(new LockService(store, new StorePomSource(store),
                new StoreDescriptorSource(store)), store, project)
                .execute(List.of("acme/mylib"));

        assertEquals(2, exit);
        assertArrayEquals(before, Files.readAllBytes(project.resolve("jeno.toml")),
                "nothing was written");
    }

    @Test
    void aFailedLockWritePutsTheManifestBack(@TempDir Path projectStore) throws Exception {
        Path project = Files.createDirectories(dir.resolve("rollback"));
        vendorProject(project);
        Files.createDirectories(project.resolve("vendor/com/example/lib-b/1.0.0"));
        Files.writeString(project.resolve("vendor/com/example/lib-b/1.0.0/lib-b-1.0.0.pom"),
                LIB_A_POM.replace("lib-a", "lib-b"));
        Files.write(project.resolve("vendor/com/example/lib-b/1.0.0/lib-b-1.0.0.jar"),
                "lib-b".getBytes());
        ArtifactLocations.useProjectRoot(project);
        ArtifactStore store = new ArtifactStore(projectStore);
        LockService lockService = new LockService(store, new StorePomSource(store),
                new StoreDescriptorSource(store));
        byte[] before;
        try {
            lockService.refresh(project);
            // make the lock destination unwritable as a file: a non-empty directory
            Files.delete(project.resolve("jeno.lock"));
            Files.createDirectories(project.resolve("jeno.lock"));
            Files.writeString(project.resolve("jeno.lock/keep"), "x");
            before = Files.readAllBytes(project.resolve("jeno.toml"));

            int exit = new AddCommand(lockService, store, project)
                    .execute(List.of("com.example:lib-b", "vendor:1.0.0"));

            assertEquals(1, exit, "the lock cannot be written");
        } finally {
            ArtifactLocations.useProjectRoot(Path.of("").toAbsolutePath());
        }

        assertArrayEquals(before, Files.readAllBytes(project.resolve("jeno.toml")),
                "a manifest written before the lock failed is restored byte for byte");
        try (var files = Files.list(project)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")),
                    "and no temporary file is left behind");
        }
    }

    @Test
    void aRewriteKeepsTheManifestsPermissions(@TempDir Path projectStore) throws Exception {
        Path project = Files.createDirectories(dir.resolve("permissions"));
        vendorProject(project);
        Files.createDirectories(project.resolve("vendor/com/example/lib-b/1.0.0"));
        Files.writeString(project.resolve("vendor/com/example/lib-b/1.0.0/lib-b-1.0.0.pom"),
                LIB_A_POM.replace("lib-a", "lib-b"));
        Files.write(project.resolve("vendor/com/example/lib-b/1.0.0/lib-b-1.0.0.jar"),
                "lib-b".getBytes());
        ArtifactLocations.useProjectRoot(project);
        ArtifactStore store = new ArtifactStore(projectStore);
        LockService lockService = new LockService(store, new StorePomSource(store),
                new StoreDescriptorSource(store));
        Set<PosixFilePermission> shared;
        try {
            lockService.refresh(project);
            shared = PosixFilePermissions.fromString("rw-r--r--");
            Files.setPosixFilePermissions(project.resolve("jeno.toml"), shared);
            Files.setPosixFilePermissions(project.resolve("jeno.lock"), shared);

            assertEquals(0, new AddCommand(lockService, store, project)
                    .execute(List.of("com.example:lib-b", "vendor:1.0.0")));
        } finally {
            ArtifactLocations.useProjectRoot(Path.of("").toAbsolutePath());
        }

        // createTempFile makes an owner-only file; the manifest must not lose
        // the access a plain write would have kept
        assertEquals(shared, Files.getPosixFilePermissions(project.resolve("jeno.toml")),
                "the manifest keeps its permissions across an atomic rewrite");
        assertEquals(shared, Files.getPosixFilePermissions(project.resolve("jeno.lock")),
                "and so does the lockfile");
    }

    @Test
    void aVendoredPomIsRereadAfterItChanges(@TempDir Path projectStore) throws Exception {
        Path project = Files.createDirectories(dir.resolve("reread"));
        vendorProject(project);
        Files.createDirectories(project.resolve("vendor/com/example/lib-b/1.0.0"));
        Files.writeString(project.resolve("vendor/com/example/lib-b/1.0.0/lib-b-1.0.0.pom"),
                LIB_A_POM.replace("lib-a", "lib-b"));
        Files.write(project.resolve("vendor/com/example/lib-b/1.0.0/lib-b-1.0.0.jar"),
                "lib-b".getBytes());
        ArtifactLocations.useProjectRoot(project);
        ArtifactStore store = new ArtifactStore(projectStore);
        LockService lockService = new LockService(store, new StorePomSource(store),
                new StoreDescriptorSource(store));
        try {
            assertEquals(1, lockService.refresh(project).artifacts().size());

            // the vendor tree is a working copy: lib-a gains a dependency on
            // lib-b, and the next lock has to notice — the URL is unchanged, so
            // an index hit would serve the old pom forever
            Path pom = project.resolve("vendor/com/example/lib-a/1.0.0/lib-a-1.0.0.pom");
            Files.writeString(pom, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>lib-a</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>lib-b</artifactId>
                      <version>1.0.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

            var graph = lockService.refresh(project);
            assertEquals(2, graph.artifacts().size(),
                    "the rewritten pom must be read again, not answered from the URL index");
        } finally {
            ArtifactLocations.useProjectRoot(Path.of("").toAbsolutePath());
        }
    }

    @Test
    void anAddWaitsWhileTheProjectLockIsHeld(@TempDir Path projectStore) throws Exception {
        Path project = Files.createDirectories(dir.resolve("waits"));
        Files.writeString(project.resolve("jeno.toml"), """
            [project]
            name = "demo"
            version = "0.1.0"
            """);
        ArtifactStore store = new ArtifactStore(projectStore);
        LockService lockService = new LockService(store, new StorePomSource(store),
                new StoreDescriptorSource(store));
        System.setProperty("jeno.central.base", "http://127.0.0.1:9/");

        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> ProjectLock.with(project, () -> {
            held.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 0;
        }));
        holder.start();
        assertTrue(held.await(10, TimeUnit.SECONDS));

        AtomicInteger exit = new AtomicInteger(-1);
        Thread add = new Thread(() -> exit.set(new AddCommand(lockService, store, project)
                .execute(List.of("com.example:lib-a", "1.0.0"))));
        add.start();
        Thread.sleep(300);
        assertEquals(-1, exit.get(), "the add waits for the project lock instead of writing");

        release.countDown();
        add.join(10_000);
        holder.join(10_000);
        assertNotEquals(-1, exit.get(), "and proceeds once the lock is free");
    }

    @Test
    void twoAddsInOneProjectDoNotLoseEachOther(@TempDir Path projectStore) throws Exception {
        Path project = Files.createDirectories(dir.resolve("concurrent"));
        for (String artifact : List.of("lib-a", "lib-b")) {
            Path pom = project.resolve("vendor/com/example/" + artifact + "/1.0.0/"
                    + artifact + "-1.0.0.pom");
            Files.createDirectories(pom.getParent());
            Files.writeString(pom, LIB_A_POM.replace("lib-a", artifact));
            Files.write(pom.resolveSibling(artifact + "-1.0.0.jar"), artifact.getBytes());
        }
        Files.writeString(project.resolve("jeno.toml"), """
            [project]
            name = "demo"
            version = "0.1.0"

            [repos]
            vendor = "local:vendor"
            """);
        ArtifactLocations.useProjectRoot(project);
        ArtifactStore store = new ArtifactStore(projectStore);
        LockService lockService = new LockService(store, new StorePomSource(store),
                new StoreDescriptorSource(store));
        try {
            Thread first = new Thread(() -> new AddCommand(lockService, store, project)
                    .execute(List.of("com.example:lib-a", "vendor:1.0.0")));
            Thread second = new Thread(() -> new AddCommand(lockService, store, project)
                    .execute(List.of("com.example:lib-b", "vendor:1.0.0")));
            first.start();
            second.start();
            first.join(20_000);
            second.join(20_000);
        } finally {
            ArtifactLocations.useProjectRoot(Path.of("").toAbsolutePath());
        }

        String manifest = Files.readString(project.resolve("jeno.toml"));
        assertTrue(manifest.contains("\"com.example:lib-a\" = \"vendor:1.0.0\""), manifest);
        assertTrue(manifest.contains("\"com.example:lib-b\" = \"vendor:1.0.0\""),
                "the second add read the first one's manifest: " + manifest);
        assertEquals(2, LockFileReader.read(Files.readString(project.resolve("jeno.lock"))).size(),
                "both dependencies are locked");
    }

    @Test
    void aSecondAddOfTheSameIdIsRejected() throws Exception {
        serveCentralPom(LIB_A_POM);
        AddCommand add = add();
        assertEquals(0, add.execute(List.of("com.example:lib-a", "1.0.0")));

        int exit = add.execute(List.of("com.example:lib-a", "2.0.0"));

        assertEquals(1, exit, "an id may appear once — edit jeno.toml instead");
    }

    @Test
    void aMalformedShorthandIsRejectedBeforeWriting() throws Exception {
        AddCommand add = add();

        int exit = add.execute(List.of("mylib", "gh:acme/mylib@latest"));

        assertEquals(1, exit);
        assertTrue(!Files.readString(dir.resolve("jeno.toml")).contains("mylib"),
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
        System.setProperty("jeno.central.base",
                "http://localhost:" + central.getAddress().getPort() + "/");

        ArtifactStore.goOffline();
        int exit = add().execute(List.of("com.example:lib-a"));

        assertEquals(2, exit, "a version list is a network call — offline refuses it");
        assertEquals(0, requests.get(), "offline means no request is attempted at all");
    }

    @Test
    void namedRepositoryDiscoveryAlsoHonoursOffline() throws Exception {
        Path repo = dir.resolve("offline-repo");
        Files.createDirectories(repo.resolve("widget"));
        Files.writeString(repo.resolve("widget/index.jeno.toml"), """
            schemaVersion = 1
            name = "widget"
            versions = ["1.0.0"]
            """);
        AddCommand command = add();
        Files.writeString(dir.resolve("jeno.toml"), """
            [project]
            name = "demo"
            version = "0.1.0"

            [repos]
            mine = "jeno:local:%s"
            """.formatted(repo));
        ArtifactStore.goOffline();

        int exit = command.execute(List.of("widget", "--repo", "mine"));

        assertEquals(2, exit, "named discovery is still network-shaped and must respect offline");
        assertTrue(Files.readString(dir.resolve("jeno.toml")).contains("mine ="),
                "offline refusal leaves the manifest untouched");
    }

    @Test
    void anExplicitVersionStillResolvesOfflineFromTheStore() throws Exception {
        serveCentralPom(LIB_A_POM);
        ArtifactStore store = new ArtifactStore(storeDir);
        LockService lockService = new LockService(store, new StorePomSource(store),
                new StoreDescriptorSource(store));

        // warm the store and the URL index online: one real resolution
        Files.writeString(dir.resolve("jeno.toml"), """
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
        Files.writeString(second.resolve("jeno.toml"), """
            [project]
            name = "demo"
            version = "0.1.0"
            """);
        ArtifactStore.goOffline();

        int exit = new AddCommand(lockService, store, second)
                .execute(List.of("com.example:lib-a", "1.0.0"));

        assertEquals(0, exit, "an explicit version resolves from the store");
        assertTrue(Files.readString(second.resolve("jeno.toml"))
                        .contains("\"com.example:lib-a\" = \"1.0.0\""),
                "the line landed in the second project's [deps]");
    }

    @Test
    void namedRepositoryDiscoveryListsIndexVersionsWithoutWriting() throws Exception {
        Path repo = dir.resolve("named-repo");
        Files.createDirectories(repo.resolve("widget"));
        Files.writeString(repo.resolve("widget/index.jeno.toml"), """
            schemaVersion = 1
            name = "widget"
            versions = ["1.0.0", "1.2.0"]
            """);
        Files.writeString(dir.resolve("jeno.toml"), """
            [project]
            name = "demo"
            version = "0.1.0"

            [repos]
            mine = "jeno:local:%s"
            """.formatted(repo));
        String before = Files.readString(dir.resolve("jeno.toml"));
        ArtifactStore store = new ArtifactStore(storeDir);
        AddCommand command = new AddCommand(
                new LockService(store, (g, a, v, base) -> null, text -> null), store, dir);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        int exit;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            exit = command.execute(List.of("widget", "--repo", "mine"));
        } finally {
            System.setOut(original);
        }

        assertEquals(0, exit);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("1.0.0"));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("1.2.0"));
        assertEquals(before, Files.readString(dir.resolve("jeno.toml")),
                "discovery must not mutate the manifest");
    }

    @Test
    void namedRepositoryDiscoveryIsStructuredInJson() throws Exception {
        Path repo = dir.resolve("named-json");
        Files.createDirectories(repo.resolve("widget"));
        Files.writeString(repo.resolve("widget/index.jeno.toml"), """
            schemaVersion = 1
            name = "widget"
            versions = ["1.0.0", "1.2.0"]
            """);
        Files.writeString(dir.resolve("jeno.toml"), """
            [project]
            name = "demo"
            version = "0.1.0"

            [repos]
            mine = "jeno:local:%s"
            """.formatted(repo));
        ArtifactStore store = new ArtifactStore(storeDir);
        AddCommand command = new AddCommand(
                new LockService(store, (g, a, v, base) -> null, text -> null), store, dir);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        int exit;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            exit = command.execute(List.of("widget", "--repo", "mine", "--json"));
        } finally {
            System.setOut(original);
        }

        assertEquals(0, exit);
        var json = com.jujin.freeway.commons.json.JsonUtils.parseObject(
                out.toString(StandardCharsets.UTF_8));
        assertEquals("discover", json.getString("mode"));
        assertEquals("mine", json.getString("repository"));
        assertEquals(2, json.getArray("versions").size());
        assertEquals(false, json.getBoolean("lockUpdated"));
    }

    @Test
    void appendsIntoDepsBeforeItsSiblingSections() {
        String text = """
            [project]
            name = "demo"
            version = "0.1.0"

            [deps]
            "com.example:lib-a" = "1.0.0"

            [deps-dev]
            "com.example:test-kit" = "2.0.0"
            """;
        String updated = AddCommand.appendToDeps(text, "com.example:new", "9.9.9");

        int devSection = updated.indexOf("[deps-dev]");
        assertTrue(updated.indexOf("com.example:new") < devSection,
                "the new line belongs to [deps], before the deps-dev sibling");
        assertTrue(updated.contains("\"com.example:test-kit\" = \"2.0.0\""),
                "the sibling section survives untouched");
    }

    @Test
    void appendsANewDepsSectionWhenMissing() {
        String text = "[project]\nname = \"demo\"\nversion = \"0.1.0\"\n";
        String updated = AddCommand.appendToDeps(text, "com.example:lib-a", "1.0.0");

        assertTrue(updated.contains("[deps]"));
        assertTrue(updated.contains("\"com.example:lib-a\" = \"1.0.0\""));
    }

    @Test
    void aSuccessfulVersionListAnswersZeroAndWritesNothing() throws Exception {
        serveMetadata("""
            <metadata>
              <versioning>
                <versions>
                  <version>1.0.0</version>
                  <version>1.1.0</version>
                </versions>
              </versioning>
            </metadata>
            """);
        AddCommand add = add();
        String before = Files.readString(dir.resolve("jeno.toml"));

        int exit = add.execute(List.of("com.example:lib-a"));

        assertEquals(0, exit, "a completed query is a success");
        assertEquals(before, Files.readString(dir.resolve("jeno.toml")),
                "a query never edits the manifest");
    }

    @Test
    void anEmptyVersionListIsAFailure() throws Exception {
        serveMetadata("<metadata><versioning><versions/></versioning></metadata>");

        assertEquals(1, add().execute(List.of("com.example:absent")),
                "nothing to suggest is not a successful query");
    }

    @Test
    void aSuccessfulSearchAnswersZeroAndWritesNothing() throws Exception {
        serveSearch("""
            {"response":{"numFound":1,"docs":[{"id":"com.example:lib-a",
             "latestVersion":"1.0.0"}]}}
            """);
        AddCommand add = add();
        String before = Files.readString(dir.resolve("jeno.toml"));

        int exit = add.execute(List.of("lib-a"));

        assertEquals(0, exit, "a completed search is a success");
        assertEquals(before, Files.readString(dir.resolve("jeno.toml")),
                "search only prints candidates");
    }

    @Test
    void anEmptySearchIsAFailure() throws Exception {
        serveSearch("{\"response\":{\"numFound\":0,\"docs\":[]}}");

        assertEquals(1, add().execute(List.of("nothing-here")));
    }

    private void serveMetadata(String xml) throws IOException {
        byte[] body = xml.getBytes();
        central = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        central.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        central.start();
        System.setProperty("jeno.central.base",
                "http://localhost:" + central.getAddress().getPort() + "/");
    }

    private void serveSearch(String json) throws IOException {
        byte[] body = json.getBytes();
        central = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        central.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        central.start();
        System.setProperty("jeno.search.host",
                "http://localhost:" + central.getAddress().getPort());
    }
}
