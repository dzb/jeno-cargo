package com.jujin.jeno.cli;

import com.jujin.jeno.project.Repository;
import com.jujin.jeno.resolve.ArtifactLocations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JenoIndexTest {

    @TempDir
    Path dir;

    @AfterEach
    void resetProjectRoot() {
        ArtifactLocations.useProjectRoot(Path.of("").toAbsolutePath());
    }

    @Test
    void parsesAndSortsVersions() {
        JenoIndex.Index index = JenoIndex.parse("""
            schemaVersion = 1
            name = "widget"
            versions = ["1.2.0", "1.0.0", "1.1.0"]
            """, "widget", "memory");

        assertEquals(List.of("1.0.0", "1.1.0", "1.2.0"), index.versions());
        assertEquals("""
            schemaVersion = 1
            name = "widget"
            versions = ["1.0.0", "1.1.0", "1.2.0"]
            """, JenoIndex.render(index));
    }

    @Test
    void localUpdateIsReadableAndIdempotent() {
        Repository repository = Repository.parse("mine",
                "jeno:local:" + dir.resolve("repo"));
        JenoIndex.update(repository, "widget", "1.0.0");
        JenoIndex.update(repository, "widget", "1.2.0");
        JenoIndex.update(repository, "widget", "1.2.0");

        JenoIndex.Index index = JenoIndex.read(repository, "widget");
        assertEquals(List.of("1.0.0", "1.2.0"), index.versions());
        assertTrue(Files.isRegularFile(
                dir.resolve("repo/widget/index.jeno.toml")));
    }

    @Test
    void missingAndMalformedIndexesHaveDistinctOutcomes() {
        Repository repository = Repository.parse("mine",
                "jeno:local:" + dir.resolve("repo"));
        assertThrows(JenoIndex.IndexUnavailable.class,
                () -> JenoIndex.read(repository, "widget"));

        Path index = dir.resolve("repo/widget/index.jeno.toml");
        try {
            Files.createDirectories(index.getParent());
            Files.writeString(index, "schemaVersion = 1\nname = \"widget\"\nversions = [\"bad\"]\n");
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        assertThrows(JenoIndex.IndexException.class,
                () -> JenoIndex.read(repository, "widget"));
    }

    @Test
    void artifactNamesCannotEscapeTheRepositoryPath() {
        Repository repository = Repository.parse("mine",
                "jeno:local:" + dir.resolve("repo"));
        assertThrows(JenoIndex.IndexException.class,
                () -> JenoIndex.read(repository, "../widget"));
    }
}
