package com.jujin.jeno.cli;

import com.jujin.jeno.project.JenoProject;
import com.jujin.jeno.project.ProjectException;
import com.jujin.jeno.project.ProjectReader;
import com.jujin.jeno.project.Repository;
import com.jujin.jeno.store.ArtifactStore;
import com.jujin.jeno.store.StoreDescriptorSource;
import com.jujin.jeno.store.StorePomSource;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code [repos.<name>] type/url} — the protocol is a declared fact, not
 * something read out of an https prefix.
 */
class RepositoryDeclarationTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private LockService locks;
    private HttpServer server;

    @BeforeEach
    void setUp() {
        ProjectLock.useRoot(root.resolve(".locks"));
        com.jujin.jeno.store.Paths.useForTesting(root.resolve(".cache/store"),
                root.resolve(".cache/sources"), root.resolve(".locks"));
        store = new ArtifactStore(root.resolve(".cache/store"));
        locks = new LockService(store, new StorePomSource(store), new StoreDescriptorSource(store));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        ProjectLock.useUserRoot();
        com.jujin.jeno.store.Paths.resetForTesting();
        com.jujin.jeno.resolve.ArtifactLocations.useProjectRoot(Path.of("").toAbsolutePath());
    }

    @Test
    void theFourTypeLocationCombinationsParseAndResolve() throws Exception {
        // maven + directory: a local Maven layout, resolved through its pom
        Path mavenVendor = root.resolve("maven-vendor");
        mavenArtifact(mavenVendor, "org.example", "lib", "1.0.0");
        Path mavenProject = project("maven-proj", """
            [repos.v]
            type = "maven"
            url = "../maven-vendor"

            [deps]
            "org.example:lib" = "v:1.0.0"
            """);
        useRoot(mavenProject);
        locks.refresh(mavenProject);
        assertEquals("local:../maven-vendor",
                LockFileFirst.source(mavenProject));

        // jeno + directory: a descriptor on disk
        Path jenoVendor = root.resolve("jeno-vendor");
        jenoArtifact(jenoVendor, "gadget", "0.9.0");
        Path jenoProject = project("jeno-proj", """
            [repos.v]
            type = "jeno"
            url = "../jeno-vendor"

            [deps]
            "gadget" = "v:0.9.0"
            """);
        useRoot(jenoProject);
        locks.refresh(jenoProject);
        assertEquals("jeno:local:../jeno-vendor", LockFileFirst.source(jenoProject));

        // https forms are pure parsing: both layouts stay distinguishable
        JenoProject parsed = ProjectReader.parse("""
            [project]
            name = "x"
            version = "1.0.0"

            [repos.mavenish]
            type = "maven"
            url = "https://a.example/"

            [repos.jenoish]
            type = "jeno"
            url = "https://b.example/"
            """);
        assertEquals(Repository.Layout.MAVEN, parsed.repos().get("mavenish").layout());
        assertEquals(Repository.Transport.NETWORK, parsed.repos().get("mavenish").transport());
        assertEquals(Repository.Layout.JENO, parsed.repos().get("jenoish").layout());
        assertEquals("jeno:https://b.example/", parsed.repos().get("jenoish").declaration());
    }

    @Test
    void aTypedHttpsJenoRepositoryLocksThroughTheDescriptor() throws Exception {
        String descriptor = """
            [project]
            name = "widget"
            version = "1.0.0"
            """;
        byte[] jar = jarBytes("widget");
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body = switch (path) {
                case "/widget/1.0.0/widget-1.0.0.jeno.toml" ->
                        descriptor.getBytes(StandardCharsets.UTF_8);
                case "/widget/1.0.0/widget-1.0.0.jar" -> jar;
                default -> null;
            };
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        String base = "http://localhost:" + server.getAddress().getPort();
        Path project = project("typed", """
            [repos.mine]
            type = "jeno"
            url = "%s"

            [deps]
            "widget" = "mine:1.0.0"
            """.formatted(base));
        useRoot(project);

        locks.refresh(project);

        assertEquals("jeno:" + base + "/", LockFileFirst.source(project),
                "the canonical declaration records the layout explicitly");
    }

    @Test
    void anUnknownTypeListsTheLegalOnes() throws Exception {
        ProjectException error = assertThrows(ProjectException.class, () ->
                ProjectReader.parse("""
                    [project]
                    name = "x"
                    version = "1.0.0"

                    [repos.x]
                    type = "git"
                    url = "https://host/"
                    """));

        assertTrue(error.getMessage().contains("maven") && error.getMessage().contains("jeno"),
                error.getMessage());
        assertTrue(error.getMessage().contains("jeno.toml:6:1"), error.getMessage());
    }

    @Test
    void anUnknownKeyCarriesItsPosition() throws Exception {
        ProjectException error = assertThrows(ProjectException.class, () ->
                ProjectReader.parse("""
                    [project]
                    name = "x"
                    version = "1.0.0"

                    [repos.x]
                    urls = "https://host/"
                    """));

        assertTrue(error.getMessage().contains("jeno.toml:6:1"), error.getMessage());
        assertTrue(error.getMessage().contains("allowed: type, url, insecure"),
                error.getMessage());
    }

    @Test
    void insecureTokenOptInIsRepositoryLocalAndOnlyForPrivateJenoHttp() {
        JenoProject parsed = ProjectReader.parse("""
            [project]
            name = "x"
            version = "1.0.0"

            [repos.mine]
            type = "jeno"
            url = "http://192.168.1.39:8080/repo/"
            insecure = true
            """);

        Repository mine = parsed.repos().get("mine");
        assertTrue(mine.insecure());
        assertEquals("jeno:http://192.168.1.39:8080/repo/", mine.declaration());

        ProjectException maven = assertThrows(ProjectException.class, () ->
                ProjectReader.parse("""
                    [project]
                    name = "x"
                    version = "1.0.0"

                    [repos.mine]
                    type = "maven"
                    url = "http://192.168.1.39:8080/repo/"
                    insecure = true
                    """));
        assertTrue(maven.getMessage().contains("only for type = \"jeno\""),
                maven.getMessage());

        ProjectException https = assertThrows(ProjectException.class, () ->
                ProjectReader.parse("""
                    [project]
                    name = "x"
                    version = "1.0.0"

                    [repos.mine]
                    type = "jeno"
                    url = "https://host/repo/"
                    insecure = true
                    """));
        assertTrue(https.getMessage().contains("private-network http://"),
                https.getMessage());

        ProjectException type = assertThrows(ProjectException.class, () ->
                ProjectReader.parse("""
                    [project]
                    name = "x"
                    version = "1.0.0"

                    [repos.mine]
                    type = "jeno"
                    url = "http://192.168.1.39:8080/repo/"
                    insecure = "yes"
                    """));
        assertTrue(type.getMessage().contains("must be true or false"),
                type.getMessage());
    }

    @Test
    void aDeclaredRepositoryNameCanBeAWriteTargetAndKeepsItsPolicy() {
        JenoProject parsed = ProjectReader.parse("""
            [project]
            name = "x"
            version = "1.0.0"

            [repos.mine]
            type = "jeno"
            url = "http://192.168.1.39:8080/repo/"
            insecure = true
            """);

        Repository named = Repository.resolveJenoTarget(parsed, "mine");
        assertTrue(named.insecure());
        assertEquals("jeno:http://192.168.1.39:8080/repo/", named.declaration());

        Repository url = Repository.resolveJenoTarget(parsed, "https://host/repo/");
        assertEquals("jeno:https://host/repo/", url.declaration());
    }

    @Test
    void aMissingUrlCarriesTheSectionPosition() throws Exception {
        ProjectException error = assertThrows(ProjectException.class, () ->
                ProjectReader.parse("""
                    [project]
                    name = "x"
                    version = "1.0.0"

                    [repos.x]
                    type = "jeno"
                    """));

        assertTrue(error.getMessage().contains("jeno.toml:5:1"), error.getMessage());
        assertTrue(error.getMessage().contains("needs a url"), error.getMessage());
    }

    @Test
    void legacyStringFormsAreRejectedWithStructuredMigration() {
        ProjectException error = assertThrows(ProjectException.class, () ->
                ProjectReader.parse("""
                    [project]
                    name = "x"
                    version = "1.0.0"

                    [repos]
                    plain = "https://host/"
                    native = "jeno:local:../vendor"
                    """));

        assertTrue(error.getMessage().contains(
                        "string repository declarations were removed"), error.getMessage());
        assertTrue(error.getMessage().contains("[repos.plain]"), error.getMessage());
        assertTrue(error.getMessage().contains("type = \"maven\""), error.getMessage());
        assertTrue(error.getMessage().contains("url"), error.getMessage());
    }

    @Test
    void explicitJenoTypeAndTheDefaultWriteTheSameLock() throws Exception {
        jenoArtifact(root.resolve("vendor"), "gadget", "0.9.0");

        Path newProject = project("new", """
            [repos.v]
            type = "jeno"
            url = "../vendor"

            [deps]
            "gadget" = "v:0.9.0"
            """);
        Path oldProject = project("old", """
            [repos.v]
            url = "../vendor"

            [deps]
            "gadget" = "v:0.9.0"
            """);
        useRoot(newProject);
        locks.refresh(newProject);
        useRoot(oldProject);
        locks.refresh(oldProject);

        assertEquals(Files.readString(oldProject.resolve("jeno.lock")),
                Files.readString(newProject.resolve("jeno.lock")),
                "explicit type = jeno and the omitted default must produce identical locks");
    }

    // ------------------------------------------------------------------

    /** Reads the single lock row's source without pulling in cli test helpers. */
    private static final class LockFileFirst {
        static String source(Path project) throws IOException {
            List<com.jujin.jeno.store.LockedArtifact> rows =
                    com.jujin.jeno.store.LockFileReader.read(
                            Files.readString(project.resolve("jeno.lock")));
            return rows.getFirst().source();
        }
    }

    private void useRoot(Path project) {
        com.jujin.jeno.resolve.ArtifactLocations.useProjectRoot(project);
    }

    private Path project(String name, String body) throws IOException {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("jeno.toml"), """
            [project]
            name = "%s"
            version = "0.1.0"
            %s
            """.formatted(name, "\n" + body));
        return dir;
    }

    private static void jenoArtifact(Path repository, String name, String version)
            throws IOException {
        Path dir = repository.resolve(name).resolve(version);
        Files.createDirectories(dir);
        Path jar = dir.resolve(name + "-" + version + ".jar");
        Files.write(jar, jarBytes(name));
        Files.writeString(dir.resolve(name + "-" + version + ".jeno.toml"), """
            [project]
            name = "%s"
            version = "%s"
            """.formatted(name, version));
        Files.writeString(dir.resolve(name + "-" + version + ".sha256"),
                ArtifactStore.sha256(jar) + "\n");
    }

    private static void mavenArtifact(Path repository, String group, String name, String version)
            throws IOException {
        Path dir = repository.resolve(group.replace('.', '/')).resolve(name).resolve(version);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + "-" + version + ".pom"), """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>%s</groupId>
              <artifactId>%s</artifactId>
              <version>%s</version>
            </project>
            """.formatted(group, name, version));
        Files.write(dir.resolve(name + "-" + version + ".jar"), jarBytes(name));
    }

    private static byte[] jarBytes(String marker) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("marker.txt"));
            zip.write(marker.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
