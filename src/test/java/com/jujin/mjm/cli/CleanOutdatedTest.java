package com.jujin.mjm.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code mjm clean} and {@code mjm outdated} — the two commands that close the
 * "housekeeping" gap. Neither is clever; what matters is what they refuse to
 * touch and how they classify what they cannot check.
 */
class CleanOutdatedTest {

    @TempDir
    Path dir;

    // ------------------------------------------------------------------
    // clean
    // ------------------------------------------------------------------

    @Test
    void cleanRemovesTargetAndDist() throws Exception {
        Files.createDirectories(dir.resolve("target/classes/demo"));
        Files.createDirectories(dir.resolve("dist/demo-1.0.0/lib"));
        Files.writeString(dir.resolve("target/classes/demo/A.class"), "x");
        Files.writeString(dir.resolve("dist/demo-1.0.0.tar.gz"), "y");

        assertEquals(0, new CleanCommand(dir).execute(List.of()));

        assertFalse(Files.exists(dir.resolve("target")), "target/ is gone");
        assertFalse(Files.exists(dir.resolve("dist")), "dist/ is gone");
    }

    @Test
    void cleanTargetKeepsDeliverables() throws Exception {
        Files.createDirectories(dir.resolve("target/classes"));
        Files.createDirectories(dir.resolve("dist"));
        Files.writeString(dir.resolve("dist/demo-1.0.0.jar"), "ship me");

        assertEquals(0, new CleanCommand(dir).execute(List.of("--target")));

        assertFalse(Files.exists(dir.resolve("target")));
        assertTrue(Files.exists(dir.resolve("dist/demo-1.0.0.jar")),
                "--target exists so a build can be cleaned without losing what you ship");
    }

    @Test
    void cleanDistKeepsTheBuild() throws Exception {
        Files.createDirectories(dir.resolve("target/classes"));
        Files.createDirectories(dir.resolve("dist"));

        assertEquals(0, new CleanCommand(dir).execute(List.of("--dist")));

        assertTrue(Files.isDirectory(dir.resolve("target/classes")));
        assertFalse(Files.exists(dir.resolve("dist")));
    }

    @Test
    void cleanSaysWhenThereIsNothingToDo() throws Exception {
        java.io.PrintStream original = System.out;
        var buffer = new java.io.ByteArrayOutputStream();
        try {
            System.setOut(new java.io.PrintStream(buffer, true,
                    java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(0, new CleanCommand(dir).execute(List.of()));
        } finally {
            System.setOut(original);
        }
        assertTrue(buffer.toString(java.nio.charset.StandardCharsets.UTF_8)
                        .contains("nothing to clean"),
                "saying so beats pretending work happened");
    }

    @Test
    void cleanRejectsUnknownOptions() {
        assertEquals(2, new CleanCommand(dir).execute(List.of("--everything")));
    }

    // ------------------------------------------------------------------
    // outdated: version selection
    // ------------------------------------------------------------------

    @Test
    void aStablePinNeverSeesAPrereleaseAsAnUpdate() {
        List<String> available = List.of("2.0.0", "2.0.20", "2.1.0-alpha1", "2.1.0");
        assertEquals("2.1.0", OutdatedCommand.newest(available, false));
    }

    @Test
    void aPrereleasePinMaySeePrereleases() {
        List<String> available = List.of("2.0.0", "2.1.0-alpha1");
        assertEquals("2.1.0-alpha1", OutdatedCommand.newest(available, true));
        assertEquals("2.0.0", OutdatedCommand.newest(available, false));
    }

    @Test
    void snapshotsAreNeverCandidates() {
        List<String> available = List.of("2.0.0", "2.5.0-SNAPSHOT");
        assertEquals("2.0.0", OutdatedCommand.newest(available, true),
                "mjm refuses snapshots, so it must not offer one");
    }

    @Test
    void aDashMarksAPreRelease() {
        assertTrue(OutdatedCommand.allowsPrerelease("2.0.0-rc1"));
        assertTrue(OutdatedCommand.allowsPrerelease("2.0.0-alpha1"));
        assertFalse(OutdatedCommand.allowsPrerelease("2.0.0"));
        assertFalse(OutdatedCommand.allowsPrerelease("30.0-jre"),
                "a qualifier like -jre is not a pre-release marker");
    }
}
