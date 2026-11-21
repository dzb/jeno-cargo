package com.jujin.mj.cli;

import com.jujin.freeway.flow.FlowEngine;
import com.jujin.freeway.flow.FlowModule;
import com.jujin.freeway.flow.TaskHandler;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.mj.pom.PomSource;
import com.jujin.mj.project.DescriptorSource;
import com.jujin.mj.resolve.ResolvedGraph;
import com.jujin.mj.resolve.LockFile;
import com.jujin.mj.store.ArtifactStore;
import com.jujin.mj.store.StoreDescriptorSource;
import com.jujin.mj.store.StorePomSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code mj release} — the lifecycle as one freeway-flow graph. The wiring is
 * real: FlowModule assembles the engine, the stages resolve by id through the
 * container, and the gates are node-level conditions.
 */
class ReleaseFlowTest {

    @TempDir
    Path dir;

    @Test
    void releaseRunsTheWholeLifecycle(@TempDir Path storeDir) throws Exception {
        project(true);

        int exit;
        try (var central = VendoredToolchain.start()) {
            exit = release(storeDir).execute(List.of());
        }

        assertEquals(0, exit, "the whole lifecycle exits zero");
        assertTrue(Files.exists(dir.resolve("target/test-classes")), "the test stage ran");
        assertTrue(Files.exists(dir.resolve("dist/demo-0.1.0.tar.gz")), "the dist stage ran");
    }

    @Test
    void skipTestLeavesTestClassesUnbuilt(@TempDir Path storeDir) throws Exception {
        project(true);

        int exit = release(storeDir).execute(List.of("--skip-test"));

        assertEquals(0, exit, "release without tests exits zero");
        assertFalse(Files.exists(dir.resolve("target/test-classes")),
                "the test gate stayed closed");
        assertTrue(Files.exists(dir.resolve("dist/demo-0.1.0.tar.gz")),
                "the dist stage still ran");
    }

    @Test
    void aFailedTestFailsFastBeforeDist(@TempDir Path storeDir) throws Exception {
        project(false);

        int exit;
        try (var central = VendoredToolchain.start()) {
            exit = release(storeDir).execute(List.of());
        }

        assertEquals(1, exit, "a failing test fails the release");
        assertFalse(Files.exists(dir.resolve("dist/demo-0.1.0.tar.gz")),
                "dist is never assembled from untested code");
    }

    @Test
    void unknownOptionIsRejected(@TempDir Path storeDir) {
        assertEquals(2, release(storeDir).execute(List.of("--bogus")));
    }

    /**
     * The real assembly, scoped to the test: FlowModule builds the engine,
     * the lifecycle stages bind by id, the store points at a temp dir.
     */
    private ReleaseCommand release(Path storeDir) {
        // hermetic against the CI runner's own tag: the verify gate must not
        // open for a fixture project when the *build* is a tag push
        System.setProperty("mj.release.ci", "false");
        Container container = Freeway.create(new FlowModule(), binder -> {
            ArtifactStore store = new ArtifactStore(storeDir);
            binder.bind(ArtifactStore.class).to(c -> store);
            binder.bind(PomSource.class).to(StorePomSource.class);
            binder.bind(DescriptorSource.class).to(StoreDescriptorSource.class);
            binder.bind(LockService.class).to(LockService.class);
            binder.bind(TaskHandler.class)
                    .to(c -> new ReleaseCommand.Lock(c.get(LockService.class), dir))
                    .id("release-lock");
            binder.bind(TaskHandler.class)
                    .to(c -> new ReleaseCommand.Build(c.get(ArtifactStore.class), dir))
                    .id("release-build");
            binder.bind(TaskHandler.class)
                    .to(c -> new ReleaseCommand.Verify(c.get(ArtifactStore.class), dir))
                    .id("release-verify");
            binder.bind(TaskHandler.class)
                    .to(c -> new ReleaseCommand.Test(c.get(ArtifactStore.class), dir))
                    .id("release-test");
            binder.bind(TaskHandler.class)
                    .to(c -> new ReleaseCommand.Dist(c.get(ArtifactStore.class), dir))
                    .id("release-dist");
            binder.bind(TaskHandler.class)
                    .to(c -> new ReleaseCommand.Publish(dir))
                    .id("release-publish");
        });
        return new ReleaseCommand(container.get(FlowEngine.class));
    }

    /**
     * A minimal releaseable project: one main class, and either a passing or
     * a failing @MjTest suite ({@code passing} picks which).
     */
    private void project(boolean passing) throws IOException {
        Files.createDirectories(dir.resolve("src/main/java/demo"));
        Files.createDirectories(dir.resolve("src/test/java/demo"));
        Files.writeString(dir.resolve("mj.toml"), """
            [project]
            name = "demo"
            version = "0.1.0"
            """);
        Files.writeString(dir.resolve("src/main/java/demo/App.java"), """
            package demo;

            public final class App {
                public static void main(String[] args) {
                    System.out.println("demo");
                }
            }
            """);
        Files.writeString(dir.resolve("src/test/java/demo/Tests.java"), """
            package demo;

            import com.jujin.mj.test.MjTest;
            import com.jujin.mj.test.MjAssert;

            public class Tests {
                @MjTest
                void one() {
                    MjAssert.equals(%s, %s);
                }
            }
            """.formatted(passing ? "\"a\"" : "1", passing ? "\"a\"" : "2"));
    }
}
