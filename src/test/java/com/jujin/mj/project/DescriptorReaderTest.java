package com.jujin.mj.project;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the mj-native release descriptor (SPEC.md §3): facts
 * only, name/version consistency enforced, retraction never self-inflicted.
 */
class DescriptorReaderTest {

    private static final String DESCRIPTOR = """
        [project]
        name = "mylib"
        version = "1.2.0"

        [java]
        version = 21

        [deps]
        "com.example:lib-a" = "1.0.0"

        [retract]
        "1.1.0" = "regression in the serializer, use 1.2.0"
        """;

    @Test
    void parsesFactsOnly() {
        Descriptor descriptor = DescriptorReader.parse(DESCRIPTOR, "mylib", "1.2.0");

        assertEquals("mylib", descriptor.name());
        assertEquals("1.2.0", descriptor.version());
        assertEquals(21, descriptor.javaVersion());
        assertEquals(1, descriptor.requirements().size());
        assertEquals("regression in the serializer, use 1.2.0",
                descriptor.retract().get("1.1.0"), "a retraction carries its reason");
    }

    @Test
    void nameAndVersionMustMatchWhatWasFetched() {
        ProjectException name = assertThrows(ProjectException.class,
            () -> DescriptorReader.parse(DESCRIPTOR, "other", "1.2.0"));
        assertTrue(name.getMessage().contains("declares name 'mylib'"), "got: " + name.getMessage());

        ProjectException version = assertThrows(ProjectException.class,
            () -> DescriptorReader.parse(DESCRIPTOR, "mylib", "1.3.0"));
        assertTrue(version.getMessage().contains("declares version '1.2.0'"),
                "got: " + version.getMessage());
    }

    @Test
    void descriptorsRejectRuleSections() {
        assertThrows(ProjectException.class,
            () -> DescriptorReader.parse("""
                [project]
                name = "mylib"
                version = "1.2.0"

                [limits]
                max-depth = 5
                """, "mylib", "1.2.0"));

        assertThrows(ProjectException.class,
            () -> DescriptorReader.parse("""
                [project]
                name = "mylib"
                version = "1.2.0"

                [repos]
                corp = "https://nexus.corp.internal/repository/maven/"

                [deps.corp]
                "com.corp:internal" = "2.1.0"
                """, "mylib", "1.2.0"));
    }

    @Test
    void aReleaseNeverRetractsItself() {
        ProjectException ex = assertThrows(ProjectException.class,
            () -> DescriptorReader.parse("""
                [project]
                name = "mylib"
                version = "1.2.0"

                [retract]
                "1.2.0" = "oops"
                """, "mylib", "1.2.0"));
        assertTrue(ex.getMessage().contains("never retracts itself"), "got: " + ex.getMessage());
    }

    @Test
    void unknownSectionsAndBadValuesAreNamed() {
        ProjectException unknown = assertThrows(ProjectException.class,
            () -> DescriptorReader.parse("""
                [project]
                name = "mylib"
                version = "1.2.0"

                [scripts]
                dev = "mj run"
                """, "mylib", "1.2.0"));
        assertTrue(unknown.getMessage().contains("unknown section [scripts]"),
                "got: " + unknown.getMessage());

        ProjectException type = assertThrows(ProjectException.class,
            () -> DescriptorReader.parse("""
                [project]
                name = "mylib"
                version = 12
                """, "mylib", "1.2.0"));
        assertTrue(type.getMessage().contains("must be a non-blank string"),
                "got: " + type.getMessage());
    }
}
