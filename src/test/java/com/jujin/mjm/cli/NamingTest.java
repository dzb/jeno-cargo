package com.jujin.mjm.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CLI is called {@code mjm}. Its sources used to say {@code mj} in comments,
 * javadoc and user-facing messages — a second name for the same tool, and in
 * one place the wrong recovery path ({@code ~/.mj/store}). This scan keeps the
 * sweep from growing back.
 *
 * <p>Two kinds of occurrence are legitimate and listed below: the term
 * {@code mj-native} (a fixed species name, not an abbreviation of the tool) and
 * two internal temp-name prefixes that are never shown to a user.
 */
class NamingTest {

    private static final List<String> ALLOWED = List.of(
            "mj-native",    // the species name for gh:/gitee: releases — a term, not the CLI
            "mj-manifest-", // temp file prefix for the jar manifest
            "----mj");      // multipart boundary prefix in the upload request

    private static final Pattern BARE_MJ = Pattern.compile("\\bmj\\b");

    @Test
    void everySourceSaysMjm() throws IOException {
        Path root = sourceRoot();
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java")).toList()) {
                check(file, violations);
            }
        }
        assertTrue(violations.isEmpty(),
                "bare `mj` left in the sources (write `mjm`, or add the term to ALLOWED):\n  "
                        + String.join("\n  ", violations));
    }

    private static void check(Path file, List<String> violations) throws IOException {
        List<String> lines = Files.readAllLines(file);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            for (String allowed : ALLOWED) {
                line = line.replace(allowed, "");
            }
            Matcher matcher = BARE_MJ.matcher(line);
            if (matcher.find()) {
                violations.add(file + ":" + (i + 1) + ": " + lines.get(i).strip());
            }
        }
    }

    /** The test JVM may start in the project root or anywhere below it. */
    private static Path sourceRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("src/main/java");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("cannot find src/main/java from "
                + Path.of("").toAbsolutePath());
    }
}
