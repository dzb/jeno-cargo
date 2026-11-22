package com.jujin.mjm.project;

import java.nio.file.Path;

/**
 * A {@code [repos]} entry as a value: a <em>location</em> plus a <em>layout</em>.
 *
 * <p>These are two independent facts, and the old prefix chain conflated them:
 * {@code local:} said "on disk" and {@code mjm:} said "the two-level layout",
 * so "the mjm layout on disk" had no spelling. Now every combination is one
 * value:
 *
 * <pre>
 *   https://host/…        HTTPS + MAVEN      local:&lt;dir&gt;        LOCAL + MAVEN
 *   mjm:https://host/…    HTTPS + MJM        mjm:local:&lt;dir&gt;    LOCAL + MJM
 * </pre>
 *
 * <p>Usage points switch on {@link #layout()} and {@link #transport()}, never on
 * the declaration text. Adding a third layout (or a future git transport) is an
 * enum value plus URLs here — not a new {@code startsWith} in every command.
 *
 * <p>{@link #declaration()} is the exact text the manifest wrote; it is what
 * the lock records as {@code source}, because the lock records facts. Reading a
 * lock parses that text with {@link #parse}, the same parser, so the two sides
 * cannot drift.
 */
public record Repository(String name, Layout layout, Transport transport,
                         String declaration, String localPath, String networkBase) {

    /** How artifacts are laid out under the location. */
    public enum Layout {
        /** Maven's group path: {@code group/path/artifact/version/artifact-version.ext}. */
        MAVEN,
        /** mjm's native two levels: {@code name/version/name-version.ext}. */
        MJM
    }

    /** Where the location lives. */
    public enum Transport {
        /** {@code https://} (loopback {@code http://} is the test/dev exception). */
        HTTPS,
        /** A directory on this machine. */
        LOCAL
    }

    /**
     * The one place a repository declaration is understood. Everything else
     * receives a {@code Repository} and switches on the enums.
     */
    public static Repository parse(String name, String declaration) {
        if (declaration == null || declaration.isBlank()) {
            throw new IllegalArgumentException(
                    "a repository is a location plus a layout:\n"
                    + "  https://host/…        or local:<dir>      (Maven layout)\n"
                    + "  mjm:https://host/…    or mjm:local:<dir>  (mjm layout, artifact names, "
                    + "no group)");
        }
        boolean mjm = declaration.startsWith("mjm:");
        String rest = mjm ? declaration.substring("mjm:".length()) : declaration;
        Layout layout = mjm ? Layout.MJM : Layout.MAVEN;

        if (rest.startsWith("local:")) {
            String path = rest.substring("local:".length()).strip();
            if (path.isEmpty()) {
                throw new IllegalArgumentException("a local: repository needs a directory — "
                        + twoShapes());
            }
            return new Repository(name, layout, Transport.LOCAL, declaration, path, null);
        }
        if (rest.startsWith("https://") || loopback(rest)) {
            return new Repository(name, layout, Transport.HTTPS, declaration, null,
                    rest.endsWith("/") ? rest : rest + "/");
        }
        throw new IllegalArgumentException(twoShapes());
    }

    /** Same parser, for a declaration read back from a lock row. */
    public static Repository parse(String declaration) {
        return parse(null, declaration);
    }

    /**
     * The explicit form: {@code [repos.<name>] type = "maven"|"mjm", url = ...}.
     * The type decides the layout — a static host can serve either one, so the
     * protocol is a declared fact, never guessed from the URL. A url that starts
     * with http is a network location; anything else is a directory ({@code ~}
     * and project-relative paths allowed).
     *
     * <p>The declaration this builds is the canonical lock form, the same one
     * the legacy string spellings produce, so the lockfile does not learn a new
     * shape.
     */
    public static Repository of(String name, String type, String url) {
        String kind = type == null || type.isBlank() ? "maven" : type.strip();
        Layout layout = switch (kind) {
            case "maven" -> Layout.MAVEN;
            case "mjm" -> Layout.MJM;
            default -> throw new IllegalArgumentException(
                    "type must be \"maven\" or \"mjm\", got \"" + kind + "\"");
        };
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("a repository needs a url: https://… or a directory");
        }
        String location = url.strip();
        boolean network = location.startsWith("https://") || loopback(location);
        if (location.startsWith("http://") && !loopback(location)) {
            throw new IllegalArgumentException(
                    "url must be https://… (loopback http is for tests only) or a directory");
        }
        if (location.startsWith("mjm:") || location.startsWith("local:")) {
            throw new IllegalArgumentException(
                    "url is a location, not a type — write type = \"" + kind
                    + "\" and url without the mjm:/local: prefix");
        }
        if (network) {
            String declaration = (layout == Layout.MJM ? "mjm:" : "") + location;
            return new Repository(name, layout, Transport.HTTPS, declaration, null,
                    location.endsWith("/") ? location : location + "/");
        }
        String declaration = (layout == Layout.MJM ? "mjm:" : "") + "local:" + location;
        return new Repository(name, layout, Transport.LOCAL, declaration, location, null);
    }

    private static boolean loopback(String url) {
        return url.startsWith("http://localhost") || url.startsWith("http://127.0.0.1");
    }

    private static String twoShapes() {
        return "a repository is a location plus a layout:\n"
                + "  https://host/…        or local:<dir>      (Maven layout)\n"
                + "  mjm:https://host/…    or mjm:local:<dir>  (mjm layout, artifact names, "
                + "no group)";
    }

    public boolean isLocal() {
        return transport == Transport.LOCAL;
    }

    public boolean isMjmLayout() {
        return layout == Layout.MJM;
    }

    /** The HTTPS base without the layout prefix, ending in {@code /}. */
    public String networkBase() {
        if (networkBase == null) {
            throw new IllegalStateException(declaration + " is not a network repository");
        }
        return networkBase;
    }

    /**
     * The directory a {@code local:} declaration names: {@code ~} expanded, a
     * relative path resolved against the workspace module it was declared in.
     */
    public Path directory(Path root) {
        if (localPath == null) {
            throw new IllegalStateException(declaration + " is not a local repository");
        }
        String raw = localPath;
        if (raw.startsWith("~/") || raw.equals("~")) {
            raw = System.getProperty("user.home") + raw.substring(1);
        }
        Path declared = Path.of(raw);
        return declared.isAbsolute()
                ? declared.normalize()
                : root.resolve(declared).normalize();
    }
}
