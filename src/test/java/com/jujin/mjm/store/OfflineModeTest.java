package com.jujin.mjm.store;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code --offline}: the process may not reach the network. A store hit is
 * served; a miss is an error that names what is missing. Never a silent
 * fallback to fetching, never a partial build.
 *
 * <p>Offline is static state, so each test toggles it explicitly and the
 * suite's order does not matter only because {@link OfflineModeTest#reset()}
 * is called on the way out — see the {@code @AfterEach}.
 */
class OfflineModeTest {

    @TempDir
    Path store;

    @org.junit.jupiter.api.AfterEach
    void reset() {
        // the flag is process-wide; leaving it set would poison other tests
        java.lang.reflect.Field field;
        try {
            field = ArtifactStore.class.getDeclaredField("offline");
            field.setAccessible(true);
            field.set(null, false);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot reset the offline flag", e);
        }
    }

    @Test
    void aStoreHitIsServedWithoutTheNetwork() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = serve("/a.pom", "a".getBytes(), requests);
        String url = "http://localhost:" + server.getAddress().getPort() + "/a.pom";
        try {
            // fill the store first, online
            Path stored = new ArtifactStore(store).ensure(url, ArtifactStore.Kind.POM);
            assertNotNull(stored);
            assertEquals(1, requests.get());

            // then forbid the network: the same URL must come from the index
            ArtifactStore.goOffline();
            Path again = new ArtifactStore(store).ensure(url, ArtifactStore.Kind.POM);
            assertEquals(stored, again);
            assertEquals(1, requests.get(), "offline served it without a request");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aMissUnderOfflineNamesWhatIsMissing() throws Exception {
        HttpServer server = serve("/b.pom", "b".getBytes(), new AtomicInteger());
        String url = "http://localhost:" + server.getAddress().getPort() + "/b.pom";
        try {
            ArtifactStore.goOffline();
            StoreException error = assertThrows(StoreException.class,
                    () -> new ArtifactStore(store).ensure(url, ArtifactStore.Kind.POM));
            assertTrue(error.getMessage().contains(url),
                    "the message names the URL that is missing: " + error.getMessage());
            assertTrue(error.getMessage().contains("--offline"),
                    "and the flag that caused it: " + error.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void offlineIsOffByDefault() {
        assertTrue(!ArtifactStore.isOffline(), "a fresh process may use the network");
    }

    private static HttpServer serve(String path, byte[] body, AtomicInteger counter)
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(path, exchange -> {
            counter.incrementAndGet();
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return server;
    }
}
