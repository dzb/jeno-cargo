package com.jujin.jeno.resolve;

import org.junit.jupiter.api.Test;

import com.jujin.jeno.project.Repository;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactLocationsTest {

    @Test
    void centralIsTheDefaultBase() {
        assertEquals(ArtifactLocations.CENTRAL_BASE, ArtifactLocations.repositoryBase(Map.of(), null));
        assertEquals("https://nexus.corp.internal/repository/maven/",
                ArtifactLocations.repositoryBase(
                        Map.of("corp", Repository.parse("corp",
                                "https://nexus.corp.internal/repository/maven/")), "corp"));
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
    void aClassifierSitsBetweenVersionAndExtension() {
        // Maven's layout: netty-4.1.0-linux-x86_64.jar — the classifier is a
        // dash-separated segment, not an extension prefix. Getting this wrong
        // silently downloads the base jar instead of the native one.
        assertEquals(
                "https://repo.maven.apache.org/maven2/io/netty/netty-transport-native-epoll/"
                        + "4.1.0/netty-transport-native-epoll-4.1.0-linux-x86_64.jar",
                ArtifactLocations.mavenFile(ArtifactLocations.CENTRAL_BASE,
                        "io.netty", "netty-transport-native-epoll", "4.1.0",
                        "linux-x86_64", "jar"));
    }

    @Test
    void aNullClassifierProducesTheBaseJarName() {
        assertEquals(
                ArtifactLocations.mavenFile(ArtifactLocations.CENTRAL_BASE,
                        "com.example", "lib-a", "1.0.0", "jar"),
                ArtifactLocations.mavenFile(ArtifactLocations.CENTRAL_BASE,
                        "com.example", "lib-a", "1.0.0", null, "jar"),
                "no classifier and an explicit null agree");
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
        assertEquals("https://github.com/acme/mylib/releases/download/v1.2.0/mylib-1.2.0.jeno.toml",
                ArtifactLocations.releaseFile("https://github.com", "acme", "mylib",
                        "v1.2.0", "1.2.0", "jeno.toml"), "the descriptor travels next to the jar");
    }

    @Test
    void canonicalBaseSeesThroughSpellingOnly() {
        assertEquals(ArtifactLocations.canonicalBase("jeno:https://host/repo/"),
                ArtifactLocations.canonicalBase("jeno:https://HOST/repo"),
                "host case and the trailing slash are spelling");
        assertEquals(ArtifactLocations.canonicalBase("jeno:https://host/repo/"),
                ArtifactLocations.canonicalBase("jeno:https://host:443/repo/"),
                "the default port is spelling");

        assertNotEquals(ArtifactLocations.canonicalBase("jeno:https://host/repo/"),
                ArtifactLocations.canonicalBase("jeno:https://other-host/repo/"),
                "a different host is a different repository");
        assertNotEquals(ArtifactLocations.canonicalBase("jeno:https://host/repo/"),
                ArtifactLocations.canonicalBase("jeno:https://host/other/"),
                "a different path is a different repository");
    }

    @Test
    void anUndeclaredRepositoryNameIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ArtifactLocations.repositoryBase(Map.of(), "nope"));
    }
}
