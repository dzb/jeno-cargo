package com.jujin.jeno.cli;

import com.jujin.jeno.project.ProjectException;
import com.jujin.jeno.project.ProjectReader;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A local directory with the default jeno layout: two levels, no group, no
 * Maven pom. The lock keeps the source and relocatable {@code file:} URLs.
 */
class JenoLocalRepositoryTest {

    @TempDir
    Path root;

    private ArtifactStore store;
    private LockService locks;

    @BeforeEach
    void setUp() {
        ProjectLock.useRoot(root.resolve(".locks"));
        com.jujin.jeno.store.Paths.useForTesting(root.resolve(".cache/store"),
                root.resolve(".cache/sources"), root.resolve(".locks"));
        com.jujin.jeno.resolve.ArtifactLocations.useProjectRoot(root);
        store = new ArtifactStore(root.resolve(".cache/store"));
        locks = new LockService(store, new StorePomSource(store), new StoreDescriptorSource(store));
    }

    @AfterEach
    void tearDown() {
        ProjectLock.useUserRoot();
        com.jujin.jeno.store.Paths.resetForTesting();
        com.jujin.jeno.resolve.ArtifactLocations.useProjectRoot(Path.of("").toAbsolutePath());
    }

    @Test
    void aRelativeLocalJenoRepositoryResolvesThroughTheTwoLevelLayout() throws Exception {
        Path vendor = root.resolve("vendor");
        publishArtifact(vendor, "lib", "1.0.0", "lib-bytes");
        Path project = project("proj", """
            [repos.cascade]
            url = "../vendor"

            [deps]
            "lib" = "cascade:1.0.0"
            """);
        com.jujin.jeno.resolve.ArtifactLocations.useProjectRoot(project);

        locks.refresh(project);

        LockedArtifact lib = only(project);
        assertEquals("jeno:local:../vendor", lib.source(),
                "LockedArtifact.source is the internal canonical spelling");
        assertTrue(Files.readString(project.resolve("jeno.lock"))
                        .contains("source = \"local:../vendor\""),
                "the lock file itself uses the v3 default-jeno spelling");
        assertTrue(lib.url().contains("/vendor/lib/1.0.0/lib-1.0.0.jar"), lib.url());
        assertTrue(Files.exists(store.findByDigest(lib.sha256(), ArtifactStore.Kind.JAR)),
                "the jar was read from the directory, not the network");
    }

    @Test
    void aBareSiblingInTheDescriptorResolvesInTheSameRepository() throws Exception {
        Path vendor = root.resolve("vendor");
        publishArtifact(vendor, "sib", "0.5.0", "sib-bytes");
        publishArtifact(vendor, "lib", "1.0.0", "lib-bytes", """
            [deps]
            "sib" = "0.5.0"
            """);
        Path project = project("proj", """
            [repos.cascade]
            url = "../vendor"

            [deps]
            "lib" = "cascade:1.0.0"
            """);
        com.jujin.jeno.resolve.ArtifactLocations.useProjectRoot(project);

        locks.refresh(project);

        List<LockedArtifact> locked = read(project);
        assertEquals(List.of("lib", "sib"), locked.stream().map(LockedArtifact::id).sorted().toList());
        for (LockedArtifact artifact : locked) {
            assertEquals("jeno:local:../vendor", artifact.source());
        }
    }

    @Test
    void publishWritesTheTripleAndAConsumerLocksAndBuilds() throws Exception {
        Path producer = project("widget", "");
        Files.createDirectories(producer.resolve("src/main/java/widget"));
        Files.writeString(producer.resolve("src/main/java/widget/Widget.java"),
                "package widget; public final class Widget {}");
        Files.writeString(producer.resolve("jeno.lock"), "");
        assertEquals(0, new BuildCommand(store, producer).execute(List.of()));
        assertEquals(0, new PackageCommand(store, producer).execute(List.of()));

        Path repository = root.resolve("local-repo");
        String target = "local:" + repository;
        Result published = run(new PublishCommand(producer), target);

        assertEquals(0, published.exit(), published.out() + published.err());
        Path artifactPath = repository.resolve("widget/0.1.0");
        assertTrue(Files.exists(artifactPath.resolve("widget-0.1.0.jar")));
        assertTrue(Files.exists(artifactPath.resolve("widget-0.1.0.jeno.toml")));
        assertEquals(ArtifactStore.sha256(artifactPath.resolve("widget-0.1.0.jar")).strip(),
                Files.readString(artifactPath.resolve("widget-0.1.0.sha256")).strip(),
                "the checksum file matches the jar");

        Path consumer = project("consumer", """
            [repos.mine]
            url = "%s"

            [deps]
            "widget" = "mine:0.1.0"
            """.formatted(repository));
        Files.createDirectories(consumer.resolve("src/main/java/consumer"));
        Files.writeString(consumer.resolve("src/main/java/consumer/App.java"),
                "package consumer; public final class App {}");
        com.jujin.jeno.resolve.ArtifactLocations.useProjectRoot(consumer);
        locks.refresh(consumer);
        assertEquals(0, new BuildCommand(store, consumer).execute(List.of()),
                "a project can consume what jeno publish just wrote");
    }

    @Test
    void malformedStructuredRepositoriesNameTheLegalShape() {
        ProjectException ftp = assertThrows(ProjectException.class, () ->
                ProjectReader.parse("[project]\nname = \"x\"\nversion = \"1.0.0\"\n"
                        + "[repos.bad]\nurl = \"ftp://host/\"\n"));
        assertTrue(ftp.getMessage().contains("url scheme"), ftp.getMessage());

        ProjectException prefixed = assertThrows(ProjectException.class, () ->
                ProjectReader.parse("[project]\nname = \"x\"\nversion = \"1.0.0\"\n"
                        + "[repos.bad]\nurl = \"jeno:../vendor\"\n"));
        assertTrue(prefixed.getMessage().contains("without the jeno:/local: prefix"),
                prefixed.getMessage());
    }

    @Test
    void workspaceModulesResolveTheirOwnRelativeLocalRepository() throws Exception {
        Path a = root.resolve("a");
        Path b = root.resolve("b");
        publishArtifact(a.resolve("vendor"), "lib", "1.0.0", "a-bytes");
        publishArtifact(b.resolve("vendor"), "lib", "2.0.0", "b-bytes");
        Path ma = module(a.resolve("m1"), "m1", """
            [repos.cascade]
            url = "../vendor"

            [deps]
            "lib" = "cascade:1.0.0"
            """);
        Path mb = module(b.resolve("m2"), "m2", """
            [repos.cascade]
            url = "../vendor"

            [deps]
            "lib" = "cascade:2.0.0"
            """);
        Files.writeString(root.resolve("jeno.ws.toml"), """
            [ws]
            modules = ["a/m1", "b/m2"]
            """);

        Result result = run(new LockCommand(locks, root));

        assertEquals(0, result.exit(), result.out() + result.err());
        LockedArtifact fromA = only(ma);
        LockedArtifact fromB = only(mb);
        assertTrue(fromA.url().contains("/a/vendor/lib/1.0.0/"), fromA.url());
        assertTrue(fromB.url().contains("/b/vendor/lib/2.0.0/"), fromB.url());
        assertEquals(Files.readString(a.resolve("vendor/lib/1.0.0/lib-1.0.0.sha256")).strip(),
                fromA.sha256());
        assertEquals(Files.readString(b.resolve("vendor/lib/2.0.0/lib-2.0.0.sha256")).strip(),
                fromB.sha256());
    }

    @Test
    void remoteJenoAndLocalMavenLayoutsRemainDistinct() throws Exception {
        // A structured URL with no type is jeno; a local Maven repository needs
        // an explicit type = "maven".
        ProjectReader.parse("""
            [project]
            name = "x"
            version = "1.0.0"

            [repos.remote]
            url = "https://repo.example/"
            """);

        Path vendor = root.resolve("maven-vendor");
        Path dir = vendor.resolve("org/example/lib/1.0.0");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("lib-1.0.0.pom"), """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>org.example</groupId>
              <artifactId>lib</artifactId>
              <version>1.0.0</version>
            </project>
            """);
        writeJar(dir.resolve("lib-1.0.0.jar"), "maven-bytes");
        Path project = project("maven", """
            [repos.v]
            type = "maven"
            url = "../maven-vendor"

            [deps]
            "org.example:lib" = "v:1.0.0"
            """);
        com.jujin.jeno.resolve.ArtifactLocations.useProjectRoot(project);

        locks.refresh(project);

        assertEquals("local:../maven-vendor", only(project).source());
    }

    // ------------------------------------------------------------------

    private Path project(String name, String body) throws IOException {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("jeno.toml"), """
            [project]
            name = "%s"
            version = "0.1.0"
            %s
            """.formatted(name, body.isBlank() ? "" : "\n" + body));
        return dir;
    }

    private Path module(Path dir, String name, String body) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("jeno.toml"), """
            [project]
            name = "%s"
            version = "0.1.0"
            %s
            """.formatted(name, "\n" + body));
        return dir;
    }

    private static void publishArtifact(Path repository, String name, String version,
                                        String marker) throws IOException {
        publishArtifact(repository, name, version, marker, "");
    }

    private static void publishArtifact(Path repository, String name, String version,
                                        String marker, String extraDescriptor) throws IOException {
        Path dir = repository.resolve(name).resolve(version);
        Files.createDirectories(dir);
        Path jar = dir.resolve(name + "-" + version + ".jar");
        writeJar(jar, marker);
        Files.writeString(dir.resolve(name + "-" + version + ".jeno.toml"), """
            [project]
            name = "%s"
            version = "%s"
            %s
            """.formatted(name, version, extraDescriptor));
        Files.writeString(dir.resolve(name + "-" + version + ".sha256"),
                ArtifactStore.sha256(jar) + "\n");
    }

    private static void writeJar(Path path, String marker) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("marker.txt"));
            zip.write(marker.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private static List<LockedArtifact> read(Path project) throws IOException {
        return LockFileReader.read(Files.readString(project.resolve("jeno.lock")));
    }

    private static LockedArtifact only(Path project) throws IOException {
        return read(project).getFirst();
    }

    private static Result run(Command command, String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            exit = command.execute(List.of(args));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new Result(exit, out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
    }

    private record Result(int exit, String out, String err) {}
}
