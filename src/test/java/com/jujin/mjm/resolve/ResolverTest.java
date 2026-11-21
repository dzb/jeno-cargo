package com.jujin.mjm.resolve;

import com.jujin.mjm.pom.PomSource;
import com.jujin.mjm.project.DescriptorSource;
import com.jujin.mjm.project.MjProject;
import com.jujin.mjm.project.ProjectReader;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the resolver — the three-piece soul: semantic-aware
 * MVS, the depth budget over the longest path, and strict visibility's
 * declared/closure split. Every fixture is the shape a real Central or
 * mj-native dependency tree takes.
 */
class ResolverTest {

    private static PomSource pomSource(String... keyAndPom) {
        Map<String, String> poms = new LinkedHashMap<>();
        for (int i = 0; i < keyAndPom.length; i += 2) {
            poms.put(keyAndPom[i], keyAndPom[i + 1]);
        }
        return (groupId, artifactId, version, repositoryBase) ->
                poms.get(groupId + ":" + artifactId + ":" + version);
    }

    /** The descriptor URL the resolver computes for a gh-hosted native release. */
    private static String ghDescriptor(String owner, String repo, String tag, String version) {
        return "https://github.com/" + owner + "/" + repo + "/releases/download/"
                + tag + "/" + repo + "-" + version + ".mjm.toml";
    }

    private static DescriptorSource descriptorSource(String... urlAndText) {
        Map<String, String> descriptors = new LinkedHashMap<>();
        for (int i = 0; i < urlAndText.length; i += 2) {
            descriptors.put(urlAndText[i], urlAndText[i + 1]);
        }
        return url -> descriptors.get(url);
    }

    private static MjProject project(String toml) {
        return ProjectReader.parse(toml);
    }

    /** A pom whose identity matches the coordinate it is served under. */
    private static String pom(String groupId, String artifactId, String version,
                              String dependencies) {
        return """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>%s</groupId>
              <artifactId>%s</artifactId>
              <version>%s</version>
              %s
            </project>
            """.formatted(groupId, artifactId, version, dependencies);
    }

    // ------------------------------------------------------------------
    // classifiers
    // ------------------------------------------------------------------

    @Test
    void aClassifiedDependencyKeepsItsClassifierInTheJarUrl() {
        // the declaring pom asks for the native sibling, not the base jar
        ResolvedGraph graph = Resolver.resolve(project("""
            [project]
            name = "demo"
            version = "0.1.0"

            [deps]
            "com.example:app" = "1.0.0"
            """), pomSource(
                "com.example:app:1.0.0", pom("com.example", "app", "1.0.0", """
                    <dependencies>
                      <dependency>
                        <groupId>io.netty</groupId>
                        <artifactId>netty-transport-native-epoll</artifactId>
                        <version>4.1.0</version>
                        <classifier>linux-x86_64</classifier>
                      </dependency>
                    </dependencies>
                    """),
                "io.netty:netty-transport-native-epoll:4.1.0",
                pom("io.netty", "netty-transport-native-epoll", "4.1.0", "")),
            descriptorSource());

        ResolvedGraph.ResolvedArtifact nativeDep = graph.artifacts().stream()
                .filter(a -> a.id().startsWith("io.netty:netty-transport-native-epoll"))
                .findFirst().orElseThrow();
        assertTrue(nativeDep.id().endsWith(":linux-x86_64"),
                "the classifier is part of the identity: " + nativeDep.id());
        assertTrue(nativeDep.jarUrl().endsWith(
                        "netty-transport-native-epoll-4.1.0-linux-x86_64.jar"),
                "the download URL names the classified artifact: " + nativeDep.jarUrl());
    }

    @Test
    void baseAndClassifiedArtifactsAreDistinctNodes() {
        // one pom asks for both the base jar and its -sources sibling; they
        // must not collapse into one node, or the closure picks one at random
        ResolvedGraph graph = Resolver.resolve(project("""
            [project]
            name = "demo"
            version = "0.1.0"

            [deps]
            "com.example:app" = "1.0.0"
            """), pomSource(
                "com.example:app:1.0.0", pom("com.example", "app", "1.0.0", """
                    <dependencies>
                      <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>lib</artifactId>
                        <version>2.0.0</version>
                      </dependency>
                      <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>lib</artifactId>
                        <version>2.0.0</version>
                        <classifier>sources</classifier>
                      </dependency>
                    </dependencies>
                    """),
                "com.example:lib:2.0.0", pom("com.example", "lib", "2.0.0", "")),
            descriptorSource());

        assertEquals(2, graph.artifacts().stream()
                        .filter(a -> a.id().startsWith("com.example:lib")).count(),
                "base and classified are two nodes: " + graph.artifacts().stream()
                        .map(ResolvedGraph.ResolvedArtifact::id).toList());
    }

    // ------------------------------------------------------------------
    // [dev-deps] — scope semantics
    // ------------------------------------------------------------------

    @Test
    void aDevOnlyArtifactIsMarkedDevAndNeverCompiled() {
        ResolvedGraph graph = Resolver.resolve(project("""
            [project]
            name = "demo"
            version = "0.1.0"

            [dev-deps]
            "com.example:test-kit" = "1.0.0"
            """), pomSource(
                "com.example:test-kit:1.0.0", pom("com.example", "test-kit", "1.0.0", "")),
            descriptorSource());

        assertEquals(1, graph.artifacts().size());
        assertEquals(Scope.DEV, graph.artifacts().getFirst().scope());
        assertTrue(graph.compileArtifacts().isEmpty(),
                "a dev-only artifact is not on the compile classpath");
        assertTrue(graph.mainArtifacts().isEmpty(),
                "a dev-only artifact is not in the runtime closure");
    }

    @Test
    void aSharedArtifactIsMainBecauseProductionReachWins() {
        // lib-a is both a main dependency and (via test-kit) a dev one —
        // production use wins, so it stays MAIN and its subtree stays MAIN.
        ResolvedGraph graph = Resolver.resolve(project("""
            [project]
            name = "demo"
            version = "0.1.0"

            [deps]
            "com.example:lib-a" = "1.0.0"

            [dev-deps]
            "com.example:test-kit" = "1.0.0"
            """), pomSource(
                "com.example:lib-a:1.0.0", pom("com.example", "lib-a", "1.0.0", ""),
                "com.example:test-kit:1.0.0", pom("com.example", "test-kit", "1.0.0", """
                    <dependencies>
                      <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>lib-a</artifactId>
                        <version>1.0.0</version>
                      </dependency>
                    </dependencies>
                    """)),
            descriptorSource());

        assertEquals(Scope.MAIN,
                byId(graph.artifacts(), "com.example:lib-a").scope(),
                "a main root keeps its artifact MAIN even when a dev root also reaches it");
        assertEquals(Scope.DEV,
                byId(graph.artifacts(), "com.example:test-kit").scope());
        assertEquals(1, graph.mainArtifacts().size());
    }

    @Test
    void aDevSubtreeIsDevAllTheWayDown() {
        ResolvedGraph graph = Resolver.resolve(project("""
            [project]
            name = "demo"
            version = "0.1.0"

            [limits]
            max-depth = 3

            [dev-deps]
            "com.example:test-kit" = "1.0.0"
            """), pomSource(
                "com.example:test-kit:1.0.0", pom("com.example", "test-kit", "1.0.0", """
                    <dependencies>
                      <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>assert-lib</artifactId>
                        <version>2.0.0</version>
                      </dependency>
                    </dependencies>
                    """),
                "com.example:assert-lib:2.0.0", pom("com.example", "assert-lib", "2.0.0", "")),
            descriptorSource());

        assertEquals(Scope.DEV,
                byId(graph.artifacts(), "com.example:assert-lib").scope(),
                "the dev root's transitives are dev too");
        assertTrue(graph.compileArtifacts().isEmpty());
        assertTrue(graph.mainArtifacts().isEmpty());
    }

    @Test
    void devArtifactsStillCountAgainstTheDepthBudget() {
        // the budget is a resolution fact; a too-deep dev tree is still too deep
        ResolveException error = assertThrows(ResolveException.class, () ->
                Resolver.resolve(project("""
                    [project]
                    name = "demo"
                    version = "0.1.0"

                    [limits]
                    max-depth = 1

                    [dev-deps]
                    "com.example:test-kit" = "1.0.0"
                    """), pomSource(
                    "com.example:test-kit:1.0.0", pom("com.example", "test-kit", "1.0.0", """
                        <dependencies>
                          <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>assert-lib</artifactId>
                            <version>2.0.0</version>
                          </dependency>
                        </dependencies>
                        """),
                    "com.example:assert-lib:2.0.0",
                    pom("com.example", "assert-lib", "2.0.0", "")),
                    descriptorSource()));

        assertTrue(error.getMessage().contains("depth"),
                "depth budget applies to dev requirements as well");
    }
    private static ResolvedGraph.ResolvedArtifact byId(
            List<ResolvedGraph.ResolvedArtifact> artifacts, String id) {
        return artifacts.stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------
    // the closure and its shape
    // ------------------------------------------------------------------

    @Test
    void aDirectDependencySitsAtDepthOneWithCentralLayoutUrls() {
        ResolvedGraph graph = Resolver.resolve(project("""
            [project]
            name = "demo"
            version = "0.1.0"

            [deps]
            "com.example:lib-a" = "1.0.0"
            """), pomSource(
                "com.example:lib-a:1.0.0", pom("com.example", "lib-a", "1.0.0", "")),
            descriptorSource());

        assertEquals(1, graph.artifacts().size());
        ResolvedGraph.ResolvedArtifact libA = graph.artifacts().getFirst();
        assertEquals("com.example:lib-a", libA.id());
        assertEquals("1.0.0", libA.version());
        assertEquals("central", libA.source());
        assertEquals("https://repo.maven.apache.org/maven2/com/example/lib-a/1.0.0/lib-a-1.0.0.jar",
                libA.jarUrl(), "the Maven repository layout");
        assertEquals("https://repo.maven.apache.org/maven2/com/example/lib-a/1.0.0/lib-a-1.0.0.pom",
                libA.metadataUrl());
        assertEquals(1, libA.depth());
        assertTrue(libA.declared());
        assertEquals(graph.artifacts(), graph.direct(), "one artifact: declared is the closure");
    }

    @Test
    void transitiveClosureBuildsAndCountsDepth() {
        ResolvedGraph graph = Resolver.resolve(project("""
            [project]
            name = "demo"
            version = "0.1.0"

            [limits]
            max-depth = 5

            [deps]
            "com.example:lib-a" = "1.0.0"
            """), pomSource(
                "com.example:lib-a:1.0.0", pom("com.example", "lib-a", "1.0.0", """
                    <dependencies>
                      <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>lib-b</artifactId>
                        <version>1.1.0</version>
                        <scope>runtime</scope>
                      </dependency>
                    </dependencies>
                    """),
                "com.example:lib-b:1.1.0", pom("com.example", "lib-b", "1.1.0", """
                    <dependencies>
                      <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>lib-c</artifactId>
                        <version>2.0.0</version>
                      </dependency>
                    </dependencies>
                    """),
                "com.example:lib-c:2.0.0", pom("com.example", "lib-c", "2.0.0", "")),
            descriptorSource());

        assertEquals(3, graph.artifacts().size(), "runtime scope propagates transitively");
        assertEquals(1, graph.direct().size(), "only lib-a is declared");
        assertEquals(2, artifact(graph, "com.example:lib-b").depth());
        assertEquals(3, artifact(graph, "com.example:lib-c").depth());
    }

    // ------------------------------------------------------------------
    // semantic-aware MVS
    // ------------------------------------------------------------------

    @Test
    void sameMajorTakesTheMax() {
        ResolvedGraph graph = Resolver.resolve(project("""
            [project]
            name = "demo"
            version = "0.1.0"

            [deps]
            "com.example:lib-a" = "1.0.0"
            "com.example:lib-d" = "1.0.0"
            """), pomSource(
                "com.example:lib-a:1.0.0", pom("com.example", "lib-a", "1.0.0", """
                    <dependencies>
                      <dependency>
                        <groupId>org.example</groupId>
                        <artifactId>lib-x</artifactId>
                        <version>1.0.0</version>
                      </dependency>
                    </dependencies>
                    """),
                "com.example:lib-d:1.0.0", pom("com.example", "lib-d", "1.0.0", """
                    <dependencies>
                      <dependency>
                        <groupId>org.example</groupId>
                        <artifactId>lib-x</artifactId>
                        <version>1.2.0</version>
                      </dependency>
                    </dependencies>
                    """),
                "org.example:lib-x:1.0.0", pom("org.example", "lib-x", "1.0.0", ""),
                "org.example:lib-x:1.2.0", pom("org.example", "lib-x", "1.2.0", "")),
            descriptorSource());

        assertEquals("1.2.0", artifact(graph, "org.example:lib-x").version(),
                "same major: the maximum wins");
    }

    @Test
    void crossMajorDemandIsAHardConflictWithBothPaths() {
        ResolveException ex = assertThrows(ResolveException.class, () ->
                Resolver.resolve(project("""
                    [project]
                    name = "demo"
                    version = "0.1.0"

                    [deps]
                    "com.example:lib-a" = "1.0.0"
                    "com.example:lib-d" = "1.0.0"
                    """), pomSource(
                    "com.example:lib-a:1.0.0", pom("com.example", "lib-a", "1.0.0", """
                        <dependencies>
                          <dependency>
                            <groupId>org.example</groupId>
                            <artifactId>lib-x</artifactId>
                            <version>1.0.0</version>
                          </dependency>
                        </dependencies>
                        """),
                    "com.example:lib-d:1.0.0", pom("com.example", "lib-d", "1.0.0", """
                        <dependencies>
                          <dependency>
                            <groupId>org.example</groupId>
                            <artifactId>lib-x</artifactId>
                            <version>2.0.0</version>
                          </dependency>
                        </dependencies>
                        """),
                    "org.example:lib-x:1.0.0", pom("org.example", "lib-x", "1.0.0", ""),
                    "org.example:lib-x:2.0.0", pom("org.example", "lib-x", "2.0.0", "")),
                descriptorSource()));

        assertTrue(ex.getMessage().contains("across majors [1, 2]"), "got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("lib-x@1.0.0"), "the conflict names both sides");
        assertTrue(ex.getMessage().contains("lib-x@2.0.0"), "got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("[overrides]"), "the exit is named");
    }

    @Test
    void nonSemverCentralVersionsFallBackToMaxWins() {
        ResolvedGraph graph = Resolver.resolve(project("""
            [project]
            name = "demo"
            version = "0.1.0"

            [deps]
            "com.example:lib-a" = "1.0.0"
            "com.example:lib-d" = "1.0.0"
            """), pomSource(
                "com.example:lib-a:1.0.0", pom("com.example", "lib-a", "1.0.0", """
                    <dependencies>
                      <dependency>
                        <groupId>org.joda</groupId>
                        <artifactId>joda-thing</artifactId>
                        <version>2023.01</version>
                      </dependency>
                    </dependencies>
                    """),
                "com.example:lib-d:1.0.0", pom("com.example", "lib-d", "1.0.0", """
                    <dependencies>
                      <dependency>
                        <groupId>org.joda</groupId>
                        <artifactId>joda-thing</artifactId>
                        <version>2023.02</version>
                      </dependency>
                    </dependencies>
                    """),
                "org.joda:joda-thing:2023.01", pom("org.joda", "joda-thing", "2023.01", ""),
                "org.joda:joda-thing:2023.02", pom("org.joda", "joda-thing", "2023.02", "")),
            descriptorSource());

        assertEquals("2023.02", artifact(graph, "org.joda:joda-thing").version(),
                "no extractable major: plain max-wins");
    }

    @Test
    void anOverridePinsTheVersionEvenAgainstTransitives() {
        ResolvedGraph graph = Resolver.resolve(project("""
            [project]
            name = "demo"
            version = "0.1.0"

            [deps]
            "com.example:lib-a" = "1.0.0"
            "com.example:lib-d" = "1.0.0"

            [overrides]
            "org.example:lib-x" = "1.5.0"
            """), pomSource(
                "com.example:lib-a:1.0.0", pom("com.example", "lib-a", "1.0.0", """
                    <dependencies>
                      <dependency>
                        <groupId>org.example</groupId>
                        <artifactId>lib-x</artifactId>
                        <version>1.0.0</version>
                      </dependency>
                    </dependencies>
                    """),
                "com.example:lib-d:1.0.0", pom("com.example", "lib-d", "1.0.0", """
                    <dependencies>
                      <dependency>
                        <groupId>org.example</groupId>
                        <artifactId>lib-x</artifactId>
                        <version>2.0.0</version>
                      </dependency>
                    </dependencies>
                    """),
                "org.example:lib-x:1.0.0", pom("org.example", "lib-x", "1.0.0", ""),
                "org.example:lib-x:1.5.0", pom("org.example", "lib-x", "1.5.0", ""),
                "org.example:lib-x:2.0.0", pom("org.example", "lib-x", "2.0.0", "")),
            descriptorSource());

        assertEquals("1.5.0", artifact(graph, "org.example:lib-x").version(),
                "the override wins over every constraint");
    }

    @Test
    void declaringAndOverridingTheSameIdIsRejected() {
        ResolveException ex = assertThrows(ResolveException.class, () ->
                Resolver.resolve(project("""
                    [project]
                    name = "demo"
                    version = "0.1.0"

                    [deps]
                    "com.example:lib-a" = "1.0.0"

                    [overrides]
                    "com.example:lib-a" = "2.0.0"
                    """), pomSource(), descriptorSource()));
        assertTrue(ex.getMessage().contains("never both"), "got: " + ex.getMessage());
    }

    // ------------------------------------------------------------------
    // the depth budget and cycles
    // ------------------------------------------------------------------

    @Test
    void theBudgetMeasuresTheLongestPath() {
        // lib-c is reachable only through lib-b — depth 3 against a budget of 2.
        ResolveException ex = assertThrows(ResolveException.class, () ->
                Resolver.resolve(project("""
                    [project]
                    name = "demo"
                    version = "0.1.0"

                    [limits]
                    max-depth = 2

                    [deps]
                    "com.example:lib-a" = "1.0.0"
                    """), pomSource(
                    "com.example:lib-a:1.0.0", pom("com.example", "lib-a", "1.0.0", """
                        <dependencies>
                          <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>lib-b</artifactId>
                            <version>1.1.0</version>
                          </dependency>
                        </dependencies>
                        """),
                    "com.example:lib-b:1.1.0", pom("com.example", "lib-b", "1.1.0", """
                        <dependencies>
                          <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>lib-c</artifactId>
                            <version>2.0.0</version>
                          </dependency>
                        </dependencies>
                        """),
                    "com.example:lib-c:2.0.0", pom("com.example", "lib-c", "2.0.0", "")),
                descriptorSource()));

        assertTrue(ex.getMessage().contains("depth 3 (budget is 2)"), "got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(
                        "com.example:lib-a@1.0.0 -> com.example:lib-b@1.1.0 -> com.example:lib-c@2.0.0"),
                "the full introduction path is named, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("max-depth"), "the exits are named");
    }

    @Test
    void aCycleIsAnErrorNotALoop() {
        ResolveException ex = assertThrows(ResolveException.class, () ->
                Resolver.resolve(project("""
                    [project]
                    name = "demo"
                    version = "0.1.0"

                    [deps]
                    "com.example:lib-a" = "1.0.0"
                    """), pomSource(
                    "com.example:lib-a:1.0.0", pom("com.example", "lib-a", "1.0.0", """
                        <dependencies>
                          <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>lib-b</artifactId>
                            <version>1.1.0</version>
                          </dependency>
                        </dependencies>
                        """),
                    "com.example:lib-b:1.1.0", pom("com.example", "lib-b", "1.1.0", """
                        <dependencies>
                          <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>lib-a</artifactId>
                            <version>1.0.0</version>
                          </dependency>
                        </dependencies>
                        """)),
                descriptorSource()));
        assertTrue(ex.getMessage().contains("circular dependency"), "got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("lib-a@"), "the cycle names its members");
    }

    // ------------------------------------------------------------------
    // exclusions, per path
    // ------------------------------------------------------------------

    @Test
    void anExclusionRemovesTheSubtreeOnItsPath() {
        ResolvedGraph graph = Resolver.resolve(project("""
            [project]
            name = "demo"
            version = "0.1.0"

            [deps]
            "com.example:lib-a" = "1.0.0"
            """), pomSource(
                "com.example:lib-a:1.0.0", pom("com.example", "lib-a", "1.0.0", """
                    <dependencies>
                      <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>lib-b</artifactId>
                        <version>1.1.0</version>
                        <exclusions>
                          <exclusion>
                            <groupId>org.example</groupId>
                            <artifactId>heavy</artifactId>
                          </exclusion>
                        </exclusions>
                      </dependency>
                    </dependencies>
                    """),
                "com.example:lib-b:1.1.0", pom("com.example", "lib-b", "1.1.0", """
                    <dependencies>
                      <dependency>
                        <groupId>org.example</groupId>
                        <artifactId>heavy</artifactId>
                        <version>9.0.0</version>
                      </dependency>
                    </dependencies>
                    """),
                "org.example:heavy:9.0.0", pom("org.example", "heavy", "9.0.0", "")),
            descriptorSource());

        assertEquals(2, graph.artifacts().size(),
                "heavy is excluded on the only path that reaches it");
    }

    @Test
    void anExclusionIsPerPathSoAnotherRouteStillBringsTheLibrary() {
        ResolvedGraph graph = Resolver.resolve(project("""
            [project]
            name = "demo"
            version = "0.1.0"

            [deps]
            "com.example:lib-a" = "1.0.0"
            "com.example:lib-d" = "1.0.0"
            """), pomSource(
                "com.example:lib-a:1.0.0", pom("com.example", "lib-a", "1.0.0", """
                    <dependencies>
                      <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>lib-b</artifactId>
                        <version>1.1.0</version>
                        <exclusions>
                          <exclusion>
                            <groupId>org.example</groupId>
                            <artifactId>heavy</artifactId>
                          </exclusion>
                        </exclusions>
                      </dependency>
                    </dependencies>
                    """),
                "com.example:lib-b:1.1.0", pom("com.example", "lib-b", "1.1.0", """
                    <dependencies>
                      <dependency>
                        <groupId>org.example</groupId>
                        <artifactId>heavy</artifactId>
                        <version>9.0.0</version>
                      </dependency>
                    </dependencies>
                    """),
                "com.example:lib-d:1.0.0", pom("com.example", "lib-d", "1.0.0", """
                    <dependencies>
                      <dependency>
                        <groupId>org.example</groupId>
                        <artifactId>heavy</artifactId>
                        <version>9.0.0</version>
                      </dependency>
                    </dependencies>
                    """),
                "org.example:heavy:9.0.0", pom("org.example", "heavy", "9.0.0", "")),
            descriptorSource());

        assertEquals("9.0.0", artifact(graph, "org.example:heavy").version(),
                "excluded via lib-a, present via lib-d — per-path exclusions");
    }

    // ------------------------------------------------------------------
    // mj-native species
    // ------------------------------------------------------------------

    @Test
    void aNativeDependencyExpandsReleaseUrlsAndWalksItsDescriptor() {
        ResolvedGraph graph = Resolver.resolve(project("""
            [project]
            name = "demo"
            version = "0.1.0"

            [deps]
            mylib = "gh:acme/mylib@v1.2.0"
            """), pomSource(
                "com.example:lib-a:1.0.0", pom("com.example", "lib-a", "1.0.0", "")),
            descriptorSource(
                "https://github.com/acme/mylib/releases/download/v1.2.0/mylib-1.2.0.mjm.toml", """
                [project]
                name = "mylib"
                version = "1.2.0"

                [deps]
                "com.example:lib-a" = "1.0.0"
                """));

        ResolvedGraph.ResolvedArtifact mylib = artifact(graph, "mylib");
        assertEquals("gh:acme/mylib", mylib.source());
        assertEquals("https://github.com/acme/mylib/releases/download/v1.2.0/mylib-1.2.0.jar",
                mylib.jarUrl(), "the GitHub release layout, tag as declared");
        assertEquals("https://github.com/acme/mylib/releases/download/v1.2.0/mylib-1.2.0.mjm.toml",
                mylib.metadataUrl(), "the descriptor travels next to the jar");
        assertEquals(1, mylib.depth());

        ResolvedGraph.ResolvedArtifact libA = artifact(graph, "com.example:lib-a");
        assertEquals("central", libA.source());
        assertEquals(2, libA.depth(), "the descriptor's dependencies are transitive");
    }

    @Test
    void aRetractedTransitiveCandidateDropsOutOfMvs() {
        // lib-x@1.0.0 needs mylib@1.0.0; lib-y@1.0.0 needs mylib@1.1.0; the
        // 1.1.0 descriptor retracts 1.0.0 — so 1.0.0 cannot be selected.
        ResolvedGraph graph = Resolver.resolve(project("""
            [project]
            name = "demo"
            version = "0.1.0"

            [deps]
            lib-x = "gh:acme/lib-x@v1.0.0"
            lib-y = "gh:acme/lib-y@v1.0.0"
            """), pomSource(), descriptorSource(
                "https://github.com/acme/lib-x/releases/download/v1.0.0/lib-x-1.0.0.mjm.toml", """
                [project]
                name = "lib-x"
                version = "1.0.0"

                [deps]
                mylib = "gh:acme/mylib@v1.0.0"
                """,
                "https://github.com/acme/lib-y/releases/download/v1.0.0/lib-y-1.0.0.mjm.toml", """
                [project]
                name = "lib-y"
                version = "1.0.0"

                [deps]
                mylib = "gh:acme/mylib@v1.1.0"
                """,
                "https://github.com/acme/mylib/releases/download/v1.0.0/mylib-1.0.0.mjm.toml", """
                [project]
                name = "mylib"
                version = "1.0.0"
                """,
                "https://github.com/acme/mylib/releases/download/v1.1.0/mylib-1.1.0.mjm.toml", """
                [project]
                name = "mylib"
                version = "1.1.0"

                [retract]
                "1.0.0" = "the 1.0.0 serializer corrupts data"
                """));

        assertEquals("1.1.0", artifact(graph, "mylib").version(),
                "the retracted candidate is out; the surviving maximum wins");
    }

    // ------------------------------------------------------------------
    // strict visibility
    // ------------------------------------------------------------------

    @Test
    void directAndClosureSplitCompileFromRuntime() {
        ResolvedGraph graph = Resolver.resolve(project("""
            [project]
            name = "demo"
            version = "0.1.0"

            [deps]
            "com.example:lib-a" = "1.0.0"
            "com.example:lib-d" = "1.0.0"
            """), pomSource(
                "com.example:lib-a:1.0.0", pom("com.example", "lib-a", "1.0.0", """
                    <dependencies>
                      <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>lib-b</artifactId>
                        <version>1.1.0</version>
                      </dependency>
                    </dependencies>
                    """),
                "com.example:lib-b:1.1.0", pom("com.example", "lib-b", "1.1.0", ""),
                "com.example:lib-d:1.0.0", pom("com.example", "lib-d", "1.0.0", "")),
            descriptorSource());

        assertEquals(3, graph.artifacts().size(), "the closure feeds the runtime classpath");
        assertEquals(2, graph.direct().size(), "the declared pair feeds the compile classpath");
        assertTrue(graph.direct().stream().allMatch(ResolvedGraph.ResolvedArtifact::declared));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static ResolvedGraph.ResolvedArtifact artifact(ResolvedGraph graph, String id) {
        return graph.artifacts().stream()
                .filter(artifact -> artifact.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no artifact '" + id + "' in "
                        + graph.artifacts()));
    }
}
