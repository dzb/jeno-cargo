package com.jujin.jeno.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The store's URL index: a durable mapping from a download URL to the digest
 * of the bytes that URL served.
 *
 * <p>The content-addressed store itself answers "where are the bytes for this
 * digest"; it cannot answer "what digest does this URL have" without fetching
 * again. Without that reverse mapping every run re-downloads every pom — the
 * in-memory {@code digestByUrl} map only lives for one process — so a lock in a
 * fresh JVM pays full network cost even when every byte is already on disk.
 *
 * <p>One small file per URL, named by the URL's sha256 and containing the
 * digest. Naming by URL digest keeps the file name filesystem-safe for any
 * URL, and a digest-shaped content check catches a truncated or hand-edited
 * entry.
 *
 * <p>An index entry is only trusted together with the artifact it points at:
 * a hit whose target file is missing is treated as a miss, because the index
 * describes what was fetched, not what is present.
 */
final class UrlIndex {

    /** Where index entries live — {@code <store>/index/}. */
    private final Path directory;

    UrlIndex(Path storeRoot) {
        this.directory = storeRoot.resolve("index");
    }

    /**
     * The digest recorded for {@code url}, or null when the URL was never
     * fetched or its artifact has since been removed.
     *
     * @param kindExtension the extension the artifact is stored under; the
     *                      target must exist for the hit to count
     */
    String lookup(String url, String kindExtension, Path storeRoot) {
        Path entry = entry(url);
        if (!Files.exists(entry)) {
            return null;
        }
        try {
            String digest = Files.readString(entry, StandardCharsets.US_ASCII).strip();
            if (!digest.matches("[0-9a-f]{64}")) {
                return null;  // corrupt entry: treat as absent, never fail the build
            }
            // the mapping is only meaningful while the bytes are still here
            return Files.exists(storeRoot.resolve(digest + "." + kindExtension))
                    ? digest : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Records that {@code url} served bytes with this digest. Best-effort. */
    void record(String url, String digest) {
        try {
            Files.createDirectories(directory);
            Path entry = entry(url);
            Path temp = Files.createTempFile(directory, "idx-", ".tmp");
            Files.writeString(temp, digest + "\n", StandardCharsets.US_ASCII);
            // atomic: a half-written entry would be a wrong answer, not a slow one
            try {
                Files.move(temp, entry, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, entry, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // The index is an optimization: failing to write it must not fail
            // a fetch that already succeeded. The next run simply re-downloads.
        }
    }

    private Path entry(String url) {
        return directory.resolve(hash(url) + ".url");
    }

    private static String hash(String url) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(url.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available on a JDK", e);
        }
    }
}
