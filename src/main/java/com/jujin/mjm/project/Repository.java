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
 *   https://host/…        NETWORK + MAVEN    local:&lt;dir&gt;        LOCAL + MAVEN
 *   mjm:https://host/…    NETWORK + MJM      mjm:local:&lt;dir&gt;    LOCAL + MJM
 *
 * Plain {@code http://} is a NETWORK location too, but only for literal
 * private-network addresses ({@link #isLocalNetworkHttp}).
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
        Host host = parseHttpHost(url);
        if (host == null || host.kind == Kind.LOCALHOST) {
            return host != null;
        }
        if (host.kind == Kind.IPV4) {
            int[] o = host.ipv4;
            return o[0] == 127
                    || o[0] == 10
                    || (o[0] == 172 && o[1] >= 16 && o[1] <= 31)
                    || (o[0] == 192 && o[1] == 168)
                    || (o[0] == 169 && o[1] == 254);
        }
        int first = host.ipv6[0];
        if (isLoopbackIpv6(host.ipv6)) {
            return true;
        }
        if ((first & 0xfe00) == 0xfc00) {
            return true;  // fc00::/7
        }
        return (first & 0xffc0) == 0xfe80;  // fe80::/10
    }

    /** Whether a plain-http URL is loopback (no encryption warning needed). */
    public static boolean isLoopbackHttp(String url) {
        Host host = parseHttpHost(url);
        if (host == null) {
            return false;
        }
        if (host.kind == Kind.LOCALHOST) {
            return true;
        }
        if (host.kind == Kind.IPV4) {
            return host.ipv4[0] == 127;
        }
        return isLoopbackIpv6(host.ipv6);
    }

    private enum Kind { LOCALHOST, IPV4, IPV6 }

    private record Host(Kind kind, int[] ipv4, int[] ipv6) {}

    /**
     * The host of a plain-http URL if, and only if, it is a strict literal:
     * {@code localhost}, dotted-quad IPv4 without leading zeros, or an IPv6
     * address in any compressing form (zone allowed). Anything else — a
     * hostname, a malformed address, another scheme — returns null and is never
     * resolved.
     */
    private static Host parseHttpHost(String url) {
        if (url == null || !url.startsWith("http://")) {
            return null;
        }
        String authority;
        try {
            authority = java.net.URI.create(url).getRawAuthority();
        } catch (IllegalArgumentException notAUrl) {
            return null;
        }
        if (authority == null || authority.isEmpty()) {
            return null;
        }
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0) {
                return null;
            }
            int[] groups = parseIpv6(authority.substring(1, close));
            return groups == null ? null : new Host(Kind.IPV6, null, groups);
        }
        int colon = authority.lastIndexOf(':');
        String host = colon < 0 ? authority : authority.substring(0, colon);
        if (host.equalsIgnoreCase("localhost")) {
            return new Host(Kind.LOCALHOST, null, null);
        }
        int[] octets = parseIpv4(host);
        return octets == null ? null : new Host(Kind.IPV4, octets, null);
    }

    /** Strict dotted quad: four decimal octets, 0-255, no leading zeros. */
    private static int[] parseIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 1 && part.startsWith("0")) {
                return null;
            }
            for (int j = 0; j < part.length(); j++) {
                if (part.charAt(j) < '0' || part.charAt(j) > '9') {
                    return null;
                }
            }
            try {
                octets[i] = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                return null;
            }
            if (octets[i] > 255) {
                return null;
            }
        }
        return octets;
    }

    /**
     * IPv6 by 16-bit groups: {@code ::} compression, 1-4 hex digits per group,
     * exactly eight groups after expansion, optional {@code %zone}. No
     * string-prefix guessing.
     */
    private static int[] parseIpv6(String text) {
        int zone = text.indexOf('%');
        if (zone >= 0) {
            String zoneName = text.substring(zone + 1);
            if (zoneName.isEmpty() || !zoneName.matches("[A-Za-z0-9_.-]+")) {
                return null;
            }
            text = text.substring(0, zone);
        }
        if (text.isEmpty() || !text.matches("[0-9A-Fa-f:]+")) {
            return null;
        }
        int compression = text.indexOf("::");
        if (compression >= 0 && text.indexOf("::", compression + 2) >= 0) {
            return null;
        }
        String left = compression < 0 ? text : text.substring(0, compression);
        String right = compression < 0 ? "" : text.substring(compression + 2);
        int[] leftGroups = groups(left);
        int[] rightGroups = groups(right);
        if (leftGroups == null || rightGroups == null) {
            return null;
        }
        int[] result = new int[8];
        if (compression < 0) {
            if (leftGroups.length != 8) {
                return null;
            }
            System.arraycopy(leftGroups, 0, result, 0, 8);
            return result;
        }
        if (leftGroups.length + rightGroups.length >= 8) {
            return null;  // :: must stand for at least one zero group
        }
        System.arraycopy(leftGroups, 0, result, 0, leftGroups.length);
        System.arraycopy(rightGroups, 0, result,
                8 - rightGroups.length, rightGroups.length);
        return result;
    }

    /** Groups of one half; empty half is allowed only around {@code ::}. */
    private static int[] groups(String half) {
        if (half.isEmpty()) {
            return new int[0];
        }
        String[] parts = half.split(":", -1);
        int[] values = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 4) {
                return null;
            }
            try {
                values[i] = Integer.parseInt(part, 16);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return values;
    }

    private static boolean isLoopbackIpv6(int[] groups) {
        for (int i = 0; i < 7; i++) {
            if (groups[i] != 0) {
                return false;
            }
        }
        return groups[7] == 1;
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

    /** The network base without the layout prefix, ending in {@code /}. */
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
