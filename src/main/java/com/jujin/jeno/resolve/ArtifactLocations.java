package com.jujin.jeno.resolve;

import com.jujin.jeno.project.Repository;

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
     *
     * <p>Process-wide and order-dependent: the CLI sets it once at assembly, and
     * workspace orchestration switches it to each module for the duration of
     * that module's command, restoring it afterwards. Sequential use is the only
     * supported use — concurrent commands in one process would race here, and
     * making the root an explicit parameter through the resolver/store is its
     * own change.
     */
    private static Path projectRoot = Path.of("").toAbsolutePath().normalize();

    /**
     * Points this process at a project. The CLI calls it once for the working
     * directory; workspace orchestration calls it per module (see above).
     */
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
     * The GitHub host, overridable by system property for tests and in-house
     * mirrors: {@code -Dmjm.github.host=…}. The URL shapes stay frozen — only
     * the host swaps.
     */
    public static String githubHost() {
        return System.getProperty("mjm.github.host", "https://github.com");
    }

    private ArtifactLocations() {}

    /**
     * The base a named repository resolves to: Central when the name is null,
     * the declared base otherwise. A {@code local:} path keeps its declaration
     * as written ({@code ~} expands to the home directory); a relative one is
     * resolved against the project root where the URL is built.
     */
    public static String repositoryBase(Map<String, Repository> repos, String name) {
        if (name == null) {
            return centralBase();
        }
        Repository repository = repos.get(name);
        if (repository == null) {
            throw new IllegalArgumentException("repository '" + name + "' is not declared in [repos]");
        }
        return repository.declaration();
    }

    /** The declaration text a repository is addressed by — the lock's {@code source}. */
    public static String repositoryDeclaration(Map<String, Repository> repos, String name) {
        Repository repository = repos.get(name);
        if (repository == null) {
            throw new IllegalArgumentException("repository '" + name + "' is not declared in [repos]");
        }
        return repository.declaration();
    }

    /**
     * The Maven repository layout: {@code base/group/path/artifact-version.ext}.
     * A {@code local:} base becomes a {@code file://} URL.
     */
    public static String mavenFile(
            String base, String group, String artifactId, String version, String extension) {
        return mavenFile(Repository.parse(base), group, artifactId, version, null, extension);
    }

    /** The Maven layout under a repository, local or remote. */
    public static String mavenFile(
            Repository repository, String group, String artifactId, String version,
            String extension) {
        return mavenFile(repository, group, artifactId, version, null, extension);
    }

    public static String mavenFile(
            Repository repository, String group, String artifactId, String version,
            String classifier, String extension) {
        String directory = group.replace('.', '/') + "/" + artifactId + "/" + version + "/";
        String file = artifactId + "-" + version
                + (classifier == null || classifier.isBlank() ? "" : "-" + classifier)
                + "." + extension;
        if (repository.isLocal()) {
            Path artifact = repository.directory(projectRoot).resolve(directory + file).normalize();
            return lockUrl(artifact, projectRoot);
        }
        return repository.networkBase() + directory + file;
    }

    /**
     * The Maven layout for a declaration or a base URL: local ones become the
     * lock's portable {@code file:} form, https ones the absolute URL. The
     * classifier sits between version and extension
     * ({@code netty-4.1.0-linux-x86_64.jar}), as Maven writes it.
     */
    public static String mavenFile(String base, String group, String artifactId,
                                   String version, String classifier, String extension) {
        return mavenFile(Repository.parse(base), group, artifactId, version,
                classifier, extension);
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
     * A repository base normalized for <em>comparison</em>: scheme and host
     * lowercased, the scheme's default port dropped, the path closed with a
     * slash. No redirects, no DNS — two spellings of one base compare equal,
     * anything that could reach a different server does not.
     */
    /**
     * Where a repository actually is, comparable across spellings: HTTPS keeps
     * {@link #canonicalBase}'s normalization; a local directory becomes its
     * real path, so {@code ../vendor} from one module and an absolute
     * {@code /abs/…/vendor} name the same location — which is exactly the
     * question publish asks when deciding whether a dependency is a sibling.
     */
    public static String canonicalLocation(Repository repository) {
        if (!repository.isLocal()) {
            return canonicalBase(repository);
        }
        Path absolute = repository.directory(projectRoot).toAbsolutePath().normalize();
        try {
            return absolute.toRealPath().toString();
        } catch (java.io.IOException e) {
            // the directory may not exist yet (a publish target creates it):
            // resolve the parent when possible so a symlinked parent still matches
            Path parent = absolute.getParent();
            if (parent != null) {
                try {
                    return parent.toRealPath().resolve(absolute.getFileName()).toString();
                } catch (java.io.IOException ignored) {
                    // fall through to the normalized path
                }
            }
            return absolute.toString();
        }
    }

    public static String canonicalBase(Repository repository) {
        return canonicalBaseOf(repository.networkBase());
    }

    public static String canonicalBase(String declaration) {
        return canonicalBase(Repository.parse(declaration));
    }

    private static String canonicalBaseOf(String host) {
        if (host == null) {
            return "";
        }
        try {
            java.net.URI uri = java.net.URI.create(host);
            String scheme = uri.getScheme() == null ? ""
                    : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            String authority = uri.getHost() == null ? ""
                    : uri.getHost().toLowerCase(java.util.Locale.ROOT);
            int port = uri.getPort();
            if (("https".equals(scheme) && port == 443)
                    || ("http".equals(scheme) && port == 80)) {
                port = -1;
            }
            String path = uri.getPath() == null ? "/" : uri.getPath();
            if (!path.endsWith("/")) {
                path = path + "/";
            }
            return scheme + "://" + authority + (port >= 0 ? ":" + port : "") + path;
        } catch (IllegalArgumentException notAUrl) {
            // compare the text as-is rather than pretending to understand it
            return host;
        }
    }

    /**
     * The mjm repository layout: {@code <base>/<name>/<version>/<name>-<version>.<ext>} —
     * two levels, no group. {@code base} is the declaration — {@code mjm:https://…}
     * or a private-network {@code mjm:http://<literal-ip>…} — whose prefix is
     * stripped here.
     */
    /** The mjm two-level layout under a repository, local or remote. */
    public static String mjmFile(Repository repository, String name, String version,
                                 String extension) {
        String path = name + "/" + version + "/" + name + "-" + version + "." + extension;
        if (repository.isLocal()) {
            Path artifact = repository.directory(projectRoot).resolve(path).normalize();
            return lockUrl(artifact, projectRoot);
        }
        return repository.networkBase() + path;
    }

    /** Same, for a declaration read from a lock row or a command line. */
    public static String mjmFile(String declaration, String name, String version,
                                 String extension) {
        return mjmFile(Repository.parse(declaration), name, version, extension);
    }

    /**
     * The absolute base a POM fetch uses: the https base, or a {@code file:}
     * directory URL for a local repository. POMs are read immediately, so the
     * relative lock form is not wanted here.
     */
    public static String fetchBase(Repository repository) {
        if (!repository.isLocal()) {
            return repository.networkBase();
        }
        String url = repository.directory(projectRoot).toUri().toString();
        return url.endsWith("/") ? url : url + "/";
    }

    /** The local directory of a {@code local:} repository. */
    public static Path localDirectory(Repository repository) {
        return repository.directory(projectRoot);
    }

    /** The local directory named by a declaration (publish target, tests). */
    public static Path localDirectory(String declaration) {
        return localDirectory(Repository.parse(declaration));
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
