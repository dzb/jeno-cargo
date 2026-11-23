package com.jujin.mjm.pom;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
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

    @Test
    void aLocalParentSuppliesPropertiesAndManagement(@org.junit.jupiter.api.io.TempDir Path root)
            throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>parent</artifactId>
              <version>9.0.0</version>
              <packaging>pom</packaging>
              <properties>
                <slf4j.version>2.0.18</slf4j.version>
              </properties>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>org.slf4j</groupId>
                    <artifactId>slf4j-api</artifactId>
                    <version>${slf4j.version}</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """);
        Path child = Files.createDirectories(root.resolve("child"));
        Files.writeString(child.resolve("pom.xml"), """
            <project>
              <parent>
                <groupId>com.example</groupId>
                <artifactId>parent</artifactId>
                <version>9.0.0</version>
                <relativePath>../pom.xml</relativePath>
              </parent>
              <artifactId>child</artifactId>
              <dependencies>
                <dependency>
                  <groupId>org.slf4j</groupId>
                  <artifactId>slf4j-api</artifactId>
                </dependency>
              </dependencies>
            </project>
            """);

        PomMigration.Result result = PomMigration.translate(child.resolve("pom.xml"));

        assertTrue(result.mjToml().contains("\"org.slf4j:slf4j-api\" = \"2.0.18\""),
                "parent property + dependencyManagement reached the child: " + result.mjToml());
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("read from")),
                result.notes().toString());
    }

    @Test
    void aMissingLocalParentIsReportedNotFatal(@org.junit.jupiter.api.io.TempDir Path root)
            throws Exception {
        Path child = Files.createDirectories(root.resolve("child"));
        Files.writeString(child.resolve("pom.xml"), """
            <project>
              <parent>
                <groupId>com.example</groupId>
                <artifactId>gone</artifactId>
                <version>1.0.0</version>
                <relativePath>../missing/pom.xml</relativePath>
              </parent>
              <artifactId>child</artifactId>
            </project>
            """);

        PomMigration.Result result = PomMigration.translate(child.resolve("pom.xml"));

        assertTrue(result.notes().stream().anyMatch(n -> n.contains("is not local")),
                result.notes().toString());
        assertTrue(result.mjToml().contains("version = \"1.0.0\""), result.mjToml());
    }

    @Test
    void aParentCycleStopsWithANote(@org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
            <project>
              <parent>
                <groupId>com.example</groupId>
                <artifactId>b</artifactId>
                <version>1.0.0</version>
                <relativePath>b/pom.xml</relativePath>
              </parent>
              <artifactId>a</artifactId>
            </project>
            """);
        Files.createDirectories(root.resolve("b"));
        Files.writeString(root.resolve("b/pom.xml"), """
            <project>
              <parent>
                <groupId>com.example</groupId>
                <artifactId>a</artifactId>
                <version>1.0.0</version>
                <relativePath>../pom.xml</relativePath>
              </parent>
              <artifactId>b</artifactId>
            </project>
            """);

        PomMigration.Result result = PomMigration.translate(root.resolve("pom.xml"));

        assertTrue(result.notes().stream().anyMatch(n -> n.contains("parent cycle")),
                result.notes().toString());
    }

    @Test
    void aPomPackagingRootReportsItsModules() {
        PomMigration.Result result = PomMigration.translate("""
            <project>
              <groupId>com.example</groupId>
              <artifactId>root</artifactId>
              <version>1.0.0</version>
              <packaging>pom</packaging>
              <modules>
                <module>app</module>
                <module>lib</module>
              </modules>
            </project>
            """);

        assertTrue(result.multiModuleRoot(), "a root with modules is not a single project");
        assertEquals(List.of("app", "lib"), result.modules());
    }

    @Test
    void pluginsRepositoriesProfilesAndExclusionsAreReported() {
        PomMigration.Result result = PomMigration.translate("""
            <project>
              <groupId>com.example</groupId>
              <artifactId>demo</artifactId>
              <version>1.0.0</version>
              <repositories>
                <repository><id>corp</id><url>https://nexus.corp/repo/</url></repository>
              </repositories>
              <profiles><profile><id>dev</id></profile></profiles>
              <dependencies>
                <dependency>
                  <groupId>org.example</groupId>
                  <artifactId>lib</artifactId>
                  <version>1.0.0</version>
                  <exclusions>
                    <exclusion><groupId>x</groupId><artifactId>y</artifactId></exclusion>
                  </exclusions>
                </dependency>
              </dependencies>
              <build>
                <plugins>
                  <plugin><artifactId>solon-maven-plugin</artifactId></plugin>
                </plugins>
              </build>
            </project>
            """);

        String notes = String.join("\n", result.notes());
        assertTrue(notes.contains("build plugins"), notes);
        assertTrue(notes.contains("repositories are not copied"), notes);
        assertTrue(notes.contains("profiles are not migrated"), notes);
        assertTrue(notes.contains("exclusions"), notes);
    }

    @Test
    void projectVersionInParentManagementResolvesToTheChildsVersion(
            @org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>parent</artifactId>
              <version>2.0.0</version>
              <packaging>pom</packaging>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>com.example</groupId>
                    <artifactId>sibling</artifactId>
                    <version>${project.version}</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """);
        Path child = Files.createDirectories(root.resolve("child"));
        Files.writeString(child.resolve("pom.xml"), """
            <project>
              <parent>
                <groupId>com.example</groupId>
                <artifactId>parent</artifactId>
                <version>2.0.0</version>
              </parent>
              <artifactId>child</artifactId>
              <dependencies>
                <dependency>
                  <groupId>com.example</groupId>
                  <artifactId>sibling</artifactId>
                </dependency>
              </dependencies>
            </project>
            """);

        PomMigration.Result result = PomMigration.translate(child.resolve("pom.xml"));

        assertTrue(result.mjToml().contains("\"com.example:sibling\" = \"2.0.0\""),
                "parent-managed ${project.version} resolved for the child: " + result.mjToml());
    }
}
