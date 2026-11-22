package com.jujin.mjm.cli;

import com.jujin.mjm.project.MjProject;
import com.jujin.mjm.project.ProjectException;
import com.jujin.mjm.project.ProjectReader;
import com.jujin.mjm.project.Repository;
import com.jujin.mjm.store.ArtifactStore;
import com.jujin.mjm.store.StoreDescriptorSource;
import com.jujin.mjm.store.StorePomSource;
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
        com.jujin.mjm.store.Paths.useForTesting(root.resolve(".cache/store"),
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
        com.jujin.mjm.store.Paths.resetForTesting();
        com.jujin.mjm.resolve.ArtifactLocations.useProjectRoot(Path.of("").toAbsolutePath());
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

        // mjm + directory: a descriptor on disk
        Path mjmVendor = root.resolve("mjm-vendor");
        mjmArtifact(mjmVendor, "gadget", "0.9.0");
        Path mjmProject = project("mjm-proj", """
            [repos.v]
            type = "mjm"
            url = "../mjm-vendor"

            [deps]
            "gadget" = "v:0.9.0"
            """);
        useRoot(mjmProject);
        locks.refresh(mjmProject);
        assertEquals("mjm:local:../mjm-vendor", LockFileFirst.source(mjmProject));

        // https forms are pure parsing: both layouts stay distinguishable
        MjProject parsed = ProjectReader.parse("""
            [project]
            name = "x"
            version = "1.0.0"

            [repos.mavenish]
            type = "maven"
            url = "https://a.example/"

            [repos.mjmish]
            type = "mjm"
            url = "https://b.example/"
            """);
        assertEquals(Repository.Layout.MAVEN, parsed.repos().get("mavenish").layout());
        assertEquals(Repository.Transport.HTTPS, parsed.repos().get("mavenish").transport());
        assertEquals(Repository.Layout.MJM, parsed.repos().get("mjmish").layout());
        assertEquals("mjm:https://b.example/", parsed.repos().get("mjmish").declaration());
    }

    @Test
    void aTypedHttpsMjmRepositoryLocksThroughTheDescriptor() throws Exception {
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
                case "/widget/1.0.0/widget-1.0.0.mjm.toml" ->
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
            type = "mjm"
            url = "%s"

            [deps]
            "widget" = "mine:1.0.0"
            """.formatted(base));
        useRoot(project);

        locks.refresh(project);

        assertEquals("mjm:" + base, LockFileFirst.source(project),
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

        assertTrue(error.getMessage().contains("maven") && error.getMessage().contains("mjm"),
                error.getMessage());
        assertTrue(error.getMessage().contains("mjm.toml:6:1"), error.getMessage());
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

        assertTrue(error.getMessage().contains("mjm.toml:6:1"), error.getMessage());
        assertTrue(error.getMessage().contains("allowed: type, url"), error.getMessage());
    }

    @Test
    void aMissingUrlCarriesTheSectionPosition() throws Exception {
        ProjectException error = assertThrows(ProjectException.class, () ->
                ProjectReader.parse("""
                    [project]
                    name = "x"
                    version = "1.0.0"

                    [repos.x]
                    type = "mjm"
                    """));

        assertTrue(error.getMessage().contains("mjm.toml:5:1"), error.getMessage());
        assertTrue(error.getMessage().contains("needs a url"), error.getMessage());
    }

    @Test
    void theLegacyStringFormsStillParse() {
        MjProject parsed = ProjectReader.parse("""
            [project]
            name = "x"
            version = "1.0.0"

            [repos]
            plain = "https://host/"
            maven-local = "local:../vendor"
            native = "mjm:https://host/mine/"
            native-local = "mjm:local:../vendor"
            """);

        assertEquals(Repository.Layout.MAVEN, parsed.repos().get("plain").layout());
        assertEquals(Repository.Transport.HTTPS, parsed.repos().get("plain").transport());
        assertEquals(Repository.Layout.MAVEN, parsed.repos().get("maven-local").layout());
        assertEquals(Repository.Transport.LOCAL, parsed.repos().get("maven-local").transport());
        assertEquals(Repository.Layout.MJM, parsed.repos().get("native").layout());
        assertEquals("mjm:https://host/mine/", parsed.repos().get("native").declaration());
        assertEquals(Repository.Layout.MJM, parsed.repos().get("native-local").layout());
        assertEquals("mjm:local:../vendor", parsed.repos().get("native-local").declaration());
    }

    @Test
    void theNewFormAndTheEquivalentLegacyFormWriteTheSameLock() throws Exception {
        mjmArtifact(root.resolve("vendor"), "gadget", "0.9.0");

        Path newProject = project("new", """
            [repos.v]
            type = "mjm"
            url = "../vendor"

            [deps]
            "gadget" = "v:0.9.0"
            """);
        Path oldProject = project("old", """
            [repos]
            v = "mjm:local:../vendor"

            [deps]
            "gadget" = "v:0.9.0"
            """);
        useRoot(newProject);
        locks.refresh(newProject);
        useRoot(oldProject);
        locks.refresh(oldProject);

        assertEquals(Files.readString(oldProject.resolve("mjm.lock")),
                Files.readString(newProject.resolve("mjm.lock")),
                "the new spelling is a source form; the lock bytes are the same");
    }

    // ------------------------------------------------------------------

    /** Reads the single lock row's source without pulling in cli test helpers. */
    private static final class LockFileFirst {
        static String source(Path project) throws IOException {
            List<com.jujin.mjm.store.LockedArtifact> rows =
                    com.jujin.mjm.store.LockFileReader.read(
                            Files.readString(project.resolve("mjm.lock")));
            return rows.getFirst().source();
        }
    }

    private void useRoot(Path project) {
        com.jujin.mjm.resolve.ArtifactLocations.useProjectRoot(project);
    }

    private Path project(String name, String body) throws IOException {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mjm.toml"), """
            [project]
            name = "%s"
            version = "0.1.0"
            %s
            """.formatted(name, "\n" + body));
        return dir;
    }

    private static void mjmArtifact(Path repository, String name, String version)
            throws IOException {
        Path dir = repository.resolve(name).resolve(version);
        Files.createDirectories(dir);
        Path jar = dir.resolve(name + "-" + version + ".jar");
        Files.write(jar, jarBytes(name));
        Files.writeString(dir.resolve(name + "-" + version + ".mjm.toml"), """
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
