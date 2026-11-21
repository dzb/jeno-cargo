package com.jujin.mj.resolve;

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

    /** Maven Central's effective base — overridable by {@code -Dmj.central.base} (mirrors, tests). */
    public static String centralBase() {
        return System.getProperty("mj.central.base", CENTRAL_BASE);
    }

    /**
     * The mj-native hosts, overridable by system property for tests and
     * in-house mirrors: {@code -Dmj.github.host=…} / {@code -Dmj.gitee.host=…}.
     * The URL shapes stay frozen — only the host swaps.
     */
    public static String githubHost() {
        return System.getProperty("mj.github.host", "https://github.com");
    }

    public static String giteeHost() {
        return System.getProperty("mj.gitee.host", "https://gitee.com");
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
        String path = group.replace('.', '/') + "/" + artifactId + "/" + version + "/"
                + artifactId + "-" + version + "." + extension;
        return file(base, path);
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
