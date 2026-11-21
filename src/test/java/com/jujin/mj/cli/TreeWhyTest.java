package com.jujin.mj.cli;

import com.jujin.mj.pom.PomSource;
import com.jujin.mj.project.DescriptorSource;
import com.jujin.mj.project.ProjectReader;
import com.jujin.mj.store.ArtifactStore;
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
 * The budget made visible: mj tree prints the graph with depths (and the
 * above-marker for repeated subtrees), mj why prints every introduction
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

        Files.writeString(dir.resolve("mj.toml"), """
            [project]
            name = "demo"
            version = "0.1.0"

            [limits]
            max-depth = 5

            [repos]
            fix = "local:%s"

            [deps.fix]
            "com.example:lib-a" = "1.0.0"
            "com.example:lib-d" = "1.0.0"
            """.formatted(fixRepo));

        PomSource pomSource = (g, a, v, base) -> {
            String path = base.substring("local:".length()) + "/" + g.replace('.', '/')
                    + "/" + a + "/" + v + "/" + a + "-" + v + ".pom";
            try {
                return Files.readString(Path.of(path));
            } catch (IOException e) {
                throw new IllegalStateException("cannot read fixture pom " + path, e);
            }
        };

        TreeCommand tree = new TreeCommand(new ArtifactStore(dir.resolve("store")),
                pomSource, text -> { throw new IllegalStateException("no natives here"); },
                dir);
        int treeExit = tree.execute(List.of());

        assertEquals(0, treeExit);
        String banner = readConsole();
        assertTrue(banner.contains("lib-a@1.0.0  [depth 1, declared]"), "got: " + banner);
        assertTrue(banner.contains("lib-c@2.0.0  [depth 3]"), "got: " + banner);

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
    }

    private static String layoutGroup(String layout) {
        // "com/example/lib-a/1.0.0" -> "com.example"
        String[] parts = layout.split("/");
        return String.join(".", parts[0], parts[1]);
    }
}
