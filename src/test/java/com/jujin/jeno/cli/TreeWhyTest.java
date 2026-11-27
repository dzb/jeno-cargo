package com.jujin.jeno.cli;

import com.jujin.jeno.pom.PomSource;
import com.jujin.jeno.project.DescriptorSource;
import com.jujin.jeno.project.ProjectReader;
import com.jujin.jeno.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The budget made visible: jeno tree prints the graph with depths (and the
 * above-marker for repeated subtrees), jeno why prints every introduction
 * path. Both re-resolve — offline here through a local: fixture repository.
 */
class TreeWhyTest {

    @TempDir
    Path dir;

    @Test
    void treeShowsDepthsAndWhyShowsEveryPath() throws Exception {
        // fixture repository: lib-a -> lib-b -> lib-c, plus lib-d -> lib-c
        Path repo = dir.resolve("fix-repo");
        String fixRepo = repo.toString();
        pom(repo, "com/example/lib-a/1.0.0", "lib-a", "1.0.0", """
            <dependencies>
              <dependency>
                <groupId>com.example</groupId>
                <artifactId>lib-b</artifactId>
                <version>1.1.0</version>
              </dependency>
            </dependencies>
            """);
        pom(repo, "com/example/lib-b/1.1.0", "lib-b", "1.1.0", """
            <dependencies>
              <dependency>
                <groupId>com.example</groupId>
                <artifactId>lib-c</artifactId>
                <version>2.0.0</version>
              </dependency>
            </dependencies>
            """);
        pom(repo, "com/example/lib-c/2.0.0", "lib-c", "2.0.0", "");
        pom(repo, "com/example/lib-d/1.0.0", "lib-d", "1.0.0", """
            <dependencies>
              <dependency>
                <groupId>com.example</groupId>
                <artifactId>lib-c</artifactId>
                <version>2.0.0</version>
              </dependency>
            </dependencies>
            """);

        Files.writeString(dir.resolve("jeno.toml"), """
            [project]
            name = "demo"
            version = "0.1.0"

            [limits]
            max-depth = 5

            [repos.fix]
            type = "maven"
            url = "%s"

            [deps]
            "com.example:lib-a" = "fix:1.0.0"
            "com.example:lib-d" = "fix:1.0.0"
            """.formatted(fixRepo));

        writeLock();
        PomSource pomSource = localPomSource();

        TreeCommand tree = new TreeCommand(new ArtifactStore(dir.resolve("store")),
                pomSource, text -> { throw new IllegalStateException("no natives here"); },
                dir);
        int treeExit = tree.execute(List.of());

        assertEquals(0, treeExit);
        String banner = readConsole();

        // The whole block, in order: a root line, its own subtree, then the next
        // root. A child never follows the wrong parent and never reads as a root.
        String expected = """
            demo@0.1.0 (budget 5)
            com.example:lib-a@1.0.0  [depth 1, declared]
            └─ com.example:lib-b@1.1.0  [depth 2]
               └─ com.example:lib-c@2.0.0  [depth 3]
            com.example:lib-d@1.0.0  [depth 1, declared]
            └─ com.example:lib-c@2.0.0  [depth 3]  (*) above
            """;
        assertEquals(expected, banner, "the tree block is exactly this shape");

        assertEquals(1, count(banner, "com.example:lib-a@1.0.0"),
                "a root appears exactly once, got: " + banner);
        assertEquals(1, count(banner, "com.example:lib-d@1.0.0"),
                "a root appears exactly once, got: " + banner);
        assertEquals(3, count(banner, "└─ "), "every child line has a connector");
        assertFalse(banner.contains("      └─ "),
                "the repeated subtree was not walked twice, got: " + banner);

        WhyCommand why = new WhyCommand(new ArtifactStore(dir.resolve("store")),
                pomSource, text -> { throw new IllegalStateException("no natives here"); },
                dir);
        int whyExit = why.execute(List.of("com.example:lib-c"));

        assertEquals(0, whyExit);
        String whyOut = readConsole();
        assertTrue(whyOut.contains("depth 3"), "got: " + whyOut);
        assertTrue(whyOut.contains("com.example:lib-a@1.0.0 -> com.example:lib-b@1.1.0 -> com.example:lib-c@2.0.0"),
                "the through-lib-b path is named, got: " + whyOut);
        assertTrue(whyOut.contains("com.example:lib-d@1.0.0 -> com.example:lib-c@2.0.0"),
                "the through-lib-d path is named too, got: " + whyOut);
        assertTrue(whyOut.contains("2 introduction paths"), "got: " + whyOut);

        System.out.flush();
        buffer.reset();
        assertEquals(0, why.execute(List.of("--json", "com.example:lib-c")));
        System.out.flush();
        var whyJson = com.jujin.freeway.commons.json.JsonUtils.parseObject(buffer.toString());
        assertEquals("why", whyJson.getString("command"));
        assertEquals(true, whyJson.getBoolean("found"));
        var pathsJson = whyJson.getArray("paths");
        assertEquals(2, pathsJson.size());
        assertEquals(3, whyJson.getObject("artifact").getInt("depth"));

        buffer.reset();
        assertEquals(0, why.execute(List.of("org.unknown:ghost")));
        System.out.flush();
        buffer.reset();  // the tree output must not bleed into the absent-id read
        int absentExit = why.execute(List.of("org.unknown:ghost"));
        System.out.flush();
        String absent = buffer.toString();
        assertEquals(0, absentExit);
        assertTrue(absent.contains("not in the graph"), "got: " + absent);
        assertFalse(absent.contains("depth"), "an absent id prints, it does not guess");
    }

    @Test
    void treeAndWhyMarkExportedApiArtifacts() throws Exception {
        Path repo = dir.resolve("api-repo");
        pom(repo, "com/example/lib-a/1.0.0", "lib-a", "1.0.0", """
            <dependencies>
              <dependency>
                <groupId>com.example</groupId>
                <artifactId>lib-b</artifactId>
                <version>1.1.0</version>
              </dependency>
            </dependencies>
            """);
        pom(repo, "com/example/lib-b/1.1.0", "lib-b", "1.1.0", "");
        Files.writeString(dir.resolve("jeno.toml"), """
            [project]
            name = "demo"
            version = "0.1.0"

            [repos.fix]
            type = "maven"
            url = "%s"

            [deps]
            "com.example:lib-a" = "fix:1.0.0"

            [deps-api]
            "com.example:lib-a" = "fix:1.0.0"
            """.formatted(repo));

        writeLock();
        TreeCommand tree = new TreeCommand(new ArtifactStore(dir.resolve("store")),
                localPomSource(), text -> { throw new IllegalStateException("no natives here"); },
                dir);
        assertEquals(0, tree.execute(List.of()));
        String treeOut = readConsole();
        assertTrue(treeOut.contains("com.example:lib-a@1.0.0  [depth 1, declared, api]"),
                "the direct API dependency is marked, got: " + treeOut);
        assertFalse(treeOut.contains("lib-b@1.1.0  [depth 2, api]"),
                "the POM dependency is implementation-only, got: " + treeOut);

        buffer.reset();
        assertEquals(0, tree.execute(List.of("--json")));
        var treeJson = com.jujin.freeway.commons.json.JsonUtils.parseObject(
                readConsole());
        var artifacts = treeJson.getArray("artifacts");
        boolean libAApi = false;
        for (int i = 0; i < artifacts.size(); i++) {
            var entry = artifacts.getObject(i);
            if (entry.getString("id").equals("com.example:lib-a")) {
                libAApi = entry.getBoolean("api");
            }
        }
        assertTrue(libAApi, "tree JSON carries the API flag");

        buffer.reset();
        WhyCommand why = new WhyCommand(new ArtifactStore(dir.resolve("store")),
                localPomSource(), text -> { throw new IllegalStateException("no natives here"); },
                dir);
        assertEquals(0, why.execute(List.of("com.example:lib-a")));
        assertTrue(readConsole().contains("declared directly, api"),
                "why marks the direct API artifact");

        buffer.reset();
        assertEquals(0, why.execute(List.of("--json", "com.example:lib-a")));
        var whyJson = com.jujin.freeway.commons.json.JsonUtils.parseObject(
                readConsole());
        assertTrue(whyJson.getObject("artifact").getBoolean("api"),
                "why JSON carries the API flag");
    }

    @Test
    void treeAndWhyExposeApiEdgesNotJustApiArtifacts() throws Exception {
        String descriptor = """
            [project]
            name = "widget"
            version = "1.0.0"

            [deps]
            "com.example:lib-b" = "1.0.0"

            [deps-api]
            "com.example:lib-b" = "1.0.0"
            """;
        com.jujin.jeno.project.DescriptorSource descriptors = url -> {
            if (url.contains("widget-1.0.0.jeno.toml")) {
                return descriptor;
            }
            throw new IllegalStateException("unexpected descriptor " + url);
        };
        PomSource poms = (group, artifact, version, base) -> {
            if (group.equals("com.example") && artifact.equals("lib-b")
                    && version.equals("1.0.0")) {
                return """
                    <project>
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>com.example</groupId>
                      <artifactId>lib-b</artifactId>
                      <version>1.0.0</version>
                    </project>
                    """;
            }
            throw new IllegalStateException("unexpected pom " + group + ":" + artifact);
        };
        Files.writeString(dir.resolve("jeno.toml"), """
            [project]
            name = "demo"
            version = "0.1.0"

            [deps]
            widget = "gh:acme/widget@v1.0.0"
            """);
        var project = ProjectReader.parse(Files.readString(dir.resolve("jeno.toml")));
        Files.writeString(dir.resolve("jeno.lock"),
                com.jujin.jeno.resolve.LockFile.serialize(
                        com.jujin.jeno.resolve.Resolver.resolve(project, poms, descriptors),
                        url -> "0".repeat(64)));

        ArtifactStore store = new ArtifactStore(dir.resolve("store"));
        TreeCommand tree = new TreeCommand(store, poms, descriptors, dir);
        buffer.reset();
        assertEquals(0, tree.execute(List.of("--json")));
        var treeJson = com.jujin.freeway.commons.json.JsonUtils.parseObject(
                readConsole());
        boolean foundApiChild = false;
        var artifacts = treeJson.getArray("artifacts");
        for (int i = 0; i < artifacts.size(); i++) {
            var entry = artifacts.getObject(i);
            if (entry.getString("id").equals("widget")) {
                foundApiChild = entry.getArray("apiChildren").size() == 1;
            }
        }
        assertTrue(foundApiChild, "tree JSON names the API child edge");

        buffer.reset();
        assertEquals(0, tree.execute(List.of()));
        assertTrue(readConsole().contains("└─ api com.example:lib-b@1.0.0"),
                "the human tree marks the API edge");

        WhyCommand why = new WhyCommand(store, poms, descriptors, dir);
        buffer.reset();
        assertEquals(0, why.execute(List.of("--json", "com.example:lib-b")));
        var whyJson = com.jujin.freeway.commons.json.JsonUtils.parseObject(
                readConsole());
        var edge = whyJson.getArray("pathEdges").getArray(0).getObject(0);
        assertEquals(true, edge.getBoolean("api"));

        buffer.reset();
        assertEquals(0, why.execute(List.of("com.example:lib-b")));
        assertTrue(readConsole().contains("--api->"),
                "the human why path marks the API edge");
    }

    @Test
    void treeWithoutALockSaysWhatToRunAndDrawsNothing() throws Exception {
        Files.writeString(dir.resolve("jeno.toml"), """
            [project]
            name = "demo"
            version = "0.1.0"
            """);

        TreeCommand tree = new TreeCommand(new ArtifactStore(dir.resolve("store")),
                localPomSource(), text -> { throw new IllegalStateException("no natives here"); },
                dir);
        java.io.ByteArrayOutputStream errors = new java.io.ByteArrayOutputStream();
        java.io.PrintStream originalErr = System.err;
        int exit;
        try {
            System.setErr(new java.io.PrintStream(errors));
            exit = tree.execute(List.of());
        } finally {
            System.setErr(originalErr);
        }

        assertEquals(1, exit);
        assertTrue(errors.toString().contains("no jeno.lock in " + dir), errors.toString());
        assertEquals("", readConsole(), "nothing is drawn without a lock");
    }

    @Test
    void aDriftedManifestIsRefusedInsteadOfDrawn() throws Exception {
        Path repo = dir.resolve("fix-repo");
        pom(repo, "com/example/lib-a/1.0.0", "lib-a", "1.0.0", "");
        pom(repo, "com/example/lib-a/1.1.0", "lib-a", "1.1.0", "");
        Files.writeString(dir.resolve("jeno.toml"), """
            [project]
            name = "demo"
            version = "0.1.0"

            [repos.fix]
            type = "maven"
            url = "%s"

            [deps]
            "com.example:lib-a" = "fix:1.0.0"
            """.formatted(repo));
        writeLock();
        // bump the declaration without re-locking: the lock still pins 1.0.0
        Files.writeString(dir.resolve("jeno.toml"), """
            [project]
            name = "demo"
            version = "0.1.0"

            [repos.fix]
            type = "maven"
            url = "%s"

            [deps]
            "com.example:lib-a" = "fix:1.1.0"
            """.formatted(repo));

        TreeCommand tree = new TreeCommand(new ArtifactStore(dir.resolve("store")),
                localPomSource(), text -> { throw new IllegalStateException("no natives here"); },
                dir);
        java.io.ByteArrayOutputStream errors = new java.io.ByteArrayOutputStream();
        java.io.PrintStream originalErr = System.err;
        int exit;
        try {
            System.setErr(new java.io.PrintStream(errors));
            exit = tree.execute(List.of());
        } finally {
            System.setErr(originalErr);
        }

        assertEquals(1, exit);
        assertTrue(errors.toString().contains("jeno.lock is out of date — run jeno lock"),
                errors.toString());
        assertTrue(errors.toString().contains("com.example:lib-a: lock 1.0.0, manifest 1.1.0"),
                errors.toString());
        assertEquals("", readConsole(), "no graph is printed when the lock disagrees");

        WhyCommand why = new WhyCommand(new ArtifactStore(dir.resolve("store")),
                localPomSource(), text -> { throw new IllegalStateException("no natives here"); },
                dir);
        try {
            System.setErr(new java.io.PrintStream(errors = new java.io.ByteArrayOutputStream()));
            exit = why.execute(List.of("com.example:lib-a"));
        } finally {
            System.setErr(originalErr);
        }
        assertEquals(1, exit);
        assertTrue(errors.toString().contains("jeno.lock is out of date"), errors.toString());
        assertEquals("", readConsole(), "why draws no paths either");
    }

    /** Generates the lock the tests' fixtures need; same local repository as the commands. */
    private void writeLock() throws Exception {
        ArtifactStore store = new ArtifactStore(dir.resolve("store"));
        new LockService(store, localPomSource(),
                text -> { throw new IllegalStateException("no natives here"); }).refresh(dir);
    }

    @Test
    void treeJsonMarksTheScopeAndTheWorkspaceLocalPath() throws Exception {
        // a local Maven repository supplies the metadata; the workspace module
        // supplies the bytes, so the JSON carries both scope and local path
        Path repo = dir.resolve("vendor");
        pom(repo, "com/example/core/1.0.0", "core", "1.0.0", "");
        Path module = dir.resolve("libs/core");
        Files.createDirectories(module);
        Files.writeString(module.resolve("jeno.toml"), """
            [project]
            name = "core"
            group = "com.example"
            version = "1.0.0"
            """);
        Path app = dir.resolve("app");
        Files.createDirectories(app);
        Files.writeString(app.resolve("jeno.toml"), """
            [project]
            name = "app"
            version = "0.1.0"

            [repos.v]
            type = "maven"
            url = "../vendor"

            [deps]
            "com.example:core" = "v:1.0.0"
            """);
        Files.writeString(dir.resolve("jeno.ws.toml"), """
            [ws]
            modules = ["libs/core", "app"]
            """);
        ArtifactStore store = new ArtifactStore(dir.resolve("store"));
        com.jujin.jeno.resolve.ArtifactLocations.useProjectRoot(app);
        var pomSource = new com.jujin.jeno.store.StorePomSource(store);
        new LockService(store, pomSource,
                text -> { throw new IllegalStateException("no natives here"); }).refresh(app);

        TreeCommand tree = new TreeCommand(store, pomSource,
                text -> { throw new IllegalStateException("no natives here"); }, app);
        assertEquals(0, tree.execute(List.of("--json")));

        var root = com.jujin.freeway.commons.json.JsonUtils.parseObject(readConsole());
        var artifacts = root.getArray("artifacts");
        assertEquals(1, artifacts.size());
        assertEquals("main", artifacts.getObject(0).getString("scope"));
        assertEquals("libs/core", artifacts.getObject(0).getString("local"),
                "the path matches the human (local: …) marker");
    }

    @Test
    void treeMarksProcessorScopeOnTheHumanLine() throws Exception {
        Path repo = dir.resolve("vendor");
        pom(repo, "com/example/proc/1.0.0", "proc", "1.0.0", "");
        Files.writeString(dir.resolve("jeno.toml"), """
            [project]
            name = "demo"
            version = "0.1.0"

            [build]
            processors = ["com.example:proc:1.0.0"]
            """);
        System.setProperty("jeno.central.base", "local:" + repo);
        try {
            ArtifactStore store = new ArtifactStore(dir.resolve("store"));
            com.jujin.jeno.resolve.ArtifactLocations.useProjectRoot(dir);
            var pomSource = new com.jujin.jeno.store.StorePomSource(store);
            new LockService(store, pomSource,
                    text -> { throw new IllegalStateException("no natives here"); }).refresh(dir);

            TreeCommand tree = new TreeCommand(store, pomSource,
                    text -> { throw new IllegalStateException("no natives here"); }, dir);
            assertEquals(0, tree.execute(List.of()));
        } finally {
            System.clearProperty("jeno.central.base");
        }

        String out = readConsole();
        assertTrue(out.contains("com.example:proc@1.0.0  [depth 1, processor]"), out);
    }

    // ------------------------------------------------------------------
    // the console capture both commands assert on
    // ------------------------------------------------------------------

    private final java.io.PrintStream realOut = System.out;
    private final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();

    private String readConsole() {
        System.out.flush();
        return buffer.toString();
    }

    {
        System.setOut(new java.io.PrintStream(buffer));
    }

    // JUnit restores System.out after each test method's constructor ran —
    // restore manually in an @AfterEach-style cleanup:
    @org.junit.jupiter.api.AfterEach
    void restoreOut() {
        System.setOut(realOut);
    }

    @Test
    void treePrintsEachArtifactAtItsFirstOccurrence() throws Exception {
        // rule: one line per artifact, where it first appears. root-a reaches
        // mid and root-b; root-b is also declared, so it must not get a second
        // (root) line — it shows up as root-a's child instead.
        Path repo = dir.resolve("fix-repo");
        pom(repo, "com/example/root-a/1.0.0", "root-a", "1.0.0", """
            <dependencies>
              <dependency>
                <groupId>com.example</groupId>
                <artifactId>mid</artifactId>
                <version>1.0.0</version>
              </dependency>
              <dependency>
                <groupId>com.example</groupId>
                <artifactId>root-b</artifactId>
                <version>1.0.0</version>
              </dependency>
            </dependencies>
            """);
        pom(repo, "com/example/mid/1.0.0", "mid", "1.0.0", """
            <dependencies>
              <dependency>
                <groupId>com.example</groupId>
                <artifactId>common</artifactId>
                <version>1.0.0</version>
              </dependency>
            </dependencies>
            """);
        pom(repo, "com/example/root-b/1.0.0", "root-b", "1.0.0", """
            <dependencies>
              <dependency>
                <groupId>com.example</groupId>
                <artifactId>common</artifactId>
                <version>1.0.0</version>
              </dependency>
            </dependencies>
            """);
        pom(repo, "com/example/root-c/1.0.0", "root-c", "1.0.0", """
            <dependencies>
              <dependency>
                <groupId>com.example</groupId>
                <artifactId>common</artifactId>
                <version>1.0.0</version>
              </dependency>
            </dependencies>
            """);
        pom(repo, "com/example/common/1.0.0", "common", "1.0.0", "");

        Files.writeString(dir.resolve("jeno.toml"), """
            [project]
            name = "demo"
            version = "0.1.0"

            [limits]
            max-depth = 5

            [repos.fix]
            type = "maven"
            url = "%s"

            [deps]
            "com.example:root-a" = "fix:1.0.0"
            "com.example:root-b" = "fix:1.0.0"
            "com.example:root-c" = "fix:1.0.0"
            """.formatted(repo));

        writeLock();
        TreeCommand tree = new TreeCommand(new ArtifactStore(dir.resolve("store")),
                localPomSource(), text -> { throw new IllegalStateException("no natives here"); },
                dir);
        assertEquals(0, tree.execute(List.of()));

        String out = readConsole();
        String expected = """
            demo@0.1.0 (budget 5)
            com.example:root-a@1.0.0  [depth 1, declared]
            ├─ com.example:mid@1.0.0  [depth 2]
            │  └─ com.example:common@1.0.0  [depth 3]
            └─ com.example:root-b@1.0.0  [depth 1, declared]
               └─ com.example:common@1.0.0  [depth 3]  (*) above
            com.example:root-c@1.0.0  [depth 1, declared]
            └─ com.example:common@1.0.0  [depth 3]  (*) above
            """;
        assertEquals(expected, out, "rule A: every artifact prints where it first appears");

        assertEquals(1, count(out, "com.example:root-b@1.0.0"),
                "the declared root reached through root-a gets no second line");
        assertFalse(out.contains("\ncom.example:root-b@1.0.0"),
                "root-b must not appear at column 0, got: " + out);
        assertEquals(2, count(out, "(*) above"), "both later common lines carry the marker");
    }

    @Test
    void treeJsonIsOneDocumentWithTheSameFacts() throws Exception {
        Path repo = dir.resolve("fix-repo");
        pom(repo, "com/example/lib-a/1.0.0", "lib-a", "1.0.0", """
            <dependencies>
              <dependency>
                <groupId>com.example</groupId>
                <artifactId>lib-b</artifactId>
                <version>1.1.0</version>
              </dependency>
            </dependencies>
            """);
        pom(repo, "com/example/lib-b/1.1.0", "lib-b", "1.1.0", """
            <dependencies>
              <dependency>
                <groupId>com.example</groupId>
                <artifactId>lib-c</artifactId>
                <version>2.0.0</version>
              </dependency>
            </dependencies>
            """);
        pom(repo, "com/example/lib-c/2.0.0", "lib-c", "2.0.0", "");
        pom(repo, "com/example/lib-d/1.0.0", "lib-d", "1.0.0", "");
        Files.writeString(dir.resolve("jeno.toml"), """
            [project]
            name = "demo"
            version = "0.1.0"

            [limits]
            max-depth = 5

            [repos.fix]
            type = "maven"
            url = "%s"

            [deps]
            "com.example:lib-a" = "fix:1.0.0"
            "com.example:lib-d" = "fix:1.0.0"
            """.formatted(repo));

        writeLock();
        TreeCommand tree = new TreeCommand(new ArtifactStore(dir.resolve("store")),
                localPomSource(), text -> { throw new IllegalStateException("no natives here"); },
                dir);
        assertEquals(0, tree.execute(List.of("--json")));

        String json = readConsole();
        var root = com.jujin.freeway.commons.json.JsonUtils.parseObject(json);
        assertEquals(2, root.getInt("schemaVersion"));
        assertEquals("tree", root.getString("command"));
        assertEquals("ok", root.getString("status"));
        assertEquals(0, root.getInt("exitCode"));
        var roots = root.getArray("roots");
        assertEquals(2, roots.size(), "one entry per declaration");
        assertEquals("com.example:lib-a", roots.getString(0), "declaration order");
        assertEquals("com.example:lib-d", roots.getString(1));

        var artifacts = root.getArray("artifacts");
        for (int i = 0; i < artifacts.size(); i++) {
            assertEquals("main", artifacts.getObject(i).getString("scope"),
                    "scope is part of the tree contract");
        }
        java.util.List<String> ids = new java.util.ArrayList<>();
        java.util.Map<String, Integer> depths = new java.util.HashMap<>();
        for (int i = 0; i < artifacts.size(); i++) {
            var entry = artifacts.getObject(i);
            ids.add(entry.getString("id"));
            depths.put(entry.getString("id"), entry.getInt("depth"));
        }
        assertEquals(ids.stream().sorted().toList(), ids, "artifacts are id-sorted");
        assertEquals(3, depths.get("com.example:lib-c"), "the same depth the tree prints");
        assertEquals(5, root.getInt("budget"));
    }

    @Test
    void treeMarksDevRootsAndTheirSubtrees() throws Exception {
        Path repo = dir.resolve("fix-repo");
        pom(repo, "com/example/lib-b/1.1.0", "lib-b", "1.1.0", """
            <dependencies>
              <dependency>
                <groupId>com.example</groupId>
                <artifactId>lib-c</artifactId>
                <version>2.0.0</version>
              </dependency>
            </dependencies>
            """);
        pom(repo, "com/example/lib-c/2.0.0", "lib-c", "2.0.0", "");
        Files.writeString(dir.resolve("jeno.toml"), """
            [project]
            name = "demo"
            version = "0.1.0"

            [repos.fix]
            type = "maven"
            url = "%s"

            [deps-dev]
            "com.example:lib-b" = "fix:1.1.0"
            """.formatted(repo));

        writeLock();
        TreeCommand tree = new TreeCommand(new ArtifactStore(dir.resolve("store")),
                localPomSource(), text -> { throw new IllegalStateException("no natives here"); },
                dir);
        assertEquals(0, tree.execute(List.of()));

        String out = readConsole();
        assertTrue(out.contains("com.example:lib-b@1.1.0  [depth 1, declared, dev]"),
                "a dev root is marked, got: " + out);
        assertTrue(out.contains("└─ com.example:lib-c@2.0.0  [depth 2, dev]"),
                "its subtree is dev and drawn below it, got: " + out);
    }

    /** The fixture repositories are {@code local:} directories of hand-written poms. */
    private static PomSource localPomSource() {
        return (g, a, v, base) -> {
            String path = base.substring("local:".length()) + "/" + g.replace('.', '/')
                    + "/" + a + "/" + v + "/" + a + "-" + v + ".pom";
            try {
                return Files.readString(Path.of(path));
            } catch (IOException e) {
                throw new IllegalStateException("cannot read fixture pom " + path, e);
            }
        };
    }

    private static int count(String haystack, String needle) {        int total = 0;
        int from = 0;
        while ((from = haystack.indexOf(needle, from)) >= 0) {
            total++;
            from += needle.length();
        }
        return total;
    }

    private void pom(Path repo, String layout, String artifactId, String version,
                     String dependencies) throws IOException {
        Path pom = repo.resolve(layout).resolve(artifactId + "-" + version + ".pom");
        Files.createDirectories(pom.getParent());
        Files.writeString(pom, """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>%s</groupId>
              <artifactId>%s</artifactId>
              <version>%s</version>
              %s
            </project>
            """.formatted(layoutGroup(layout), artifactId, version, dependencies));
        Files.write(pom.resolveSibling(artifactId + "-" + version + ".jar"),
                new byte[0]);
    }

    private static String layoutGroup(String layout) {
        // "com/example/lib-a/1.0.0" -> "com.example"
        String[] parts = layout.split("/");
        return String.join(".", parts[0], parts[1]);
    }
}
