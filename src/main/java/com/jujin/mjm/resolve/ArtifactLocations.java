package com.jujin.mjm.resolve;

import java.util.Map;

/**
 * The one place that turns coordinates into URLs — Maven's repository layout
 * and mj-native release layout, over three base kinds: the Central default,
 * an https named repository, and a {@code local:} directory (a Maven-layout
 * folder on disk, served as a {@code file://} URL — the self-bootstrap
 * channel of DESIGN.md Q9).
 */
public final class ArtifactLocations {

    /** Maven Central's canonical repository base — the default of {@link #centralBase()}. */
    public static final String CENTRAL_BASE = "https://repo.maven.apache.org/maven2/";

    /** Maven Central's effective base — overridable by {@code -Dmjm.central.base} (mirrors, tests). */
    public static String centralBase() {
        return System.getProperty("mjm.central.base", CENTRAL_BASE);
    }

    /**
     * The mj-native hosts, overridable by system property for tests and
     * in-house mirrors: {@code -Dmjm.github.host=…} / {@code -Dmjm.gitee.host=…}.
     * The URL shapes stay frozen — only the host swaps.
     */
    public static String githubHost() {
        return System.getProperty("mjm.github.host", "https://github.com");
    }

    public static String giteeHost() {
        return System.getProperty("mjm.gitee.host", "https://gitee.com");
    }

    private ArtifactLocations() {}

    /**
     * The base a named repository resolves to: Central when the name is null,
     * the declared base otherwise. {@code local:} paths are normalized here —
     * {@code ~} expands to the home directory, relative paths stay relative
     * (they resolve against the working directory, i.e. the project root).
     */
    public static String repositoryBase(Map<String, String> repos, String name) {
        if (name == null) {
            return centralBase();
        }
        String base = repos.get(name);
        if (base == null) {
            throw new IllegalArgumentException("repository '" + name + "' is not declared in [repos]");
        }
        return base;
    }

    /**
     * The Maven repository layout: {@code base/group/path/artifact-version.ext}.
     * A {@code local:} base becomes a {@code file://} URL.
     */
    public static String mavenFile(
            String base, String group, String artifactId, String version, String extension) {
        return mavenFile(base, group, artifactId, version, null, extension);
    }

    /**
     * The Maven repository layout with an optional classifier:
     * {@code artifact-version-classifier.ext}. Maven puts the classifier
     * between version and extension — {@code netty-4.1.0-linux-x86_64.jar},
     * never {@code netty-4.1.0.linux-x86_64.jar}. Dropping it silently fetches
     * the base jar, which for a native dependency is the wrong artifact.
     */
    public static String mavenFile(String base, String group, String artifactId,
                                   String version, String classifier, String extension) {
        String file = artifactId + "-" + version
                + (classifier == null || classifier.isBlank() ? "" : "-" + classifier)
                + "." + extension;
        return file(base, group.replace('.', '/') + "/" + artifactId + "/" + version + "/" + file);
    }

    /**
     * The mj-native release layout:
     * {@code host/owner/repo/releases/download/tag/repo-version.ext}.
     */
    public static String releaseFile(
            String host, String owner, String repo, String tag, String version, String extension) {
        String file = repo + "-" + version + "." + extension;
        return host + "/" + owner + "/" + repo + "/releases/download/" + tag + "/" + file;
    }

    private static String file(String base, String path) {
        String root = base.startsWith("local:") ? asFileUrl(base.substring("local:".length())) : base;
        return (root.endsWith("/") ? root : root + "/") + path;
    }

    private static String asFileUrl(String raw) {
        String path = raw.strip();
        if (path.startsWith("~/") || path.equals("~")) {
            path = System.getProperty("user.home") + path.substring(1);
        }
        java.nio.file.Path normalized = java.nio.file.Path.of(path).normalize();
        String url = normalized.toUri().toString();
        return url.endsWith("/") ? url : url + "/";
    }
}
