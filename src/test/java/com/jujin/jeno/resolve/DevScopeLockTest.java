package com.jujin.jeno.resolve;

import com.jujin.jeno.project.ProjectReader;
import com.jujin.jeno.project.Species;
import com.jujin.jeno.store.LockFileReader;
import com.jujin.jeno.store.LockedArtifact;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * jeno.lock's {@code scope} field: DEV rows are written as {@code scope = "dev"},
 * MAIN rows carry no scope at all — so every lockfile written before
 * {@code [dev-deps]} existed reads back unchanged.
 */
class DevScopeLockTest {

    private static final String SHA = "a".repeat(64);

    @Test
    void devRowsSerializeAndReadBack() {
        ResolvedGraph graph = new ResolvedGraph(List.of(
                artifact("com.example:main-lib", Scope.MAIN),
                artifact("com.example:test-kit", Scope.DEV)), List.of(), java.util.Map.of());

        String lock = LockFile.serialize(graph, url -> SHA);

        assertTrue(lock.contains("scope = \"dev\""), "the DEV row carries its scope");
        assertEquals(1, count(lock, "scope = \"dev\""),
                "only the DEV row is written with a scope — MAIN is the default");

        List<LockedArtifact> read = LockFileReader.read(lock);
        assertEquals(Scope.MAIN, byId(read, "com.example:main-lib").scope());
        assertEquals(Scope.DEV, byId(read, "com.example:test-kit").scope());
        assertTrue(byId(read, "com.example:main-lib").isMain());
        assertFalse(byId(read, "com.example:test-kit").isMain());
    }

    @Test
    void aPreScopeLockfileReadsAsAllMain() {
        // the shape every lockfile had before [dev-deps]: no scope key anywhere
        List<LockedArtifact> read = LockFileReader.read("""
            [[artifacts]]
            id = "com.example:lib-a"
            version = "1.0.0"
            source = "central"
            url = "https://repo.maven.apache.org/maven2/com/example/lib-a/1.0.0/lib-a-1.0.0.jar"
            sha256 = "%s"
            meta-sha256 = "%s"
            depth = 1
            """.formatted(SHA, SHA));

        assertEquals(Scope.MAIN, read.getFirst().scope(),
                "an absent scope is main — old lockfiles stay valid");
    }

    @Test
    void anUnknownScopeIsRejected() {
        assertThrows(RuntimeException.class, () -> LockFileReader.read("""
            [[artifacts]]
            id = "com.example:lib-a"
            version = "1.0.0"
            source = "central"
            url = "https://repo.maven.apache.org/maven2/com/example/lib-a/1.0.0/lib-a-1.0.0.jar"
            sha256 = "%s"
            depth = 1
            scope = "compile"
            """.formatted(SHA)), "scope is either absent or the string \"dev\"");
    }

    @Test
    void compileArtifactsSkipDevDeclarations() {
        // the strict-visibility split: direct DEV artifacts are not compile inputs
        ResolvedGraph graph = new ResolvedGraph(List.of(
                artifact("com.example:main-lib", Scope.MAIN),
                artifact("com.example:test-kit", Scope.DEV)), List.of(
                declared("com.example:main-lib", Scope.MAIN),
                declared("com.example:test-kit", Scope.DEV)), java.util.Map.of());

        assertEquals(List.of("com.example:main-lib"),
                graph.compileArtifacts().stream()
                        .map(ResolvedGraph.ResolvedArtifact::id).toList(),
                "only MAIN declarations feed the compile classpath");
        assertEquals(List.of("com.example:main-lib"),
                graph.mainArtifacts().stream()
                        .map(ResolvedGraph.ResolvedArtifact::id).toList(),
                "only MAIN artifacts feed the runtime closure");
    }

    @Test
    void projectParsesDevDepsThroughThePublicFacade() {
        var project = ProjectReader.parse("""
            [project]
            name = "demo"
            version = "0.1.0"

            [deps]
            "com.example:lib-a" = "1.0.0"

            [dev-deps]
            "com.example:test-kit" = "2.0.0"
            """);
        assertEquals(1, project.requirements().size());
        assertEquals(1, project.devRequirements().size());
    }

    private static ResolvedGraph.ResolvedArtifact artifact(String id, Scope scope) {
        return new ResolvedGraph.ResolvedArtifact(id, "1.0.0", Species.CENTRAL, "central",
                "https://repo.maven.apache.org/maven2/x.jar", null, 1, false, scope);
    }

    private static ResolvedGraph.ResolvedArtifact declared(String id, Scope scope) {
        return new ResolvedGraph.ResolvedArtifact(id, "1.0.0", Species.CENTRAL, "central",
                "https://repo.maven.apache.org/maven2/x.jar", null, 1, true, scope);
    }

    private static LockedArtifact byId(List<LockedArtifact> artifacts, String id) {
        return artifacts.stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) {
            count++;
        }
        return count;
    }
}
