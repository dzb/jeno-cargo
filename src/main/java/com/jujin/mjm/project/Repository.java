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
        /** A network location: {@code https://}, or plain {@code http://} on a private network. */
        NETWORK,
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
        if (rest.startsWith("https://") || isLocalNetworkHttp(rest)) {
            return new Repository(name, layout, Transport.NETWORK, declaration, null,
                    rest.endsWith("/") ? rest : rest + "/");
        }
        if (rest.startsWith("http://")) {
            throw new IllegalArgumentException("plain http is only allowed on a private network"
                    + " (localhost/127/8, 10/8, 172.16-31, 192.168/16, 169.254/16, IPv6 ::1,"
                    + " fc00::/7, fe80::/10) — an explicit IP address, not a hostname; use"
                    + " https:// for public hosts — got '" + declaration + "'");
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
        boolean network = location.startsWith("https://") || isLocalNetworkHttp(location);
        if (location.startsWith("http://") && !isLocalNetworkHttp(location)) {
            throw new IllegalArgumentException(
                    "url must be https://… , plain http on a private network"
                    + " (localhost/10/172.16-31/192.168/169.254/IPv6 ULA or link-local),"
                    + " or a directory");
        }
        if (location.startsWith("mjm:") || location.startsWith("local:")) {
            throw new IllegalArgumentException(
                    "url is a location, not a type — write type = \"" + kind
                    + "\" and url without the mjm:/local: prefix");
        }
        if (network) {
            String declaration = (layout == Layout.MJM ? "mjm:" : "") + location;
            return new Repository(name, layout, Transport.NETWORK, declaration, null,
                    location.endsWith("/") ? location : location + "/");
        }
        String declaration = (layout == Layout.MJM ? "mjm:" : "") + "local:" + location;
        return new Repository(name, layout, Transport.LOCAL, declaration, location, null);
    }

    /**
     * Plain {@code http://} is accepted only for literal private-network hosts:
     * localhost, 127/8, 10/8, 172.16/12, 192.168/16, 169.254/16, IPv6 ::1,
     * fc00::/7 and fe80::/10. No DNS: a hostname such as repo.example is
     * refused, because resolving it would decide the security policy remotely.
     */
    public static boolean isLocalNetworkHttp(String url) {
        if (url == null || !url.startsWith("http://")) {
            return false;
        }
        String host;
        try {
            host = java.net.URI.create(url).getHost();
        } catch (IllegalArgumentException notAUrl) {
            return false;
        }
        if (host == null) {
            return false;
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.equalsIgnoreCase("localhost")) {
            return true;
        }
        if (host.contains(":")) {
            return privateIpv6(host);
        }
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            try {
                octets[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return false;
            }
            if (octets[i] < 0 || octets[i] > 255) {
                return false;
            }
        }
        return octets[0] == 127
                || octets[0] == 10
                || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                || (octets[0] == 192 && octets[1] == 168)
                || (octets[0] == 169 && octets[1] == 254);
    }

    /** Whether a plain-http URL is loopback (no encryption warning needed). */
    public static boolean isLoopbackHttp(String url) {
        if (url == null || !url.startsWith("http://")) {
            return false;
        }
        String host;
        try {
            host = java.net.URI.create(url).getHost();
        } catch (IllegalArgumentException notAUrl) {
            return false;
        }
        if (host == null) {
            return false;
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        return host.equalsIgnoreCase("localhost") || host.equals("::1") || host.startsWith("127.");
    }

    private static boolean privateIpv6(String host) {
        String normalized = host.toLowerCase(java.util.Locale.ROOT);
        if (normalized.equals("::1")) {
            return true;
        }
        String first = normalized.startsWith("::") ? "" : normalized.split(":")[0];
        if (first.length() >= 2) {
            int byte0 = Integer.parseInt(first.substring(0, 2), 16);
            int byte1 = first.length() >= 4
                    ? Integer.parseInt(first.substring(2, 4), 16)
                    : Integer.parseInt(first.substring(2), 16) << 4;
            if ((byte0 & 0xfe) == 0xfc) {
                return true;  // fc00::/7
            }
            if (byte0 == 0xfe && (byte1 & 0xc0) == 0x80) {
                return true;  // fe80::/10
            }
        }
        return false;
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

    /** Whether this is a plain-http network location (never true for https/local). */
    public boolean isPlainHttp() {
        return networkBase != null && networkBase.startsWith("http://");
    }

    /** Whether a plain-http location is loopback (no encryption warning needed). */
    public boolean isLoopbackNetwork() {
        return isPlainHttp() && isLoopbackHttp(networkBase);
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
