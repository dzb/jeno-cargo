package com.jujin.mjm.project;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for mjm.toml's closed schema (SPEC.md §2): every accepted
 * shape parses into typed requirements, and every rejected shape fails with
 * the rule named.
 */
class ProjectReaderTest {

    @Test
    void parsesEverySectionWithDefaults() {
        MjProject project = ProjectReader.parse("""
            [project]
            name = "demo"
            version = "0.1.0"

            [java]
            version = 21

            [deps]
            "com.example:lib-a" = "1.0.0"

            [scripts]
            dev = "mj run app.Main"
            """);

        assertEquals("demo", project.name());
        assertEquals("0.1.0", project.version());
        assertEquals(21, project.javaVersion());
        assertEquals(2, project.maxDepth(), "the depth budget defaults to 2");
        assertEquals(1, project.requirements().size());
        assertEquals("dev", project.scripts().keySet().iterator().next());
        assertEquals(Map.of(), project.overrides());
    }

    @Test
    void rejectsUnknownSectionsAndNonTables() {
        ProjectException unknown = assertThrows(ProjectException.class,
            () -> ProjectReader.parse("""
                [project]
                name = "demo"
                version = "0.1.0"

                [dep]
                "com.example:lib-a" = "1.0.0"
                """));
        assertTrue(unknown.getMessage().contains("unknown section [dep]"),
                "a misspelled section must be named, got: " + unknown.getMessage());
        assertTrue(unknown.getMessage().contains("mjm.toml:5:1"),
                "the section's line and column are named, got: " + unknown.getMessage());

        ProjectException notATable = assertThrows(ProjectException.class,
            () -> ProjectReader.parse("""
                project = "demo"
                """));
        assertTrue(notATable.getMessage().contains("must be a table"),
                "got: " + notATable.getMessage());
    }

    @Test
    void anUnknownKeyFailsWithItsLineColumnAndTheNearestKey() {
        ProjectException error = assertThrows(ProjectException.class,
            () -> ProjectReader.parse("""
                [project]
                name = "demo"
                version = "0.1.0"
                mian = "com.example.Main"
                """));

        assertTrue(error.getMessage().contains("mjm.toml:4:1"),
                "the key's line and column are named, got: " + error.getMessage());
        assertTrue(error.getMessage().contains("[project].mian is not a key"),
                "the key and its section are named, got: " + error.getMessage());
        assertTrue(error.getMessage().contains("did you mean 'main'?"),
                "a typo gets the key it meant, got: " + error.getMessage());
        assertTrue(error.getMessage().contains("allowed: "),
                "and the keys that are legal there, got: " + error.getMessage());
    }

    @Test
    void aValueCanNameItsRepository() {
        MjProject project = ProjectReader.parse("""
            [project]
            name = "demo"
            version = "0.1.0"

            [repos]
            corp = "https://nexus.corp.internal/repository/maven/"

            [deps]
            "com.corp:lib" = "corp:2.1.0"
            "com.example:plain" = "1.2.3"
            """);

        Requirement named = project.requirements().get(0);
        assertEquals(Species.CENTRAL, named.species());
        assertEquals("corp", named.repository());
        assertEquals("2.1.0", named.version());
        assertEquals("com.corp:lib", named.id());

        assertEquals(null, project.requirements().get(1).repository(),
                "a bare version is still Central");
    }

    @Test
    void theSpeciesPrefixesAreReservedRepositoryNames() {
        ProjectException gh = assertThrows(ProjectException.class,
            () -> ProjectReader.parse("""
                [project]
                name = "demo"
                version = "0.1.0"

                [repos]
                gh = "https://mirror.example.com/gh/"

                [deps]
                "com.example:lib" = "1.0.0"
                """));
        assertTrue(gh.getMessage().contains("[repos].gh is reserved for the gh: species"),
                gh.getMessage());
        assertTrue(gh.getMessage().contains("mjm.toml:6:1"),
                "the position is named like every other schema error: " + gh.getMessage());

    }

    @Test
    void theRemovedGiteeSpeciesExplainsItsTwoReplacements() {
        ProjectException gitee = assertThrows(ProjectException.class,
            () -> ProjectReader.parse("""
                [project]
                name = "demo"
                version = "0.1.0"

                [deps]
                other = "gitee:acme/other@1.2.3-rc1"
                """));

        String message = gitee.getMessage();
        assertTrue(message.contains("gitee: species was removed"), message);
        assertTrue(message.contains("releases/download"), "names the release jar URL: " + message);
        assertTrue(message.contains("[repos]"), "and the published-host route: " + message);
    }

    @Test
    void aDeclaredRepositoryWinsOnlyWhenNoSpeciesPrefixMatches() {
        // corp: has no species meaning, so the declared repository is what it is
        MjProject project = ProjectReader.parse("""
            [project]
            name = "demo"
            version = "0.1.0"

            [repos]
            corp = "https://nexus.corp.internal/repository/maven/"

            [deps]
            "com.corp:lib" = "corp:2.0.0"
            "mylib" = "gh:acme/mylib@v1.0.0"
            """);

        assertEquals("corp", project.requirements().get(0).repository());
        assertEquals(Species.GH, project.requirements().get(1).species(),
                "a gh: value stays the gh: species even with a corp repo declared");
    }

    @Test
    void aMjmRepositoryHoldsNativeReleasesByName() {
        MjProject project = ProjectReader.parse("""
            [project]
            name = "demo"
            version = "0.1.0"

            [repos]
            mine = "mjm:https://repo.example/"

            [deps]
            "widget" = "mine:1.2.3"

            [deps.mine]
            "gadget" = "0.9.0"
            """);

        Requirement widget = project.requirements().get(0);
        assertEquals(Species.NAMED, widget.species());
        assertEquals("mine", widget.repository());
        assertEquals("1.2.3", widget.version());
        assertEquals("widget", widget.id());

        Requirement gadget = project.requirements().get(1);
        assertEquals(Species.NAMED, gadget.species());
        assertEquals("gadget", gadget.id(), "the table form is addressed by name too");
    }

    @Test
    void aMjmRepositoryDependencyIsAddressedByArtifactName() {
        ProjectException error = assertThrows(ProjectException.class,
            () -> ProjectReader.parse("""
                [project]
                name = "demo"
                version = "0.1.0"

                [repos]
                mine = "mjm:https://repo.example/"

                [deps]
                "com.corp:lib" = "mine:1.0.0"
                """));
        assertTrue(error.getMessage().contains("addressed by its artifact name"),
                error.getMessage());
    }

    @Test
    void aMjmRepositoryIsHttpsOnly() {
        assertRejects("""
            [project]
            name = "demo"
            version = "0.1.0"

            [repos]
            mine = "mjm:local:/tmp/repo"
            """, "mjm: repository is https-only");

        assertRejects("""
            [project]
            name = "demo"
            version = "0.1.0"

            [repos]
            mine = "mjm:http://repo.example/"
            """, "mjm: repository is https-only");
    }

    @Test
    void aValueNamingAnUndeclaredRepositoryIsAnErrorListingTheDeclaredOnes() {
        ProjectException error = assertThrows(ProjectException.class,
            () -> ProjectReader.parse("""
                [project]
                name = "demo"
                version = "0.1.0"

                [repos]
                corp = "https://nexus.corp.internal/repository/maven/"

                [deps]
                "com.other:lib" = "other:1.0.0"
                """));

        assertTrue(error.getMessage().contains("'other'"), error.getMessage());
        assertTrue(error.getMessage().contains("declared: corp"),
                "the message lists what is declared: " + error.getMessage());
    }

    @Test
    void theTwoSpellingsOfOneDependencyCollide() {
        ProjectException error = assertThrows(ProjectException.class,
            () -> ProjectReader.parse("""
                [project]
                name = "demo"
                version = "0.1.0"

                [repos]
                corp = "https://nexus.corp.internal/repository/maven/"

                [deps]
                "com.corp:lib" = "corp:2.1.0"

                [deps.corp]
                "com.corp:lib" = "2.1.0"
                """));

        assertTrue(error.getMessage().contains("declared twice"), error.getMessage());
    }

    @Test
    void quotedKeysAndSectionsStillCarryTheirPosition() {
        // both are legal TOML; the scanner must not read the '=' or ']' inside
        // the quotes as the separator and lose the coordinates
        ProjectException quotedKey = assertThrows(ProjectException.class,
            () -> ProjectReader.parse("""
                [project]
                name = "demo"
                version = "0.1.0"
                "mi=an" = 1
                """));
        assertTrue(quotedKey.getMessage().contains("mjm.toml:4:1"),
                "got: " + quotedKey.getMessage());
        assertTrue(quotedKey.getMessage().contains("[project].mi=an is not a key"),
                "the key is reported as it was written, got: " + quotedKey.getMessage());

        ProjectException quotedSection = assertThrows(ProjectException.class,
            () -> ProjectReader.parse("""
                [project]
                name = "demo"
                version = "0.1.0"

                ["dep"]
                "com.example:lib-a" = "1.0.0"
                """));
        assertTrue(quotedSection.getMessage().contains("mjm.toml:5:1"),
                "got: " + quotedSection.getMessage());
        assertTrue(quotedSection.getMessage().contains("unknown section [dep]"),
                "got: " + quotedSection.getMessage());
    }

    @Test
    void everyDocumentedKeyIsAccepted() {
        MjProject project = ProjectReader.parse("""
            [project]
            name = "demo"
            group = "com.example"
            version = "0.1.0"
            main = "com.example.Main"
            summary = "a demo"
            homepage = "https://example.com/demo"
            license = "Apache-2.0"

            [java]
            version = 21

            [limits]
            max-depth = 3

            [build]
            parameters = ["-parameters"]
            processors = ["com.example:proc:1.0.0"]

            [test]
            include = ["*Test"]
            exclude = ["*Slow*"]
            """);

        assertEquals("com.example.Main", project.main());
        assertEquals("https://example.com/demo", project.homepage());
        assertEquals(3, project.maxDepth());
        assertEquals(List.of("-parameters"), project.buildParameters());
        assertEquals(1, project.processorRequirements().size(),
                "the processor coordinate resolves as its own closure");
        assertEquals(List.of("*Test"), project.testInclude());
        assertEquals(List.of("*Slow*"), project.testExclude());
    }

    @Test
    void theTestSectionIsTypedAndClosed() {
        ProjectException wrongType = assertThrows(ProjectException.class,
            () -> ProjectReader.parse("""
                [project]
                name = "demo"
                version = "0.1.0"

                [test]
                include = "x"
                """));
        assertTrue(wrongType.getMessage().contains("mjm.toml:6:1"),
                "the wrong-typed key names its line and column, got: "
                        + wrongType.getMessage());
        assertTrue(wrongType.getMessage().contains("[test].include must be a string array"),
                "got: " + wrongType.getMessage());

        assertRejects("""
            [project]
            name = "demo"
            version = "0.1.0"

            [test]
            bogus = 1
            """, "[test].bogus is not a key");

        assertRejects("""
            [project]
            name = "demo"
            version = "0.1.0"

            [limits]
            max-depth = 2
            bogus = 1
            """, "[limits].bogus is not a key");

        assertRejects("""
            [project]
            name = "demo"
            version = "0.1.0"

            [java]
            version = 21
            bogus = 1
            """, "[java].bogus is not a key");

        assertRejects("""
            [project]
            name = "demo"
            version = "0.1.0"

            [build]
            parameters = ["-parameters"]
            bogus = 1
            """, "[build].bogus is not a key");
    }

    @Test
    void anEmptySectionFallsBackToItsDefault() {
        // SPEC.md §2.1: max-depth defaults to 2 and [java].version is optional —
        // an emptied section must not force a value.
        MjProject project = ProjectReader.parse("""
            [project]
            name = "demo"
            version = "0.1.0"

            [limits]

            [java]
            """);

        assertEquals(2, project.maxDepth(), "an empty [limits] means the default budget");
        assertNull(project.javaVersion(), "an empty [java] means no target");
    }

    @Test
    void classifiesValueShapesIntoSpecies() {
        MjProject project = ProjectReader.parse("""
            [project]
            name = "demo"
            version = "0.1.0"

            [repos]
            corp = "https://nexus.corp.internal/repository/maven/"

            [deps]
            "com.example:lib-a" = "1.0.0"
            mylib = "gh:acme/mylib@v1.2.0"
            other = "gh:acme/other@1.2.3-rc1"
            vendored = "https://cdn.example.com/vendored-0.9.jar"

            [deps.corp]
            "com.corp:internal" = "2.1.0"
            """);

        assertEquals(5, project.requirements().size());
        Requirement central = project.requirements().get(0);
        assertEquals(Species.CENTRAL, central.species());
        assertEquals("com.example", central.group());
        assertEquals("lib-a", central.artifactId());
        assertEquals("1.0.0", central.version());

        Requirement gh = project.requirements().get(1);
        assertEquals(Species.GH, gh.species());
        assertEquals("acme", gh.owner());
        assertEquals("mylib", gh.repo());
        assertEquals("v1.2.0", gh.tag());
        assertEquals("1.2.0", gh.version(), "the version drops the tag's v");

        Requirement ghPrerelease = project.requirements().get(2);
        assertEquals(Species.GH, ghPrerelease.species());
        assertEquals("1.2.3-rc1", ghPrerelease.version(), "a prerelease tag keeps its suffix");

        Requirement url = project.requirements().get(3);
        assertEquals(Species.URL, url.species());

        Requirement corp = project.requirements().stream()
                .filter(r -> r.repository() != null)
                .findFirst()
                .orElseThrow();
        assertEquals("corp", corp.repository(), "the [deps.corp] group carries its repository");
        assertEquals("2.1.0", corp.version());
    }

    @Test
    void rejectsCentralVersionShapes() {
        assertRejects("""
            [project]
            name = "demo"
            version = "0.1.0"

            [deps]
            "com.example:lib-a" = "[1.0,2.0)"
            """, "version ranges are not supported");

        assertRejects("""
            [project]
            name = "demo"
            version = "0.1.0"

            [deps]
            "com.example:lib-a" = "1.0.0-SNAPSHOT"
            """, "snapshot versions are not supported");

        assertRejects("""
            [project]
            name = "demo"
            version = "0.1.0"

            [deps]
            "com.example:lib-a" = "${jackson.version}"
            """, "versions are literal");

        assertRejects("""
            [project]
            name = "demo"
            version = "0.1.0"

            [deps]
            "com.example:lib-a" = 1
            """, "must be a quoted string");
    }

    @Test
    void aCentralDependencyKeyMustBeGroupArtifact() {
        ProjectException ex = assertThrows(ProjectException.class,
            () -> ProjectReader.parse("""
                [project]
                name = "demo"
                version = "0.1.0"

                [deps]
                "just-a-name" = "1.0.0"
                """));
        assertTrue(ex.getMessage().contains("group:artifact"), "got: " + ex.getMessage());
    }

    @Test
    void rejectsMalformedNativeShorthands() {
        assertRejects("""
            [project]
            name = "demo"
            version = "0.1.0"

            [deps]
            mylib = "gh:acme/mylib@latest"
            """, "optional v plus semver");

        assertRejects("""
            [project]
            name = "demo"
            version = "0.1.0"

            [deps]
            mylib = "gh:acme/mylib"
            """, "must be in the form");

        assertRejects("""
            [project]
            name = "demo"
            version = "0.1.0"

            [deps]
            vendored = "https://cdn.example.com/vendored-0.9.tgz"
            """, "must end in .jar");
    }

    @Test
    void aRepoGroupMustNameADeclaredRepository() {
        ProjectException ex = assertThrows(ProjectException.class,
            () -> ProjectReader.parse("""
                [project]
                name = "demo"
                version = "0.1.0"

                [deps.corp]
                "com.corp:internal" = "2.1.0"
                """));
        assertTrue(ex.getMessage().contains("not declared in [repos]"), "got: " + ex.getMessage());

        ProjectException https = assertThrows(ProjectException.class,
            () -> ProjectReader.parse("""
                [project]
                name = "demo"
                version = "0.1.0"

                [repos]
                corp = "http://nexus.corp.internal/repository/maven/"

                [deps.corp]
                "com.corp:internal" = "2.1.0"
                """));
        assertTrue(https.getMessage().contains("https"), "got: " + https.getMessage());
    }

    @Test
    void anIdMayAppearOnceAcrossAllDepsSections() {
        ProjectException ex = assertThrows(ProjectException.class,
            () -> ProjectReader.parse("""
                [project]
                name = "demo"
                version = "0.1.0"

                [repos]
                corp = "https://nexus.corp.internal/repository/maven/"

                [deps]
                "com.example:lib-a" = "1.0.0"

                [deps.corp]
                "com.example:lib-a" = "1.0.0"
                """));
        assertTrue(ex.getMessage().contains("declared twice"), "got: " + ex.getMessage());
    }

    @Test
    void overridesRejectUrlDependenciesAndNonStrings() {
        ProjectException url = assertThrows(ProjectException.class,
            () -> ProjectReader.parse("""
                [project]
                name = "demo"
                version = "0.1.0"

                [deps]
                vendored = "https://cdn.example.com/vendored-0.9.jar"

                [overrides]
                vendored = "1.0.0"
                """));
        assertTrue(url.getMessage().contains("no version to override"), "got: " + url.getMessage());
    }

    @Test
    void theDepthBudgetIsBoundedOneTo255() {
        assertRejects("""
            [project]
            name = "demo"
            version = "0.1.0"

            [limits]
            max-depth = 0
            """, "between 1 and 255");

        assertRejects("""
            [project]
            name = "demo"
            version = "0.1.0"

            [limits]
            max-depth = 256
            """, "between 1 and 255");

        assertRejects("""
            [project]
            name = "demo"
            version = "0.1.0"

            [limits]
            max-depth = "two"
            """, "must be an integer");
    }

    @Test
    void projectNameFollowsTheArtifactRules() {
        assertRejects("""
            [project]
            name = "Demo"
            version = "0.1.0"
            """, "must match [a-z][a-z0-9-]*");

        assertRejects("""
            [project]
            version = "0.1.0"
            """, "[project].name must be a non-blank string");
    }

    private static void assertRejects(String toml, String expectedMessagePart) {
        ProjectException ex = assertThrows(ProjectException.class, () -> ProjectReader.parse(toml));
        assertTrue(ex.getMessage().contains(expectedMessagePart),
                "expected '" + expectedMessagePart + "', got: " + ex.getMessage());
    }
}
