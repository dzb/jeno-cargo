package com.jujin.mjm.resolve;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;

/**
 * The one place that turns coordinates into URLs — Maven's repository layout
 * and mj-native release layout, over three base kinds: the Central default,
 * an https named repository, and a {@code local:} directory (a Maven-layout
 * folder on disk — the self-bootstrap channel of DESIGN.md Q9).
 *
 * <p>A {@code local:} artifact is written into {@code mjm.lock} as
 * {@code file:vendor/com/…/x.jar} — relative to the project root, so the lock
 * does not bind one checkout's absolute path. Reading expands it against the
 * project root again ({@link #localPath}); {@code file:///abs/…} rows stay
 * readable for locks written before this rule. Only paths outside the project
 * root (for example {@code local:/opt/repo}) are written absolute, because a
 * relative form would not describe them.
 */
public final class ArtifactLocations {

    /** Maven Central's canonical repository base — the default of {@link #centralBase()}. */
    public static final String CENTRAL_BASE = "https://repo.maven.apache.org/maven2/";

    /**
     * The project this process works in. A {@code local:} base declared as a
     * relative path, and a relative {@code file:} lock URL, both resolve here —
     * never against whatever directory the shell happened to start in.
     */
    private static Path projectRoot = Path.of("").toAbsolutePath().normalize();

    /** One process works in one project; the CLI sets this once at assembly. */
    public static void useProjectRoot(Path projectDir) {
        projectRoot = projectDir.toAbsolutePath().normalize();
    }

    public static Path projectRoot() {
        return projectRoot;
    }

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
     * the declared base otherwise. A {@code local:} path keeps its declaration
     * as written ({@code ~} expands to the home directory); a relative one is
     * resolved against the project root where the URL is built.
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

    /**
     * A repository base as a fetchable URL prefix, always ending in {@code /}.
     * A {@code local:} directory becomes an absolute {@code file:///} URL: this
     * is for callers that fetch a path outside the artifact layout (a version
     * listing), where the lock's relative form does not apply. The lock's own
     * form stays {@link #lockUrl}'s business.
     */
    public static String baseUrl(String base) {
        if (!base.startsWith("local:")) {
            return base.endsWith("/") ? base : base + "/";
        }
        String url = localBase(base).toUri().toString();
        return url.endsWith("/") ? url : url + "/";
    }

    private static String file(String base, String path) {
        if (!base.startsWith("local:")) {
            return (base.endsWith("/") ? base : base + "/") + path;
        }
        Path artifact = localBase(base).resolve(path).normalize();
        return lockUrl(artifact, projectRoot);
    }

    /** The declared {@code local:} directory as an absolute path on this machine. */
    private static Path localBase(String base) {
        String raw = base.substring("local:".length()).strip();
        if (raw.startsWith("~/") || raw.equals("~")) {
            raw = System.getProperty("user.home") + raw.substring(1);
        }
        Path declared = Path.of(raw);
        return declared.isAbsolute()
                ? declared.normalize()
                : projectRoot.resolve(declared).normalize();
    }

    /**
     * The lock representation of a file on disk: {@code file:vendor/…} when it
     * sits inside the project (portable), the absolute {@code file:///…} form
     * when it does not (no relative form could describe it).
     */
    static String lockUrl(Path artifact, Path root) {
        Path normalized = artifact.toAbsolutePath().normalize();
        if (normalized.startsWith(root)) {
            return "file:" + root.relativize(normalized).toString().replace('\\', '/');
        }
        return normalized.toUri().toString();
    }

    /** The absolute URL a lock row points at, expanded against this project. */
    public static String expand(String url) {
        return expand(url, projectRoot);
    }

    /** The same expansion against an explicit root — the pure form, for callers and tests. */
    public static String expand(String url, Path root) {
        return url.startsWith("file:") ? localPath(url, root).toUri().toString() : url;
    }

    /**
     * The file a {@code file:} URL points at. Absolute {@code file:///…} rows
     * (old locks) resolve as they are; a relative {@code file:vendor/…} row is
     * relative to the project root, never to the working directory.
     */
    public static Path localPath(String url, Path root) {
        String rest = url.substring("file:".length());
        if (rest.startsWith("//")) {
            return Path.of(URI.create(url));
        }
        Path declared = Path.of(rest);
        return (declared.isAbsolute() ? declared : root.resolve(declared))
                .toAbsolutePath().normalize();
    }
}
