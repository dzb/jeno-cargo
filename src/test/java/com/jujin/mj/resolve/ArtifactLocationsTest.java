package com.jujin.mj.resolve;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactLocationsTest {

    @Test
    void centralIsTheDefaultBase() {
        assertEquals(ArtifactLocations.CENTRAL_BASE, ArtifactLocations.repositoryBase(Map.of(), null));
        assertEquals("https://nexus.corp.internal/repository/maven/",
                ArtifactLocations.repositoryBase(
                        Map.of("corp", "https://nexus.corp.internal/repository/maven/"), "corp"));
    }

    @Test
    void mavenLayoutUnderAnyBase() {
        assertEquals(
                "https://repo.maven.apache.org/maven2/com/example/lib-a/1.0.0/lib-a-1.0.0.jar",
                ArtifactLocations.mavenFile(ArtifactLocations.CENTRAL_BASE,
                        "com.example", "lib-a", "1.0.0", "jar"));
        assertEquals(
                "https://nexus.corp.internal/repository/maven2/com/example/lib-a/1.0.0/lib-a-1.0.0.pom",
                ArtifactLocations.mavenFile("https://nexus.corp.internal/repository/maven2",
                        "com.example", "lib-a", "1.0.0", "pom"),
                "a base without a trailing slash is joined with one");
    }

    @Test
    void localBasesBecomeFileUrls() {
        String url = ArtifactLocations.mavenFile("local:/home/dev/.m2/repository",
                "com.example", "lib-a", "1.0.0", "jar");
        assertTrue(url.startsWith("file:///home/dev/.m2/repository/com/example/lib-a/1.0.0/"),
                "a local: base is a Maven-layout directory on disk, got: " + url);
        assertTrue(url.endsWith("lib-a-1.0.0.jar"));

        String home = System.getProperty("user.home");
        String tilde = ArtifactLocations.mavenFile("local:~/.m2/repository",
                "com.example", "lib-a", "1.0.0", "jar");
        assertTrue(tilde.startsWith("file://" + home + "/.m2/repository/"),
                "~ expands to the home directory, got: " + tilde);
    }

    @Test
    void releaseLayoutCarriesTheTag() {
        assertEquals("https://github.com/acme/mylib/releases/download/v1.2.0/mylib-1.2.0.jar",
                ArtifactLocations.releaseFile("https://github.com", "acme", "mylib",
                        "v1.2.0", "1.2.0", "jar"));
        assertEquals("https://gitee.com/acme/mylib/releases/download/v1.2.0/mylib-1.2.0.mj.toml",
                ArtifactLocations.releaseFile("https://gitee.com", "acme", "mylib",
                        "v1.2.0", "1.2.0", "mj.toml"), "the descriptor travels next to the jar");
    }

    @Test
    void anUndeclaredRepositoryNameIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ArtifactLocations.repositoryBase(Map.of(), "nope"));
    }
}
