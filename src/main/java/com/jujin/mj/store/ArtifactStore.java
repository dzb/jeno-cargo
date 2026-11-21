package com.jujin.mj.store;

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
 * <p>Downloads are the JDK's own {@link HttpClient} for https URLs;
 * {@code file://} URLs (a {@code local:} repository) are read straight from
 * disk — the copy through the store still pins the sha256, so a local
 * bootstrap is exactly as tamper-evident as a remote one.
 */
public final class ArtifactStore {

    /** What kind of bytes a URL holds — decides the store file's extension. */
    public enum Kind {
        JAR("jar"), POM("pom"), DESCRIPTOR("mj.toml");

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
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    /** The digests of everything this session downloaded — the lockfile's source of truth. */
    private final Map<String, String> digestByUrl = new ConcurrentHashMap<>();

    public ArtifactStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
        this.locks = this.root.resolve("locks");
        try {
            Files.createDirectories(this.locks);
        } catch (IOException e) {
            throw new StoreException("cannot create the store at " + this.root, e);
        }
    }

    /** The store under the current user's home: {@code ~/.mj/store}. */
    public static ArtifactStore userStore() {
        return new ArtifactStore(Path.of(System.getProperty("user.home"), ".mj", "store"));
    }

    /**
     * Ensures the content at {@code url} is in the store and returns its path.
     * The digest is remembered for the session — {@code DigestSource} reads it
     * when the lockfile is written.
     */
    public Path ensure(String url, Kind kind) {
        String known = digestByUrl.get(url);
        if (known != null) {
            Path cached = root.resolve(known + "." + kind.extension());
            if (Files.exists(cached)) {
                return cached;  // this session already fetched this URL
            }
        }
        byte[] bytes = fetch(url);
        String sha = sha256(bytes);
        digestByUrl.put(url, sha);
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
        Map<String, Path> jars = new LinkedHashMap<>();
        for (LockedArtifact artifact : locked) {
            Path jar = findByDigest(artifact.sha256(), Kind.JAR);
            if (jar == null) {
                throw new StoreException(artifact.id() + "@" + artifact.version()
                        + " is not in the store — run mj lock");
            }
            if (!sha256(jar).equals(artifact.sha256())) {
                throw new StoreException("the store copy of " + artifact.id() + "@"
                        + artifact.version() + " does not match the locked sha256 — the store "
                        + "is corrupted; delete ~/.mj/store and run mj lock");
            }
            jars.put(artifact.id(), jar);
        }
        return jars;
    }

    /** The digest recorded for a URL this session downloaded. */
    public String digestOf(String url) {
        return digestByUrl.get(url);
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
        URI uri = URI.create(url);
        if (url.startsWith("file://")) {
            try {
                return Files.readAllBytes(Path.of(uri));
            } catch (IOException | IllegalArgumentException e) {
                throw new StoreException("cannot read " + url, e);
            }
        }
        // https everywhere, except plain http on the loopback — the local
        // test/dev channel never crosses a network.
        boolean loopbackHttp = url.startsWith("http://")
                && (uri.getHost().equals("localhost") || uri.getHost().equals("127.0.0.1"));
        if (!url.startsWith("https://") && !loopbackHttp) {
            throw new StoreException("only https, file:// and loopback http URLs are fetched, got "
                    + url);
        }
        try {
            HttpResponse<byte[]> response = http.send(
                    HttpRequest.newBuilder(uri).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new StoreException(url + " answered " + response.statusCode()
                        + " (mj pins exact released artifacts)");
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
