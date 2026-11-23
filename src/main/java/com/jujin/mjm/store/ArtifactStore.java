package com.jujin.mjm.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

/**
 * The content-addressed store (DESIGN.md §6.1): every downloaded artifact
 * lands as {@code <sha256>.<ext>} under one root, read-only once written, and
 * every concurrent write is guarded by a URL-level file lock. The classpath
 * points straight into the store — nothing is ever copied per project.
 *
 * <p>Downloads are the JDK's own {@link HttpClient} for https and private-network http URLs;
 * {@code file://} URLs (a {@code local:} repository) are read straight from
 * disk — the copy through the store still pins the sha256, so a local
 * bootstrap is exactly as tamper-evident as a remote one.
 */
public final class ArtifactStore {

    /** What kind of bytes a URL holds — decides the store file's extension. */
    public enum Kind {
        JAR("jar"), POM("pom"), DESCRIPTOR("mjm.toml"), ARCHIVE("tar.gz");

        private final String extension;

        Kind(String extension) {
            this.extension = extension;
        }

        String extension() {
            return extension;
        }
    }

    private static final Set<java.nio.file.attribute.PosixFilePermission> READ_PERMISSIONS = Set.of(
            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
            java.nio.file.attribute.PosixFilePermission.GROUP_READ,
            java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE,
            java.nio.file.attribute.PosixFilePermission.OTHERS_READ,
            java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE);

    private final Path root;
    private final Path locks;
    /**
     * URL → digest, persisted: without it every fresh process re-downloads
     * every pom even when the bytes are already on disk.
     */
    private final UrlIndex index;
    // HTTP/1.1 on purpose: a store download is one file, so multiplexing buys
    // nothing — and HTTP/2 was observed failing on GitHub release assets after
    // the redirect (EOF while reading, then a connect timeout), while HTTP/1.1
    // answered 200 on every attempt on the same machine and URL.
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    /** The digests of everything this session downloaded — the lockfile's source of truth. */
    private final Map<String, String> digestByUrl = new ConcurrentHashMap<>();

    /**
     * When true, {@link #ensure} answers from the store or fails — it never
     * touches the network. Set once per process by the CLI ({@code --offline}),
     * because "may this run reach the network" is a property of the run, not of
     * one store instance.
     *
     * <p>Offline is not "prefer local": a miss is an error naming what is
     * missing. A silent fallback to the network would make the flag a lie, and
     * a silent downgrade to a partial build would be worse.
     */
    private static volatile boolean offline;

    /** Puts the whole process in offline mode. Called once, from the entry point. */
    public static void goOffline() {
        offline = true;
    }

    /** Whether this process refuses network access. */
    public static boolean isOffline() {
        return offline;
    }

    public ArtifactStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
        this.locks = this.root.resolve("locks");
        this.index = new UrlIndex(this.root);
        try {
            Files.createDirectories(this.locks);
        } catch (IOException e) {
            throw new StoreException("cannot create the store at " + this.root, e);
        }
    }

    /** The user's store, wherever {@link Paths} decided it lives. */
    public static ArtifactStore userStore() {
        return new ArtifactStore(Paths.store());
    }

    /**
     * Ensures the content at {@code url} is in the store and returns its path.
     * The digest is remembered for the session — {@code DigestSource} reads it
     * when the lockfile is written.
     *
     * <p>A {@code local:} repository's files are the one thing the durable index
     * must not answer for: a vendored jar or pom is edited in place (that is how
     * the self-bootstrap channel gets updated), and the index only knows "this
     * URL hashed to X last time", so a hit would serve stale bytes forever.
     * Online, those URLs are re-read and re-hashed on every run; offline, the
     * index is still consulted, because it is the only way a moved checkout can
     * find bytes it already has (SPEC §1.5).
     */
    public Path ensure(String url, Kind kind) {
        boolean localFile = url.startsWith("file:");
        String key = cacheKey(url);
        if (!localFile) {
            String known = digestByUrl.get(key);
            if (known != null) {
                Path cached = root.resolve(known + "." + kind.extension());
                if (Files.exists(cached)) {
                    return cached;  // this session already fetched this URL
                }
            }
        }
        // The durable index answers the same question across processes. A hit
        // means the bytes are on disk AND we know what they hashed to — no
        // network, no re-hash.
        if (offline || !localFile) {
            String indexed = index.lookup(url, kind.extension(), root);
            if (indexed != null && localFile && offline && !indexMatchesLocal(url, indexed)) {
                // the relative key is portable across checkouts only while the
                // file behind it still hashes to the recorded digest; a sibling
                // module's file with the same relative tail must not answer
                indexed = null;
            }
            if (indexed != null) {
                digestByUrl.put(key, indexed);
                return root.resolve(indexed + "." + kind.extension());
            }
        }
        if (offline) {
            throw new StoreException(url + " is not in the store and --offline forbids "
                    + "fetching it — run mjm lock without --offline once to fill the store");
        }
        byte[] bytes = fetch(url);
        String sha = sha256(bytes);
        digestByUrl.put(key, sha);
        index.record(url, sha);
        Path target = root.resolve(sha + "." + kind.extension());
        if (Files.exists(target)) {
            return target;  // content-addressed: same digest, same bytes, already stored
        }
        Path lockFile = locks.resolve(sha256(url.getBytes()) + ".lock");
        try {
            Files.createDirectories(root);
            try (FileChannel channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.READ);
                 var ignored = channel.lock()) {
                if (!Files.exists(target)) {
                    Path temp = Files.createTempFile(root, "part-", ".tmp");
                    Files.write(temp, bytes);
                    Files.setPosixFilePermissions(temp, READ_PERMISSIONS);
                    try {
                        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException e) {
                        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            return target;
        } catch (IOException e) {
            throw new StoreException("cannot store " + url, e);
        }
    }

    /**
     * The store path of an already-pinned digest — the lock-hit fast path
     * (SPEC.md §4.2 #7): no resolution, no download, one existence check.
     */
    public Path findByDigest(String sha256, Kind kind) {
        Path target = root.resolve(sha256 + "." + kind.extension());
        return Files.exists(target) ? target : null;
    }

    /**
     * Every locked jar, verified byte-for-byte against its pinned sha256 —
     * the shared pre-build/package gate (SPEC.md §4.2 #2). Throws with the
     * next action when the store is missing a file or a copy drifted.
     */
    public Map<String, Path> verifiedJars(List<LockedArtifact> locked) {
        return verifiedJars(locked, artifact -> false);
    }

    /**
     * The jar map, skipping rows the caller has replaced by something else —
     * a workspace module supplies its own classes, so demanding its locked jar
     * from the store would fail a build that never needs it.
     */
    public Map<String, Path> verifiedJars(List<LockedArtifact> locked,
                                          java.util.function.Predicate<LockedArtifact> skip) {
        Map<String, Path> jars = new LinkedHashMap<>();
        for (LockedArtifact artifact : locked) {
            if (skip.test(artifact)) {
                continue;
            }
            Path jar = findByDigest(artifact.sha256(), Kind.JAR);
            if (jar == null) {
                throw new StoreException(artifact.id() + "@" + artifact.version()
                        + " is not in the store — run mjm lock");
            }
            if (!sha256(jar).equals(artifact.sha256())) {
                throw new StoreException("the store copy of " + artifact.id() + "@"
                        + artifact.version() + " does not match the locked sha256 — the store "
                        + "is corrupted; run mjm cache purge, then mjm lock");
            }
            jars.put(artifact.id(), jar);
        }
        return jars;
    }

    /** The digest recorded for a URL this session downloaded. */
    public String digestOf(String url) {
        return digestByUrl.get(cacheKey(url));
    }

    /**
     * The identity of a URL for the session cache and the durable index. A
     * relative {@code file:vendor/…} row means "this path inside <em>the</em>
     * project", so two workspace modules that write the same relative tail point
     * at different bytes — the key carries the project root, or the second
     * module would silently receive the first one's artifact. Absolute URLs are
     * already unique and stay as written.
     */
    /** Whether the file a relative {@code file:} row names still has this digest. */
    private static boolean indexMatchesLocal(String url, String sha) {
        try {
            Path source = com.jujin.mjm.resolve.ArtifactLocations.localPath(
                    url, com.jujin.mjm.resolve.ArtifactLocations.projectRoot());
            return Files.isRegularFile(source) && sha256(Files.readAllBytes(source)).equals(sha);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static String cacheKey(String url) {
        if (!url.startsWith("file:") || url.startsWith("file://")) {
            return url;
        }
        Path absolute = com.jujin.mjm.resolve.ArtifactLocations.localPath(
                url, com.jujin.mjm.resolve.ArtifactLocations.projectRoot());
        return "file:" + absolute;
    }

    /** The text at {@code url}, through the store. */
    public String readText(String url, Kind kind) {
        try {
            return Files.readString(ensure(url, kind));
        } catch (IOException e) {
            throw new StoreException("cannot read " + url + " from the store", e);
        }
    }

    private byte[] fetch(String url) {
        if (url.startsWith("file:")) {
            // A lock row may be relative (file:vendor/…): expand it against the
            // project root. The URL string itself — exactly as the lock wrote it —
            // stays the index key, so a store warmed in one checkout answers in
            // another.
            try {
                return Files.readAllBytes(
                        com.jujin.mjm.resolve.ArtifactLocations.localPath(
                                url, com.jujin.mjm.resolve.ArtifactLocations.projectRoot()));
            } catch (IOException | IllegalArgumentException e) {
                throw new StoreException("cannot read " + url, e);
            }
        }
        URI uri = URI.create(url);
        // https everywhere, except plain http on the loopback — the local
        // test/dev channel never crosses a network.
        boolean privateHttp = com.jujin.mjm.project.Repository.isLocalNetworkHttp(url);
        if (!url.startsWith("https://") && !privateHttp) {
            throw new StoreException("only https, file: and private-network http URLs are "
                    + "fetched, got " + url);
        }
        try {
            HttpResponse<byte[]> response = http.send(
                    HttpRequest.newBuilder(uri).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new StoreException(url + " answered " + response.statusCode()
                        + " (mjm pins exact released artifacts)");
            }
            return response.body();
        } catch (IOException e) {
            throw new StoreException("download failed: " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StoreException("download interrupted: " + url, e);
        }
    }

    /** The sha256 of bytes, as 64 lowercase hex characters. */
    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available on a JDK", e);
        }
    }

    /** The sha256 of a file's bytes, as 64 lowercase hex characters. */
    public static String sha256(Path file) {
        try {
            return sha256(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
