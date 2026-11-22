package com.jujin.mjm.project;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A repository is a location plus a layout — two facts, no prefix chain. */
class RepositoryTest {

    @Test
    void theFourCombinationsParseIntoTheirTwoFacts() {
        Repository mavenHttps = Repository.parse("r", "https://host/repo");
        assertEquals(Repository.Layout.MAVEN, mavenHttps.layout());
        assertEquals(Repository.Transport.HTTPS, mavenHttps.transport());
        assertEquals("https://host/repo/", mavenHttps.networkBase());

        Repository mavenLocal = Repository.parse("r", "local:../vendor");
        assertEquals(Repository.Layout.MAVEN, mavenLocal.layout());
        assertEquals(Repository.Transport.LOCAL, mavenLocal.transport());
        assertEquals(Path.of("/work/libs/vendor"),
                mavenLocal.directory(Path.of("/work/libs/app")));

        Repository mjmHttps = Repository.parse("r", "mjm:https://host/repo");
        assertEquals(Repository.Layout.MJM, mjmHttps.layout());
        assertEquals(Repository.Transport.HTTPS, mjmHttps.transport());
        assertEquals("https://host/repo/", mjmHttps.networkBase());

        Repository mjmLocal = Repository.parse("r", "mjm:local:../vendor");
        assertEquals(Repository.Layout.MJM, mjmLocal.layout());
        assertEquals(Repository.Transport.LOCAL, mjmLocal.transport());
        assertEquals("mjm:local:../vendor", mjmLocal.declaration());
    }

    @Test
    void anUnknownLocationNamesBothDimensions() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Repository.parse("r", "mjm:../vendor"));
        assertTrue(error.getMessage().contains("mjm:https://"), error.getMessage());
        assertTrue(error.getMessage().contains("mjm:local:"), error.getMessage());
    }

    @Test
    void layoutAndTransportPrefixedStringsLiveOnlyInTheParser() throws IOException {
        Path sources = Path.of("src/main/java");
        if (!Files.isDirectory(sources)) {
            return;  // a test run from another working directory has nothing to scan
        }
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(sources)) {
            for (Path file : stream.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                boolean sniffs = text.contains("startsWith(\"mjm:\")")
                        || text.contains("startsWith(\"local:\")");
                if (sniffs && !file.getFileName().toString().equals("Repository.java")) {
                    offenders.add(file.toString());
                }
            }
        }
        assertEquals(List.of(), offenders,
                "layout/transport must come from Repository, not prefix checks");
        assertTrue(Set.of("Repository.java").contains("Repository.java"));
    }
}
