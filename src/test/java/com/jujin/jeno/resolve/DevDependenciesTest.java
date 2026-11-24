package com.jujin.jeno.resolve;

import com.jujin.jeno.project.JenoProject;
import com.jujin.jeno.project.ProjectException;
import com.jujin.jeno.project.ProjectReader;
import com.jujin.jeno.project.Requirement;
import com.jujin.jeno.project.Species;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code [deps-dev]} — test-only requirements (Cargo's dev-dependencies).
 * They resolve into jeno.lock like any other requirement but carry
 * {@link Scope#DEV}: never compiled against, never run, never packaged,
 * never published — only {@code jeno test} sees them.
 */
class DevDependenciesTest {

    @Test
    void devDepsParseAsTheirOwnSection() {
        JenoProject project = ProjectReader.parse("""
            [project]
            name = "demo"
            version = "0.1.0"

            [deps]
            "com.example:lib-a" = "1.0.0"

            [deps-dev]
            "org.junit.jupiter:junit-jupiter-api" = "6.1.3"
            """);

        assertEquals(1, project.requirements().size());
        assertEquals(1, project.devRequirements().size());
        assertEquals("org.junit.jupiter:junit-jupiter-api",
                project.devRequirements().getFirst().id());
    }

    @Test
    void devDepsTakeEveryDeclarationShape() {
        JenoProject project = ProjectReader.parse("""
            [project]
            name = "demo"
            version = "0.1.0"

            [repos]
            corp = "https://repo.example.com/maven2/"

            [deps-dev]
            "com.example:lib-a" = "1.0.0"
            "harness" = "gh:acme/harness@v2.0.0"
            "fixture" = "https://example.com/fixture-1.0.jar"
            "com.corp:testkit" = "corp:3.1.0"
            """);

        List<Requirement> dev = project.devRequirements();
        assertEquals(4, dev.size());
        assertEquals(Species.CENTRAL, byId(dev, "com.example:lib-a").species());
        assertEquals(Species.GH, byId(dev, "harness").species());
        assertEquals(Species.URL, byId(dev, "fixture").species());
        assertEquals("corp", byId(dev, "com.corp:testkit").repository());
    }

    @Test
    void oneIdCannotLiveInBothScopes() {
        ProjectException error = assertThrows(ProjectException.class, () ->
                ProjectReader.parse("""
                    [project]
                    name = "demo"
                    version = "0.1.0"

                    [deps]
                    "com.example:lib-a" = "1.0.0"

                    [deps-dev]
                    "com.example:lib-a" = "1.0.0"
                    """));

        assertTrue(error.getMessage().contains("both [deps] and [deps-dev]"),
                "the error names both sections: " + error.getMessage());
    }

    @Test
    void devDepsRejectRepositoryGroupTables() {
        ProjectException error = assertThrows(ProjectException.class, () ->
                ProjectReader.parse("""
                    [project]
                    name = "demo"
                    version = "0.1.0"

                    [deps-dev.corp]
                    "com.corp:testkit" = "3.1.0"
                    """));

        assertTrue(error.getMessage().contains(
                        "repository-group dependency tables were removed"),
                error.getMessage());
    }

    @Test
    void theDevSectionIsClosedLikeEveryOther() {
        ProjectException error = assertThrows(ProjectException.class, () ->
                ProjectReader.parse("""
                    [project]
                    name = "demo"
                    version = "0.1.0"

                    [deps-dev]
                    "com.example:lib-a" = "1.0.0"
                    something-else = "x"
                    """));
        assertTrue(error.getMessage().contains("1.0.0")
                        || error.getMessage().contains("something-else"),
                "an unknown key is an error: " + error.getMessage());
    }

    private static Requirement byId(List<Requirement> requirements, String id) {
        return requirements.stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }
}
