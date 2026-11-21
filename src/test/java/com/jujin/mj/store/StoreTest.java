package com.jujin.mj.store;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The store's contract (DESIGN.md §6.1): content-addressed files, one fetch
 * per URL, read-only once written, https everywhere except the loopback.
 */
class StoreTest {

    @TempDir
    Path temp;

    @Test
    void downloadsOncePerUrlAndStoresByDigest() throws Exception {
        byte[] jar = "jar-bytes-1".getBytes();
        HttpServer server = serve("/lib-a-1.0.0.jar", jar);
        try {
            ArtifactStore store = new ArtifactStore(temp.resolve("store"));
            String url = "http://localhost:" + server.getAddress().getPort() + "/lib-a-1.0.0.jar";

            Path stored = store.ensure(url, ArtifactStore.Kind.JAR);
            assertEquals(ArtifactStore.sha256(jar),
                    stored.getFileName().toString().replace(".jar", ""),
                    "the store file is named by the content digest");
            assertEquals(ArtifactStore.sha256(jar), store.digestOf(url));
            assertEquals("r-xr-xr-x", PosixFilePermissions.toString(
                            Files.getPosixFilePermissions(stored)),
                    "a stored artifact is read-only and executable-as-zip");
            assertTrue(Files.readString(stored).startsWith("jar-bytes"));

            // a second ensure must not reach the network again
            store.ensure(url, ArtifactStore.Kind.JAR);
            assertEquals(1, requests.get(), "the store answers the second lookup from disk");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fileUrlsCopyThroughTheStoreWithTheSameDigestDiscipline() throws Exception {
        Path local = temp.resolve("fixture.jar");
        Files.writeString(local, "local-jar-bytes");
        ArtifactStore store = new ArtifactStore(temp.resolve("store"));

        Path stored = store.ensure(local.toUri().toString(), ArtifactStore.Kind.JAR);

        assertEquals(ArtifactStore.sha256(local), store.digestOf(local.toUri().toString()));
        assertEquals(-1L, Files.mismatch(local, stored), "the bytes are copied verbatim");
        assertTrue(Files.exists(stored));
    }

    @Test
    void plainHttpOutsideTheLoopbackIsRejected() {
        ArtifactStore store = new ArtifactStore(temp.resolve("store"));
        StoreException ex = assertThrows(StoreException.class,
                () -> store.ensure("http://example.com/lib-1.0.jar", ArtifactStore.Kind.JAR));
        assertTrue(ex.getMessage().contains("only https"), "got: " + ex.getMessage());
    }

    @Test
    void aMissingRemoteFileIsANamedFailure() throws Exception {
        HttpServer server = serve("/missing.jar", "x".getBytes());
        try {
            ArtifactStore store = new ArtifactStore(temp.resolve("store"));
            String url = "http://localhost:" + server.getAddress().getPort() + "/gone.jar";
            StoreException ex = assertThrows(StoreException.class,
                    () -> store.ensure(url, ArtifactStore.Kind.JAR));
            assertTrue(ex.getMessage().contains("answered 404"), "got: " + ex.getMessage());
        } finally {
            server.stop(0);
        }
    }

    private HttpServer serve(String path, byte[] body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(path, exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return server;
    }

    private final AtomicInteger requests = new AtomicInteger();
}
