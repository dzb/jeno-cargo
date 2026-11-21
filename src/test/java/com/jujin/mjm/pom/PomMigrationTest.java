package com.jujin.mjm.pom;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code pom.xml} → {@code mjm.toml}: a translation a human reviews, never a
 * silent import. Test scope becomes {@code [dev-deps]}; properties are
 * resolved because mjm.toml has no interpolation; anything mj cannot express
 * is reported rather than dropped.
 */
class PomMigrationTest {

    @Test
    void coordinatesVersionsAndScopesTranslate() {
        PomMigration.Result result = PomMigration.translate("""
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>shop</artifactId>
              <version>1.2.0</version>
              <properties>
                <jackson.version>2.17.0</jackson.version>
              </properties>
              <dependencies>
                <dependency>
                  <groupId>com.fasterxml.jackson.core</groupId>
                  <artifactId>jackson-databind</artifactId>
                  <version>${jackson.version}</version>
                </dependency>
                <dependency>
                  <groupId>org.junit.jupiter</groupId>
                  <artifactId>junit-jupiter</artifactId>
                  <version>5.10.0</version>
                  <scope>test</scope>
                </dependency>
              </dependencies>
            </project>
            """);

        String toml = result.mjToml();
        assertTrue(toml.contains("name = \"shop\""), toml);
        assertTrue(toml.contains("version = \"1.2.0\""), toml);
        assertTrue(toml.contains("\"com.fasterxml.jackson.core:jackson-databind\" = \"2.17.0\""),
                "the property was resolved into a literal version: " + toml);
        assertTrue(toml.contains("[dev-deps]"), "test scope lands in [dev-deps]: " + toml);
        assertTrue(toml.contains("\"org.junit.jupiter:junit-jupiter\" = \"5.10.0\""), toml);
        assertTrue(result.notes().isEmpty(), "nothing to report: " + result.notes());
    }

    @Test
    void theOutputIsAValidProject() {
        PomMigration.Result result = PomMigration.translate("""
            <project>
              <groupId>com.example</groupId>
              <artifactId>demo</artifactId>
              <version>0.1.0</version>
              <dependencies>
                <dependency>
                  <groupId>org.slf4j</groupId>
                  <artifactId>slf4j-api</artifactId>
                  <version>2.0.18</version>
                </dependency>
              </dependencies>
            </project>
            """);

        var project = com.jujin.mjm.project.ProjectReader.parse(result.mjToml());
        assertEquals("demo", project.name());
        assertEquals(1, project.requirements().size());
        assertTrue(project.devRequirements().isEmpty());
    }

    @Test
    void dependencyManagementSuppliesMissingVersions() {
        PomMigration.Result result = PomMigration.translate("""
            <project>
              <groupId>com.example</groupId>
              <artifactId>demo</artifactId>
              <version>1.0.0</version>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>com.google.guava</groupId>
                    <artifactId>guava</artifactId>
                    <version>33.0.0-jre</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
              <dependencies>
                <dependency>
                  <groupId>com.google.guava</groupId>
                  <artifactId>guava</artifactId>
                </dependency>
              </dependencies>
            </project>
            """);

        assertTrue(result.mjToml().contains("\"com.google.guava:guava\" = \"33.0.0-jre\""),
                result.mjToml());
    }

    @Test
    void theParentCoordinateIsFilledInAndReported() {
        PomMigration.Result result = PomMigration.translate("""
            <project>
              <parent>
                <groupId>com.example</groupId>
                <artifactId>parent</artifactId>
                <version>9.0.0</version>
              </parent>
              <artifactId>child</artifactId>
            </project>
            """);

        assertTrue(result.mjToml().contains("version = \"9.0.0\""),
                "a child pom inherits the parent's version: " + result.mjToml());
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("com.example:parent:9.0.0")),
                "the parent chain is reported, not silently assumed: " + result.notes());
    }

    @Test
    void unrepresentableFactsAreReportedNotDropped() {
        PomMigration.Result result = PomMigration.translate("""
            <project>
              <groupId>com.example</groupId>
              <artifactId>demo</artifactId>
              <version>1.0.0</version>
              <dependencies>
                <dependency>
                  <groupId>com.example</groupId>
                  <artifactId>snap</artifactId>
                  <version>1.0.0-SNAPSHOT</version>
                </dependency>
                <dependency>
                  <groupId>com.example</groupId>
                  <artifactId>no-version</artifactId>
                </dependency>
                <dependency>
                  <groupId>com.example</groupId>
                  <artifactId>native</artifactId>
                  <version>1.0.0</version>
                  <classifier>linux-x86_64</classifier>
                </dependency>
                <dependency>
                  <groupId>com.example</groupId>
                  <artifactId>bom</artifactId>
                  <version>1.0.0</version>
                  <scope>import</scope>
                  <type>pom</type>
                </dependency>
              </dependencies>
            </project>
            """);

        String notes = String.join("\n", result.notes());
        assertTrue(notes.contains("snapshot"), notes);
        assertTrue(notes.contains("no-version"), notes);
        assertTrue(notes.contains("classifier"), notes);
        assertTrue(notes.contains("BOM"), notes);
        assertFalse(result.mjToml().contains("com.example:snap"),
                "a snapshot never reaches the generated file");
        assertFalse(result.mjToml().contains("com.example:native"),
                "an artifact with a classifier is reported, not mis-declared");
    }
}
