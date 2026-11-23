package com.jujin.jeno.cli;

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
 * The CLI is called {@code jeno}. Its sources used to say {@code mj} in comments,
 * javadoc and user-facing messages — a second name for the same tool, and in
 * one place the wrong recovery path ({@code ~/.mj/store}). This scan keeps the
 * sweep from growing back.
 *
 * <p>No exception list remains: the old short term has no legitimate use.
 * The guard file itself is skipped by its own scan so it can keep the pattern
 * and the explanatory text.
 *
 * <p>The second check is narrower and stricter: the system properties the tool
 * reads are {@code -Djeno.*}, and a document naming the short form (dash, D, the
 * two letters, a dot) sends the user to a flag nothing looks at.
 */
class NamingTest {

    private static final List<String> ALLOWED = List.of();

    private static final Pattern BARE_MJ = Pattern.compile("\\bmj\\b");

    /**
     * A system property is spelled {@code -Djeno.<name>}; the short form is
     * exactly the wrong key, and a user who copies it from a document gets a
     * silently ignored flag. Built from two pieces so this guard never trips
     * its own grep.
     */
    private static final String WRONG_PROPERTY = "-D" + "mj.";

    @Test
    void everySourceSaysJeno() throws IOException {
        Path root = sourceRoot();
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals("NamingTest.java"))
                    .toList()) {
                check(file, violations);
            }
        }
        assertTrue(violations.isEmpty(),
                "bare `mj` left in the sources (write `jeno`, or add the term to ALLOWED):\n  "
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

    @Test
    void noSourceOrDocumentNamesAnMjSystemProperty() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot())) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals("NamingTest.java"))
                    .toList()) {
                checkProperty(file, violations);
            }
        }
        Path root = projectRoot();
        for (String document : List.of("DESIGN.md", "SPEC.md", "README.md")) {
            Path file = root.resolve(document);
            if (Files.isRegularFile(file)) {
                checkProperty(file, violations);
            }
        }
        assertTrue(violations.isEmpty(),
                "a system property is -Djeno.<name>; the short two-letter form is ignored "
                        + "at runtime:\n  " + String.join("\n  ", violations));
    }

    private static void checkProperty(Path file, List<String> violations) throws IOException {
        List<String> lines = Files.readAllLines(file);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(WRONG_PROPERTY)) {
                violations.add(file + ":" + (i + 1) + ": " + lines.get(i).strip());
            }
        }
    }

    /** The project root above {@code src/main/java}. */
    private static Path projectRoot() {
        return sourceRoot().getParent().getParent().getParent();
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
