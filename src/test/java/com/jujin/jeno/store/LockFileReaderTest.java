package com.jujin.jeno.store;

import com.jujin.jeno.resolve.ResolvedGraph;
import com.jujin.jeno.project.Species;
import com.jujin.jeno.resolve.LockFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * jeno.lock's round trip: what the resolver serializes, the reader returns —
 * and anything a hand slipped in is rejected.
 */
class LockFileReaderTest {

    private static ResolvedGraph.ResolvedArtifact artifact(String id, String version) {
        return new ResolvedGraph.ResolvedArtifact(id, version, Species.CENTRAL, "central",
                "https://repo.maven.apache.org/maven2/" + id.replace('.', '/') + "/" + version
                        + "/" + id.substring(id.indexOf(':') + 1) + "-" + version + ".jar",
                "https://repo.maven.apache.org/maven2/" + id.replace('.', '/') + "/" + version
                        + "/" + id.substring(id.indexOf(':') + 1) + "-" + version + ".pom",
                1, true);
    }

    @Test
    void whatTheResolverWritesTheReaderReturns() {
        ResolvedGraph graph = new ResolvedGraph(
                List.of(artifact("com.example:lib-a", "1.0.0"),
                        artifact("org.example:lib-b", "2.0.0")),
                List.of(artifact("com.example:lib-a", "1.0.0")),
                java.util.Map.of());
        String sha = "a".repeat(64);
        String metaSha = "b".repeat(64);
        String lock = LockFile.serialize(graph, url -> url.contains("lib-b") ? sha : metaSha);

        List<LockedArtifact> rows = LockFileReader.read(lock);

        assertEquals(2, rows.size());
        assertEquals("com.example:lib-a", rows.get(0).id());
        assertEquals(metaSha, rows.get(0).metaSha256(), "each digest lands on its own row");
        assertEquals(sha, rows.get(1).metaSha256());
        assertEquals(1, rows.get(0).depth());
    }

    @Test
    void aUrlLeafReadsBackWithoutMetaSha() {
        ResolvedGraph graph = new ResolvedGraph(
                List.of(new ResolvedGraph.ResolvedArtifact("vendored", "0.9", Species.URL, "url",
                        "https://cdn.example.com/vendored-0.9.jar", null, 1, true)),
                List.of(), java.util.Map.of());
        String lock = LockFile.serialize(graph, url -> "c".repeat(64));

        List<LockedArtifact> rows = LockFileReader.read(lock);
        assertEquals(1, rows.size());
        assertNull(rows.getFirst().metaSha256(), "a leaf has no metadata to pin");
    }

    @Test
    void unknownFieldsAndBadDigestsAreRejected() {
        String lock = """
            [[artifacts]]
            id = "com.example:lib-a"
            version = "1.0.0"
            source = "central"
            url = "https://repo.maven.apache.org/maven2/com/example/lib-a/1.0.0/lib-a-1.0.0.jar"
            sha256 = "%s"
            depth = 1
            extra = "surprise"
            """.formatted("a".repeat(64));

        StoreException ex = assertThrows(StoreException.class, () -> LockFileReader.read(lock));
        assertTrue(ex.getMessage().contains("unknown field 'extra'"), "got: " + ex.getMessage());
    }

    @Test
    void aShortDigestIsRejected() {
        String lock = """
            [[artifacts]]
            id = "com.example:lib-a"
            version = "1.0.0"
            source = "central"
            url = "https://repo.maven.apache.org/maven2/com/example/lib-a/1.0.0/lib-a-1.0.0.jar"
            sha256 = "%s"
            depth = 1
            """.formatted("a".repeat(31));

        StoreException bad = assertThrows(StoreException.class, () -> LockFileReader.read(lock));
        assertTrue(bad.getMessage().contains("64 hex characters"), "got: " + bad.getMessage());
    }

    @Test
    void aDuplicateIdIsRejected() {
        String row = """
            [[artifacts]]
            id = "com.example:lib-a"
            version = "1.0.0"
            source = "central"
            url = "https://repo.maven.apache.org/maven2/com/example/lib-a/1.0.0/lib-a-1.0.0.jar"
            sha256 = "%s"
            depth = 1
            """.formatted("a".repeat(64));
        StoreException ex = assertThrows(StoreException.class,
                () -> LockFileReader.read(row + row));
        assertTrue(ex.getMessage().contains("appears twice"), "got: " + ex.getMessage());
    }

    @Test
    void unknownSectionsAreRejected() {
        StoreException ex = assertThrows(StoreException.class,
                () -> LockFileReader.read("[java]\nversion = 21\n"));
        assertTrue(ex.getMessage().contains("unknown section [java]"), "got: " + ex.getMessage());
    }
}
