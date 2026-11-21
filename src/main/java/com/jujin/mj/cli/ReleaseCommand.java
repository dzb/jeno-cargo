package com.jujin.mj.cli;

import com.jujin.freeway.flow.FlowContext;
import com.jujin.freeway.flow.FlowEngine;
import com.jujin.freeway.flow.FlowException;
import com.jujin.freeway.flow.Graph;
import com.jujin.freeway.flow.Node;
import com.jujin.freeway.flow.TaskHandler;
import com.jujin.mj.project.MjProject;
import com.jujin.mj.project.ProjectReader;
import com.jujin.mj.store.ArtifactStore;
import com.jujin.mj.store.LockFileReader;
import com.jujin.mj.store.LockedArtifact;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code mj release} — the whole lifecycle as one freeway-flow graph
 * (DESIGN.md §7.5):
 *
 * <pre>
 *   lock → build → verify → test → dist → publish
 * </pre>
 *
 * <p>Each stage is a task node resolved against the container
 * ({@code @release-lock} …); the gates are node-level {@code when}
 * conditions over the run's context — skipping a stage is a data decision
 * in the graph, not an if-chain in code:
 *
 * <ul>
 *   <li>{@code verify} runs only under a CI tag ({@code ci.tag != ''}) —
 *       it asserts tag == mj.toml version == the freshly compiled binary's
 *       {@code --version}</li>
 *   <li>{@code test} runs unless {@code --skip-test}</li>
 *   <li>{@code publish} runs only with {@code --publish gh:owner/repo}</li>
 * </ul>
 *
 * <p>Fail-fast: a failed stage throws {@link FlowException} and the
 * evaluation stops — dist is never assembled from untested code.
 */
public final class ReleaseCommand implements Command {

    /** The lifecycle as a flow graph — the gates are node-level conditions. */
    private static final String GRAPH = """
        {
          "id": "release",
          "title": "mj release lifecycle",
          "version": 3,
          "nodes": [
            { "id": "start",   "type": "START" },
            { "id": "lock",    "type": "ACTIVITY", "task": "@release-lock" },
            { "id": "build",   "type": "ACTIVITY", "task": "@release-build" },
            { "id": "verify",  "type": "ACTIVITY", "task": "@release-verify",
              "when": "ciTag != ''" },
            { "id": "test",    "type": "ACTIVITY", "task": "@release-test",
              "when": "runTests == true" },
            { "id": "dist",    "type": "ACTIVITY", "task": "@release-dist" },
            { "id": "publish", "type": "ACTIVITY", "task": "@release-publish",
              "when": "publishTarget != ''" },
            { "id": "end",     "type": "END" }
          ],
          "links": [
            { "from": "start",   "to": "lock" },
            { "from": "lock",    "to": "build" },
            { "from": "build",   "to": "verify" },
            { "from": "verify",  "to": "test" },
            { "from": "test",    "to": "dist" },
            { "from": "dist",    "to": "publish" },
            { "from": "publish", "to": "end" }
          ]
        }
        """;

    private final FlowEngine engine;

    public ReleaseCommand(FlowEngine engine) {
        this.engine = engine;
    }

    @Override
    public String name() {
        return "release";
    }

    @Override
    public String summary() {
        return "run the whole lifecycle as one flow — lock, build, verify, test, dist, publish";
    }

    @Override
    public int execute(List<String> args) {
        boolean runTests = true;
        String publishTarget = "";
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            switch (arg) {
                case "--skip-test" -> runTests = false;
                case "--publish" -> {
                    if (i + 1 >= args.size()) {
                        System.err.println("mj: --publish needs a target "
                                + "(gh:owner/repo or gitee:owner/repo)");
                        return 2;
                    }
                    publishTarget = args.get(++i);
                }
                default -> {
                    System.err.println("mj: unknown release option '" + arg + "'");
                    return 2;
                }
            }
        }

        FlowContext context = FlowContext.of()
                .put("runTests", runTests)
                .put("ciTag", ciTag())
                .put("publishTarget", publishTarget);

        try {
            engine.run(Graph.fromText(GRAPH), context);
            return 0;
        } catch (FlowException e) {
            System.err.println("mj: release failed: " + e.getMessage());
            return 1;
        }
    }

    /**
     * The CI tag — only when the push is a tag push. On branch pushes
     * GITHUB_REF_NAME names the branch, which is not a version claim; locally
     * both are absent. Empty means: the verify gate stays closed.
     */
    private static String ciTag() {
        // the test seam: CI runs these tests ON a tag push, where the ambient
        // GITHUB_REF_* variables would open the verify gate for a fixture
        // project that has nothing to do with mj's own version
        if ("false".equals(System.getProperty("mj.release.ci"))) {
            return "";
        }
        if (!"tag".equals(System.getenv("GITHUB_REF_TYPE"))) {
            return "";
        }
        String tag = System.getenv("GITHUB_REF_NAME");
        return tag == null ? "" : tag;
    }

    // ------------------------------------------------------------------
    // the lifecycle stages — container-resolved task handlers
    // ------------------------------------------------------------------

    /** lock — resolve and pin the closure into mj.lock. */
    static final class Lock implements TaskHandler {
        private final LockService lock;
        private final Path projectDir;

        Lock(LockService lock, Path projectDir) {
            this.lock = lock;
            this.projectDir = projectDir;
        }

        @Override
        public void run(FlowContext context, Node node) throws Throwable {
            int exit = new LockCommand(lock, projectDir).execute(List.of());
            if (exit != 0) {
                throw new FlowException("lock stage failed (exit " + exit + ")");
            }
        }
    }

    /** build — strict-visibility compile into target/classes. */
    static final class Build implements TaskHandler {
        private final ArtifactStore store;
        private final Path projectDir;

        Build(ArtifactStore store, Path projectDir) {
            this.store = store;
            this.projectDir = projectDir;
        }

        @Override
        public void run(FlowContext context, Node node) throws Throwable {
            int exit = new BuildCommand(store, projectDir).execute(List.of());
            if (exit != 0) {
                throw new FlowException("build stage failed (exit " + exit + ")");
            }
        }
    }

    /**
     * verify — the CI version gate: tag == mj.toml == the freshly compiled
     * binary. Runs only when a tag push seeded {@code ci.tag}.
     */
    static final class Verify implements TaskHandler {
        private final ArtifactStore store;
        private final Path projectDir;

        Verify(ArtifactStore store, Path projectDir) {
            this.store = store;
            this.projectDir = projectDir;
        }

        @Override
        public void run(FlowContext context, Node node) throws Throwable {
            String tag = context.getAs("ciTag");
            String claimed = tag.startsWith("v") ? tag.substring(1) : tag;
            MjProject project = ProjectReader.parse(
                    Files.readString(projectDir.resolve("mj.toml")));
            if (!project.version().equals(claimed)) {
                throw new FlowException("tag " + tag + " does not match mj.toml "
                        + "[project].version " + project.version());
            }
            String built = forkedVersion();
            if (!built.equals(project.version())) {
                throw new FlowException("the compiled Mj.VERSION (" + built
                        + ") does not match mj.toml (" + project.version()
                        + ") — bump both or neither");
            }
            System.out.println("release: tag, mj.toml and the built binary agree on "
                    + project.version());
        }

        /** Runs the freshly compiled Mj --version in a forked JVM. */
        private String forkedVersion() throws Exception {
            List<String> entries = new ArrayList<>();
            entries.add(projectDir.resolve("target/classes").toString());
            for (LockedArtifact artifact : LockFileReader.read(
                    Files.readString(projectDir.resolve("mj.lock")))) {
                Path jar = store.findByDigest(artifact.sha256(), ArtifactStore.Kind.JAR);
                if (jar != null) {
                    entries.add(jar.toString());
                }
            }
            Process process = new ProcessBuilder("java", "-cp",
                    String.join(File.pathSeparator, entries),
                    "com.jujin.mj.cli.Mj", "--version")
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            process.waitFor();
            // "mj 0.2.0 — modern java manager"
            for (String token : output.split(" ")) {
                if (token.matches("\\d+\\.\\d+\\.\\d+.*")) {
                    return token.replaceAll("-.*$", "");
                }
            }
            throw new FlowException("cannot read the built binary's version: "
                    + output.strip());
        }
    }

    /** test — forked-JVM test run (@MjTest + Jupiter). */
    static final class Test implements TaskHandler {
        private final ArtifactStore store;
        private final Path projectDir;

        Test(ArtifactStore store, Path projectDir) {
            this.store = store;
            this.projectDir = projectDir;
        }

        @Override
        public void run(FlowContext context, Node node) throws Throwable {
            int exit = new TestCommand(store, projectDir).execute(List.of());
            if (exit != 0) {
                throw new FlowException("test stage failed (exit " + exit + ")");
            }
        }
    }

    /** dist — assemble the distribution directory and its tar.gz. */
    static final class Dist implements TaskHandler {
        private final ArtifactStore store;
        private final Path projectDir;

        Dist(ArtifactStore store, Path projectDir) {
            this.store = store;
            this.projectDir = projectDir;
        }

        @Override
        public void run(FlowContext context, Node node) throws Throwable {
            int exit = new DistCommand(store, projectDir).execute(List.of());
            if (exit != 0) {
                throw new FlowException("dist stage failed (exit " + exit + ")");
            }
        }
    }

    /** publish — the library release triple, uploaded to gh:/gitee:. */
    static final class Publish implements TaskHandler {
        private final Path projectDir;

        Publish(Path projectDir) {
            this.projectDir = projectDir;
        }

        @Override
        public void run(FlowContext context, Node node) throws Throwable {
            String target = context.getAs("publishTarget");
            int exit = new PublishCommand(projectDir).execute(List.of(target));
            if (exit != 0) {
                throw new FlowException("publish stage failed (exit " + exit + ")");
            }
        }
    }
}
