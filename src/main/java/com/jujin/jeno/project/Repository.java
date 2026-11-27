package com.jujin.jeno.project;

import com.jujin.jeno.cli.LegacyNames;

import java.nio.file.Path;

/**
 * A {@code [repos]} entry as a value: a <em>location</em> plus a <em>layout</em>.
 *
 * <p>The structured form ({@link #of}) declares the layout directly and
 * defaults to the native jeno layout when {@code type} is omitted:
 *
 * <pre>
 *   [repos.mine] url = "https://host/"       NETWORK + JENO (default)
 *   [repos.corp] type = "maven" + url        NETWORK + MAVEN
 *   [repos.vendor] url = "vendor"            LOCAL + JENO (default)
 * </pre>
 *
 * <p>Manifests accept only the structured form. The old declaration grammar
 * ({@code local:}, optional {@code jeno:}) remains as an internal parser for
 * lock migration and URL construction; it is not a user-facing repository
 * syntax.
 *
 * <p>Usage points switch on {@link #layout()} and {@link #transport()}, never on
 * the declaration text. Adding a third layout (or a future git transport) is an
 * enum value plus URLs here — not a new {@code startsWith} in every command.
 *
 * <p>{@link #declaration()} is the internal legacy-canonical source spelling;
 * lock v3 uses {@link #lockSource(Repository)} instead (jeno unmarked, Maven
 * marked {@code maven:}). The table-only {@code insecure} flag is a write
 * policy, not an artifact fact, so it is not part of either spelling.
 */
public record Repository(String name, Layout layout, Transport transport,
                         String declaration, String localPath, String networkBase,
                         boolean insecure) {

    /** Compatibility constructor for declarations that carry no token opt-in. */
    public Repository(String name, Layout layout, Transport transport,
                      String declaration, String localPath, String networkBase) {
        this(name, layout, transport, declaration, localPath, networkBase, false);
    }

    /** How artifacts are laid out under the location. */
    public enum Layout {
        /** Maven's group path: {@code group/path/artifact/version/artifact-version.ext}. */
        MAVEN,
        /** jeno's native two levels: {@code name/version/name-version.ext}. */
        JENO
    }

    /** Where the location lives. */
    public enum Transport {
        /** A network location: {@code https://}, or plain {@code http://} on a private network. */
        NETWORK,
        /** A directory on this machine. */
        LOCAL
    }

    /**
     * Internal parser for the legacy declaration spelling used by old lock
     * migration and URL construction. Manifests use {@link #of}; user-facing
     * repository syntax is the structured table only.
     */
    public static Repository parse(String name, String declaration) {
        if (declaration == null || declaration.isBlank()) {
            throw new IllegalArgumentException(twoShapes());
        }
        if (declaration.startsWith(LegacyNames.REPOSITORY_PREFIX)) {
            throw new IllegalArgumentException(LegacyNames.repositoryPrefix(declaration));
        }
        boolean jeno = declaration.startsWith("jeno:");
        String rest = jeno ? declaration.substring("jeno:".length()) : declaration;
        Layout layout = jeno ? Layout.JENO : Layout.MAVEN;

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
     * The lock v3 source spelling: the jeno layout is the default and therefore
     * unmarked; Maven layout is the explicit exception. This is deliberately a
     * separate representation from {@link #declaration()}, whose legacy string
     * grammar keeps Maven as the old default for compatibility.
     */
    public static String lockSource(Repository repository) {
        if (repository.layout() == Layout.JENO) {
            return repository.isLocal()
                    ? "local:" + repository.localPath()
                    : repository.networkBase();
        }
        return "maven:" + repository.declaration();
    }

    /**
     * Convert an internal canonical source (the pre-v3 {@code declaration()}
     * form) to the lock v3 text. Special source species are not repositories
     * and are returned unchanged.
     */
    public static String lockSourceFor(String internalSource) {
        if (internalSource.equals("central") || internalSource.equals("url")
                || internalSource.startsWith("gh:")) {
            return internalSource;
        }
        return lockSource(parse(internalSource));
    }

    /** Best-effort user-facing spelling for an internal canonical source. */
    public static String displaySource(String internalSource) {
        try {
            return lockSourceFor(internalSource);
        } catch (RuntimeException e) {
            return internalSource;
        }
    }

    /**
     * Parse one lock {@code source} value according to the lockfile version.
     * v1/v2 used the legacy declaration grammar (bare https/local = Maven;
     * {@code jeno:} = jeno) and are accepted only as migration input. v3 is the
     * current grammar: bare https/local = jeno and Maven is marked with
     * {@code maven:}.
     */
    public static Repository parseLockSource(String source, int lockVersion) {
        if (lockVersion <= 2) {
            return parse(source);
        }
        if (source.startsWith("jeno:")) {
            throw new IllegalArgumentException(
                    "lockfile v3 uses jeno layout by default; remove the jeno: prefix");
        }
        if (source.startsWith("maven:")) {
            String location = source.substring("maven:".length());
            Repository repository = parse(location);
            if (repository.layout() != Layout.MAVEN) {
                throw new IllegalArgumentException(
                        "maven: lock source must name a Maven-layout repository");
            }
            return repository;
        }
        if (source.startsWith("local:") || source.startsWith("https://")
                || source.startsWith("http://")) {
            return parse("jeno:" + source);
        }
        throw new IllegalArgumentException("lockfile v3 source must be a network URL, a "
                + "local: directory, or maven:<declaration> — got '" + source + "'");
    }

    /**
     * Resolve a publish/release target in the command's jeno-layout context.
     * Because publish/release only write the native layout, a bare https/http
     * URL (or a local: directory) means jeno layout here; Maven must be named
     * explicitly as {@code maven:<declaration>} and will be rejected by the
     * caller. Legacy {@code jeno:} target prefixes are rejected.
     */
    public static Repository resolveJenoTarget(JenoProject project, String target) {
        Repository named = project.repos().get(target);
        if (named != null) {
            return named;
        }
        if (target.startsWith("gh:")) {
            throw new IllegalArgumentException(
                    "gh: is a release channel, not a repository target");
        }
        if (target.startsWith("jeno:")) {
            throw new IllegalArgumentException("jeno: target prefixes were removed; use "
                    + "https://host/, local:<dir>, or a declared [repos.<name>] target");
        }
        if (target.startsWith("maven:")) {
            return parse(target.substring("maven:".length()));
        }
        String location = target.startsWith("local:")
                ? target.substring("local:".length()) : target;
        return of("", "jeno", location);
    }

    /**
     * The explicit form: {@code [repos.<name>] type = "maven"|"jeno", url = ...}.
     * The type decides the layout — a static host can serve either one, so the
     * layout is a declared fact, never guessed from the URL. <b>Omitting type
     * means the native jeno layout</b>; Maven is the explicit foreign layout.
     * A url that starts with http is a network location; anything else is a
     * directory ({@code ~} and project-relative paths allowed).
     *
     * <p>{@link #declaration()} is the internal legacy-canonical spelling
     * shared with the legacy string parser. Lock v3 serializes through
     * {@link #lockSource(Repository)} and reads through
     * {@link #parseLockSource(String, int)}.
     */
    public static Repository of(String name, String type, String url) {
        return of(name, type, url, false);
    }

    /**
     * The same explicit form plus the opt-in that permits
     * {@code JENO_PUBLISH_TOKEN} on a private-network cleartext repository.
     * The opt-in is deliberately repository-local and absent from the lock
     * declaration: it is a write policy, not an artifact fact.
     */
    public static Repository of(String name, String type, String url, boolean insecure) {
        String kind = type == null || type.isBlank() ? "jeno" : type.strip();
        if (kind.equalsIgnoreCase(LegacyNames.REPOSITORY_TYPE)) {
            throw new IllegalArgumentException(LegacyNames.repositoryType());
        }
        Layout layout = switch (kind) {
            case "maven" -> Layout.MAVEN;
            case "jeno" -> Layout.JENO;
            default -> throw new IllegalArgumentException(
                    "type must be \"maven\" or \"jeno\", got \"" + kind + "\"");
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
        if (location.matches("[A-Za-z][A-Za-z0-9+.-]*://.*")
                && !location.startsWith("https://") && !isLocalNetworkHttp(location)) {
            throw new IllegalArgumentException(
                    "url scheme must be https://, private-network http://, or a local "
                            + "directory — got '" + location + "'");
        }
        if (location.startsWith("jeno:") || location.startsWith("local:")) {
            throw new IllegalArgumentException(
                    "url is a location, not a type — write type = \"" + kind
                    + "\" and url without the jeno:/local: prefix");
        }
        if (insecure) {
            if (layout != Layout.JENO) {
                throw new IllegalArgumentException(
                        "insecure = true is only for type = \"jeno\" repositories");
            }
            if (!location.startsWith("http://")) {
                throw new IllegalArgumentException(
                        "insecure = true is only for a private-network http:// repository; "
                                + "https already encrypts JENO_PUBLISH_TOKEN");
            }
        }
        if (network) {
            String declaration = (layout == Layout.JENO ? "jeno:" : "") + location;
            return new Repository(name, layout, Transport.NETWORK, declaration, null,
                    location.endsWith("/") ? location : location + "/", insecure);
        }
        String declaration = (layout == Layout.JENO ? "jeno:" : "") + "local:" + location;
        return new Repository(name, layout, Transport.LOCAL, declaration, location, null, false);
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
                + "  [repos.<name>] url = \"https://host/…\" (or a directory)  → jeno layout by default\n"
                + "  [repos.<name>] type = \"maven\" + url                    → Maven layout\n"
                + "  plain http: literal private address only; public hosts need https.";
    }

    /** The location without a layout/transport prefix, for user-facing output. */
    public String location() {
        return isLocal() ? localPath : networkBase;
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

    public boolean isJenoLayout() {
        return layout == Layout.JENO;
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
