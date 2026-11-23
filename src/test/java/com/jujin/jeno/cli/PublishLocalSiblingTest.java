package com.jujin.jeno.cli;

import com.jujin.jeno.store.ArtifactStore;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Publishing into a local jeno repository: a dependency declared from that same
 * directory is a sibling, whether the target is spelled relatively or
 * absolutely — both name one real directory.
 */
class PublishLocalSiblingTest {

    @TempDir
    Path root;

    private ArtifactStore store;

    @BeforeEach
    void setUp() {
        ProjectLock.useRoot(root.resolve(".locks"));
        com.jujin.jeno.store.Paths.useForTesting(root.resolve(".cache/store"),
                root.resolve(".cache/sources"), root.resolve(".locks"));
        store = new ArtifactStore(root.resolve(".cache/store"));
    }

    @AfterEach
    void tearDown() {
        ProjectLock.useUserRoot();
        com.jujin.jeno.store.Paths.resetForTesting();
        com.jujin.jeno.resolve.ArtifactLocations.useProjectRoot(Path.of("").toAbsolutePath());
    }

    @Test
    void aRelativeTargetPublishesTheSiblingAsABareName() throws Exception {
        Path vendor = root.resolve("vendor");
        publishArtifact(vendor, "gadget", "0.9.0");
        Path project = producer();

        Result result = publish(project, "jeno:local:../vendor");

        assertEquals(0, result.exit(), result.out() + result.err());
        String descriptor = Files.readString(
                vendor.resolve("widget/0.1.0/widget-0.1.0.jeno.toml"));
        assertTrue(descriptor.contains("\"gadget\" = \"0.9.0\""),
                "the sibling travels as a bare name: " + descriptor);
    }

    @Test
    void anAbsoluteTargetNamesTheSameDirectory() throws Exception {
        Path vendor = root.resolve("vendor");
        publishArtifact(vendor, "gadget", "0.9.0");
        Path project = producer();

        Result result = publish(project, "jeno:local:" + vendor);

        assertEquals(0, result.exit(), result.out() + result.err());
        assertTrue(Files.exists(vendor.resolve("widget/0.1.0/widget-0.1.0.jar")));
    }

    @Test
    void aDependencyFromAnotherLocalRepositoryIsStillRefused() throws Exception {
        Path vendor = root.resolve("vendor");
        Path other = root.resolve("other-vendor");
        publishArtifact(vendor, "gadget", "0.9.0");
        publishArtifact(other, "gadget", "0.9.0");
        Path project = producer();

        Result result = publish(project, "jeno:local:" + other);

        assertEquals(1, result.exit());
        assertTrue(result.err().contains("belongs to repository 'cascade'"), result.err());
        assertTrue(result.err().contains("vendor"), result.err());
        assertTrue(result.err().contains("other-vendor"), result.err());
        assertFalse(Files.exists(other.resolve("widget")), "nothing was written");
    }

    @Test
    void httpsSiblingComparisonIsUnchanged() {
        var repository = com.jujin.jeno.project.Repository.parse("r", "jeno:https://HOST/repo");
        assertEquals(
                com.jujin.jeno.resolve.ArtifactLocations.canonicalLocation(repository),
                com.jujin.jeno.resolve.ArtifactLocations.canonicalLocation(
                        com.jujin.jeno.project.Repository.parse("r2", "jeno:https://host:443/repo/")),
                "host case, default port and the trailing slash are spelling");
    }

    // ------------------------------------------------------------------

    private Path producer() throws Exception {
        Path project = root.resolve("producer");
        Files.createDirectories(project.resolve("src/main/java/widget"));
        Files.writeString(project.resolve("jeno.toml"), """
            [project]
            name = "widget"
            version = "0.1.0"

            [repos]
            cascade = "jeno:local:../vendor"

            [deps]
            "gadget" = "cascade:0.9.0"
            """);
        Files.writeString(project.resolve("src/main/java/widget/Widget.java"),
                "package widget; public final class Widget {}");
        com.jujin.jeno.resolve.ArtifactLocations.useProjectRoot(project);
        LockService locks = new LockService(store, new StorePomSource(store),
                new StoreDescriptorSource(store));
        locks.refresh(project);
        assertEquals(0, new BuildCommand(store, project).execute(List.of()));
        assertEquals(0, new PackageCommand(store, project).execute(List.of()));
        return project;
    }

    private static void publishArtifact(Path repository, String name, String version)
            throws IOException {
        Path dir = repository.resolve(name).resolve(version);
        Files.createDirectories(dir);
        Path jar = dir.resolve(name + "-" + version + ".jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("marker.txt"));
            zip.write(name.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Files.writeString(dir.resolve(name + "-" + version + ".jeno.toml"), """
            [project]
            name = "%s"
            version = "%s"
            """.formatted(name, version));
        Files.writeString(dir.resolve(name + "-" + version + ".sha256"),
                ArtifactStore.sha256(jar) + "\n");
    }

    private static Result publish(Path project, String target) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            exit = new PublishCommand(project).execute(List.of(target));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new Result(exit, out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
    }

    private record Result(int exit, String out, String err) {}
}
