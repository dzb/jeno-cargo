package com.jujin.jeno.pom;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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

        var project = com.jujin.jeno.project.ProjectReader.parse(result.mjToml());
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

    // ------------------------------------------------------------------
    // remote parents through a PomSource
    // ------------------------------------------------------------------

    private static PomSource source(Map<String, String> poms) {
        return (group, artifact, version, base) -> poms.get(group + ":" + artifact + ":" + version);
    }

    private static Map<String, String> poms(Object... keyAndPom) {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < keyAndPom.length; i += 2) {
            map.put((String) keyAndPom[i], (String) keyAndPom[i + 1]);
        }
        return map;
    }

    private static Path child(Path root, String pom) throws java.io.IOException {
        Path dir = Files.createDirectories(root.resolve("child"));
        Files.writeString(dir.resolve("pom.xml"), pom);
        return dir.resolve("pom.xml");
    }

    @Test
    void aRemoteParentSuppliesPropertiesAndManagement(@org.junit.jupiter.api.io.TempDir Path root)
            throws Exception {
        Map<String, String> poms = poms("com.example:parent:1.0.0", """
            <project>
              <groupId>com.example</groupId>
              <artifactId>parent</artifactId>
              <version>1.0.0</version>
              <packaging>pom</packaging>
              <properties><lib.version>3.2.1</lib.version></properties>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>com.example</groupId><artifactId>lib</artifactId>
                    <version>${lib.version}</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """);
        Path pom = child(root, """
            <project>
              <parent>
                <groupId>com.example</groupId><artifactId>parent</artifactId>
                <version>1.0.0</version><relativePath/>
              </parent>
              <artifactId>child</artifactId>
              <dependencies>
                <dependency><groupId>com.example</groupId><artifactId>lib</artifactId></dependency>
              </dependencies>
            </project>
            """);

        PomMigration.Result result = PomMigration.translate(pom, source(poms));

        assertTrue(result.mjToml().contains("\"com.example:lib\" = \"3.2.1\""),
                result.mjToml());
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("read from Central")),
                result.notes().toString());
    }

    @Test
    void aRemoteParentChainIsWalkedParentFirst(@org.junit.jupiter.api.io.TempDir Path root)
            throws Exception {
        Map<String, String> poms = poms(
                "com.example:parent:1.0.0", """
                    <project>
                      <parent>
                        <groupId>com.example</groupId><artifactId>grand</artifactId>
                        <version>2.0.0</version>
                      </parent>
                      <groupId>com.example</groupId><artifactId>parent</artifactId>
                      <version>1.0.0</version>
                      <packaging>pom</packaging>
                      <dependencies>
                        <dependency>
                          <groupId>com.example</groupId><artifactId>lib</artifactId>
                        </dependency>
                      </dependencies>
                    </project>
                    """,
                "com.example:grand:2.0.0", """
                    <project>
                      <groupId>com.example</groupId><artifactId>grand</artifactId>
                      <version>2.0.0</version>
                      <packaging>pom</packaging>
                      <dependencyManagement>
                        <dependencies>
                          <dependency>
                            <groupId>com.example</groupId><artifactId>lib</artifactId>
                            <version>9.9.9</version>
                          </dependency>
                        </dependencies>
                      </dependencyManagement>
                    </project>
                    """);
        Path pom = child(root, """
            <project>
              <parent>
                <groupId>com.example</groupId><artifactId>parent</artifactId>
                <version>1.0.0</version><relativePath/>
              </parent>
              <artifactId>child</artifactId>
              <dependencies>
                <dependency>
                  <groupId>com.example</groupId><artifactId>lib</artifactId>
                </dependency>
              </dependencies>
            </project>
            """);

        PomMigration.Result result = PomMigration.translate(pom, source(poms));

        assertTrue(result.mjToml().contains("\"com.example:lib\" = \"9.9.9\""),
                result.mjToml());
    }

    @Test
    void aMissingRemoteParentIsANoteNotAFailure(@org.junit.jupiter.api.io.TempDir Path root)
            throws Exception {
        Path pom = child(root, """
            <project>
              <parent>
                <groupId>com.example</groupId><artifactId>absent</artifactId>
                <version>1.0.0</version><relativePath/>
              </parent>
              <artifactId>child</artifactId>
            </project>
            """);

        PomMigration.Result result = PomMigration.translate(pom, source(Map.of()));

        assertTrue(result.notes().stream().anyMatch(n -> n.contains("was not found on Central")),
                result.notes().toString());
        assertTrue(result.mjToml().contains("name = \"child\""), result.mjToml());
    }

    @Test
    void aThrowingSourceIsANote(@org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        Path pom = child(root, """
            <project>
              <parent>
                <groupId>com.example</groupId><artifactId>parent</artifactId>
                <version>1.0.0</version><relativePath/>
              </parent>
              <artifactId>child</artifactId>
            </project>
            """);
        PomSource offline = (group, artifact, version, base) -> {
            throw new IllegalStateException("offline: not in the store");
        };

        PomMigration.Result result = PomMigration.translate(pom, offline);

        assertTrue(result.notes().stream().anyMatch(n -> n.contains("offline: not in the store")),
                result.notes().toString());
    }

    @Test
    void aRemoteParentCycleStops(@org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        Map<String, String> poms = poms(
                "com.example:a:1.0.0", """
                    <project>
                      <parent>
                        <groupId>com.example</groupId><artifactId>b</artifactId>
                        <version>1.0.0</version>
                      </parent>
                      <groupId>com.example</groupId><artifactId>a</artifactId>
                      <version>1.0.0</version>
                    </project>
                    """,
                "com.example:b:1.0.0", """
                    <project>
                      <parent>
                        <groupId>com.example</groupId><artifactId>a</artifactId>
                        <version>1.0.0</version>
                      </parent>
                      <groupId>com.example</groupId><artifactId>b</artifactId>
                      <version>1.0.0</version>
                    </project>
                    """);
        Path pom = child(root, """
            <project>
              <parent>
                <groupId>com.example</groupId><artifactId>a</artifactId>
                <version>1.0.0</version><relativePath/>
              </parent>
              <artifactId>child</artifactId>
            </project>
            """);

        PomMigration.Result result = PomMigration.translate(pom, source(poms));

        assertTrue(result.notes().stream().anyMatch(n -> n.contains("parent cycle")),
                result.notes().toString());
    }

    @Test
    void aSnapshotParentIsNotRequested(@org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        java.util.concurrent.atomic.AtomicBoolean called = new java.util.concurrent.atomic.AtomicBoolean();
        PomSource source = (group, artifact, version, base) -> {
            called.set(true);
            return null;
        };
        Path pom = child(root, """
            <project>
              <parent>
                <groupId>com.example</groupId><artifactId>parent</artifactId>
                <version>1.0.0-SNAPSHOT</version><relativePath/>
              </parent>
              <artifactId>child</artifactId>
            </project>
            """);

        PomMigration.Result result = PomMigration.translate(pom, source);

        assertFalse(called.get(), "a snapshot parent is never fetched");
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("snapshot")),
                result.notes().toString());
    }

    @Test
    void anUnresolvedParentVersionIsNotRequested(@org.junit.jupiter.api.io.TempDir Path root)
            throws Exception {
        java.util.concurrent.atomic.AtomicBoolean called = new java.util.concurrent.atomic.AtomicBoolean();
        PomSource source = (group, artifact, version, base) -> {
            called.set(true);
            return null;
        };
        Path pom = child(root, """
            <project>
              <parent>
                <groupId>com.example</groupId><artifactId>parent</artifactId>
                <version>${parent.version}</version><relativePath/>
              </parent>
              <artifactId>child</artifactId>
            </project>
            """);

        PomMigration.Result result = PomMigration.translate(pom, source);

        assertFalse(called.get(), "a ${...} parent version is never fetched");
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("unresolved version")),
                result.notes().toString());
    }

    @Test
    void aLocalParentWinsAndTheSourceIsNotCalled(@org.junit.jupiter.api.io.TempDir Path root)
            throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId><artifactId>parent</artifactId>
              <version>1.0.0</version>
              <properties><lib.version>4.0.0</lib.version></properties>
              <dependencyManagement><dependencies><dependency>
                <groupId>com.example</groupId><artifactId>lib</artifactId>
                <version>${lib.version}</version>
              </dependency></dependencies></dependencyManagement>
            </project>
            """);
        Path pom = child(root, """
            <project>
              <parent>
                <groupId>com.example</groupId><artifactId>parent</artifactId>
                <version>1.0.0</version>
              </parent>
              <artifactId>child</artifactId>
              <dependencies>
                <dependency><groupId>com.example</groupId><artifactId>lib</artifactId></dependency>
              </dependencies>
            </project>
            """);
        PomSource source = (group, artifact, version, base) -> {
            throw new AssertionError("local parent was available; the source must not be used");
        };

        PomMigration.Result result = PomMigration.translate(pom, source);

        assertTrue(result.mjToml().contains("\"com.example:lib\" = \"4.0.0\""),
                result.mjToml());
    }

    // ------------------------------------------------------------------
    // BOM imports
    // ------------------------------------------------------------------

    private static String bom(String group, String artifact, String version, String extra) {
        return """
            <project>
              <groupId>%s</groupId><artifactId>%s</artifactId><version>%s</version>
              <packaging>pom</packaging>
              %s
            </project>
            """.formatted(group, artifact, version, extra);
    }

    private static String childImporting(String bomVersion, String extraDeps) {
        return """
            <project>
              <groupId>com.example</groupId><artifactId>child</artifactId>
              <version>1.0.0</version>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>com.example</groupId><artifactId>bom</artifactId>
                    <version>%s</version><type>pom</type><scope>import</scope>
                  </dependency>
                </dependencies>
              </dependencyManagement>
              <dependencies>
                %s
              </dependencies>
            </project>
            """.formatted(bomVersion, extraDeps);
    }

    private static String dep(String group, String artifact, String version) {
        return "<dependency><groupId>" + group + "</groupId><artifactId>" + artifact
                + "</artifactId>" + (version == null ? "" : "<version>" + version
                + "</version>") + "</dependency>";
    }

    @Test
    void anImportedBomSuppliesManagedVersions(@org.junit.jupiter.api.io.TempDir Path root)
            throws Exception {
        Map<String, String> poms = poms("com.example:bom:1.0.0", bom("com.example", "bom",
                "1.0.0", """
                    <dependencyManagement><dependencies>
                      <dependency>
                        <groupId>com.example</groupId><artifactId>lib</artifactId>
                        <version>5.0.0</version>
                      </dependency>
                    </dependencies></dependencyManagement>
                    """));
        Path pom = child(root, childImporting("1.0.0", dep("com.example", "lib", null)));

        PomMigration.Result result = PomMigration.translate(pom, source(poms));

        assertTrue(result.mjToml().contains("\"com.example:lib\" = \"5.0.0\""),
                result.mjToml());
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("BOM com.example:bom:1.0.0"
                + " read from Central")), result.notes().toString());
    }

    @Test
    void aBomCanImportAnotherBom(@org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        Map<String, String> poms = poms(
                "com.example:bom:1.0.0", bom("com.example", "bom", "1.0.0", """
                    <dependencyManagement><dependencies>
                      <dependency>
                        <groupId>com.example</groupId><artifactId>nested</artifactId>
                        <version>2.0.0</version><type>pom</type><scope>import</scope>
                      </dependency>
                    </dependencies></dependencyManagement>
                    """),
                "com.example:nested:2.0.0", bom("com.example", "nested", "2.0.0", """
                    <dependencyManagement><dependencies>
                      <dependency>
                        <groupId>com.example</groupId><artifactId>lib</artifactId>
                        <version>6.0.0</version>
                      </dependency>
                    </dependencies></dependencyManagement>
                    """));
        Path pom = child(root, childImporting("1.0.0", dep("com.example", "lib", null)));

        PomMigration.Result result = PomMigration.translate(pom, source(poms));

        assertTrue(result.mjToml().contains("\"com.example:lib\" = \"6.0.0\""),
                result.mjToml());
    }

    @Test
    void aBomParentSuppliesItsOwnProperty(@org.junit.jupiter.api.io.TempDir Path root)
            throws Exception {
        Map<String, String> poms = poms(
                "com.example:bom-parent:9.0.0", bom("com.example", "bom-parent", "9.0.0", """
                    <properties><lib.version>7.0.0</lib.version></properties>
                    """),
                "com.example:bom:1.0.0", """
                    <project>
                      <parent>
                        <groupId>com.example</groupId><artifactId>bom-parent</artifactId>
                        <version>9.0.0</version>
                      </parent>
                      <artifactId>bom</artifactId><version>1.0.0</version>
                      <packaging>pom</packaging>
                      <dependencyManagement><dependencies>
                        <dependency>
                          <groupId>com.example</groupId><artifactId>lib</artifactId>
                          <version>${lib.version}</version>
                        </dependency>
                      </dependencies></dependencyManagement>
                    </project>
                    """);
        Path pom = child(root, childImporting("1.0.0", dep("com.example", "lib", null)));

        PomMigration.Result result = PomMigration.translate(pom, source(poms));

        assertTrue(result.mjToml().contains("\"com.example:lib\" = \"7.0.0\""),
                result.mjToml());
    }

    @Test
    void aMissingBomIsANoteButMigrationContinues(@org.junit.jupiter.api.io.TempDir Path root)
            throws Exception {
        Path pom = child(root, childImporting("1.0.0", dep("com.example", "lib", null)));

        PomMigration.Result result = PomMigration.translate(pom, source(Map.of()));

        assertTrue(result.notes().stream().anyMatch(n -> n.contains("was not found on Central")),
                result.notes().toString());
        assertTrue(result.mjToml().contains("name = \"child\""), result.mjToml());
    }

    @Test
    void aBomCycleIsReported(@org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        Map<String, String> poms = poms(
                "com.example:bom:1.0.0", bom("com.example", "bom", "1.0.0", """
                    <dependencyManagement><dependencies><dependency>
                      <groupId>com.example</groupId><artifactId>other</artifactId>
                      <version>1.0.0</version><type>pom</type><scope>import</scope>
                    </dependency></dependencies></dependencyManagement>
                    """),
                "com.example:other:1.0.0", bom("com.example", "other", "1.0.0", """
                    <dependencyManagement><dependencies><dependency>
                      <groupId>com.example</groupId><artifactId>bom</artifactId>
                      <version>1.0.0</version><type>pom</type><scope>import</scope>
                    </dependency></dependencies></dependencyManagement>
                    """));
        Path pom = child(root, childImporting("1.0.0", dep("com.example", "lib", null)));

        PomMigration.Result result = PomMigration.translate(pom, source(poms));

        assertTrue(result.notes().stream().anyMatch(n -> n.contains("BOM import cycle")),
                result.notes().toString());
    }

    @Test
    void anExplicitManagementEntryBeatsTheBom(@org.junit.jupiter.api.io.TempDir Path root)
            throws Exception {
        Map<String, String> poms = poms("com.example:bom:1.0.0", bom("com.example", "bom",
                "1.0.0", """
                    <dependencyManagement><dependencies>
                      <dependency>
                        <groupId>com.example</groupId><artifactId>lib</artifactId>
                        <version>5.0.0</version>
                      </dependency>
                    </dependencies></dependencyManagement>
                    """));
        Path pom = child(root, """
            <project>
              <groupId>com.example</groupId><artifactId>child</artifactId>
              <version>1.0.0</version>
              <dependencyManagement><dependencies>
                <dependency>
                  <groupId>com.example</groupId><artifactId>bom</artifactId>
                  <version>1.0.0</version><type>pom</type><scope>import</scope>
                </dependency>
                <dependency>
                  <groupId>com.example</groupId><artifactId>lib</artifactId>
                  <version>9.9.9</version>
                </dependency>
              </dependencies></dependencyManagement>
              <dependencies>
                <dependency><groupId>com.example</groupId><artifactId>lib</artifactId></dependency>
              </dependencies>
            </project>
            """);

        PomMigration.Result result = PomMigration.translate(pom, source(poms));

        assertTrue(result.mjToml().contains("\"com.example:lib\" = \"9.9.9\""),
                result.mjToml());
    }

    @Test
    void aSnapshotBomIsNotRequested(@org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        java.util.concurrent.atomic.AtomicBoolean called = new java.util.concurrent.atomic.AtomicBoolean();
        PomSource source = (group, artifact, version, base) -> {
            called.set(true);
            return null;
        };
        Path pom = child(root, childImporting("1.0.0-SNAPSHOT", dep("com.example", "lib", null)));

        PomMigration.Result result = PomMigration.translate(pom, source);

        assertFalse(called.get(), "a snapshot BOM is never requested");
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("BOM com.example:bom is a"
                + " snapshot")), result.notes().toString());
    }

    @Test
    void anUnresolvedBomVersionIsNotRequested(@org.junit.jupiter.api.io.TempDir Path root)
            throws Exception {
        java.util.concurrent.atomic.AtomicBoolean called = new java.util.concurrent.atomic.AtomicBoolean();
        PomSource source = (group, artifact, version, base) -> {
            called.set(true);
            return null;
        };
        Path pom = child(root, childImporting("${bom.version}",
                dep("com.example", "lib", null)));

        PomMigration.Result result = PomMigration.translate(pom, source);

        assertFalse(called.get(), "an unresolved BOM version is never requested");
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("BOM com.example:bom version"
                + " '${bom.version}' is unresolved")), result.notes().toString());
    }

    @Test
    void aLocalOnlyMigrationReportsTheBomWithoutFetching(
            @org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        Path pom = child(root, childImporting("1.0.0", dep("com.example", "lib", null)));

        PomMigration.Result result = PomMigration.translate(pom);

        assertTrue(result.notes().stream().anyMatch(n -> n.contains("no source is available")),
                result.notes().toString());
    }

    @Test
    void aBomFetchFailureIsANote(@org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        PomSource offline = (group, artifact, version, base) -> {
            throw new IllegalStateException("offline: not in the store");
        };
        Path pom = child(root, childImporting("1.0.0", dep("com.example", "lib", null)));

        PomMigration.Result result = PomMigration.translate(pom, offline);

        assertTrue(result.notes().stream().anyMatch(n -> n.contains("offline: not in the store")),
                result.notes().toString());
        assertTrue(result.mjToml().contains("name = \"child\""), result.mjToml());
    }

    @Test
    void bomImportsStopAtTheDepthLimit(@org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        Map<String, String> poms = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 12; i++) {
            String importNext = i == 11 ? "" : """
                <dependencyManagement><dependencies><dependency>
                  <groupId>com.example</groupId><artifactId>bom%d</artifactId>
                  <version>1.0.0</version><type>pom</type><scope>import</scope>
                </dependency></dependencies></dependencyManagement>
                """.formatted(i + 1);
            poms.put("com.example:bom" + i + ":1.0.0",
                    bom("com.example", "bom" + i, "1.0.0", importNext));
        }
        Path pom = child(root, childImportingWithGroup("com.example", "bom0",
                "1.0.0", dep("com.example", "lib", null)));

        PomMigration.Result result = PomMigration.translate(pom, source(poms));

        assertTrue(result.notes().stream().anyMatch(n -> n.contains("BOM import depth")),
                result.notes().toString());
    }

    private static String childImportingWithGroup(String group, String bom, String version,
                                                  String deps) {
        return """
            <project>
              <groupId>com.example</groupId><artifactId>child</artifactId>
              <version>1.0.0</version>
              <dependencyManagement><dependencies>
                <dependency>
                  <groupId>%s</groupId><artifactId>%s</artifactId>
                  <version>%s</version><type>pom</type><scope>import</scope>
                </dependency>
              </dependencies></dependencyManagement>
              <dependencies>%s</dependencies>
            </project>
            """.formatted(group, bom, version, deps);
    }

    // ------------------------------------------------------------------
    // [java].version from Maven properties
    // ------------------------------------------------------------------

    private static PomMigration.Result javaPom(String properties) {
        return PomMigration.translate("""
            <project>
              <groupId>com.example</groupId><artifactId>demo</artifactId>
              <version>1.0.0</version>
              <properties>%s</properties>
            </project>
            """.formatted(properties));
    }

    @Test
    void javaVersionComesFromJavaVersionProperty() {
        PomMigration.Result result = javaPom("<java.version>21</java.version>");

        assertTrue(result.mjToml().contains("[java]\nversion = 21"), result.mjToml());
    }

    @Test
    void mavenCompilerReleaseWinsOverOtherProperties() {
        PomMigration.Result result = javaPom(
                "<maven.compiler.release>17</maven.compiler.release>"
                        + "<java.version>21</java.version>"
                        + "<maven.compiler.target>1.8</maven.compiler.target>");

        assertTrue(result.mjToml().contains("[java]\nversion = 17"), result.mjToml());
    }

    @Test
    void compilerTargetMapsLegacyOneDotEightToEight() {
        PomMigration.Result result = javaPom(
                "<maven.compiler.target>1.8</maven.compiler.target>");

        assertTrue(result.mjToml().contains("[java]\nversion = 8"), result.mjToml());
    }

    @Test
    void compilerSourceIsTheLastFallback() {
        PomMigration.Result result = javaPom(
                "<maven.compiler.source>1.7</maven.compiler.source>");

        assertTrue(result.mjToml().contains("[java]\nversion = 7"), result.mjToml());
    }

    @Test
    void noJavaPropertyWritesNoJavaSection() {
        PomMigration.Result result = javaPom("");

        assertFalse(result.mjToml().contains("[java]"), result.mjToml());
        assertTrue(result.notes().isEmpty(), result.notes().toString());
    }

    @Test
    void anUnresolvedJavaVersionIsANoteNotAGuess() {
        PomMigration.Result result = javaPom(
                "<java.version>${java.version}</java.version>");

        assertFalse(result.mjToml().contains("[java]"), result.mjToml());
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("cannot derive [java].version")),
                result.notes().toString());
    }
}
