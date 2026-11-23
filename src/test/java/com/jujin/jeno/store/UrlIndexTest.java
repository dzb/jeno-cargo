package com.jujin.jeno.store;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The store's URL index: a second process must not re-download what the first
 * one already stored. Without it, {@code digestByUrl} dies with the process and
 * every {@code mjm lock} pays full network cost even when the bytes are on disk.
 */
class UrlIndexTest {

    @TempDir
    Path store;

    @Test
    void aSecondStoreInstanceReusesTheIndexInsteadOfRefetching() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        byte[] body = "pom-bytes".getBytes();
        server.createContext("/x.pom", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        String url = "http://localhost:" + server.getAddress().getPort() + "/x.pom";

        try {
            // first store instance: fetches and records
            Path first = new ArtifactStore(store).ensure(url, ArtifactStore.Kind.POM);
            assertEquals(1, requests.get(), "the first call fetches");
            assertTrue(Files.exists(first));

            // a fresh instance stands in for a new process: its in-memory map
            // is empty, so only the persisted index can save the request
            Path second = new ArtifactStore(store).ensure(url, ArtifactStore.Kind.POM);
            assertEquals(1, requests.get(),
                    "the second instance answers from the index, not the network");
            assertEquals(first, second);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anIndexEntryWhoseArtifactIsGoneIsAMiss() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        byte[] body = "bytes".getBytes();
        server.createContext("/y.pom", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        String url = "http://localhost:" + server.getAddress().getPort() + "/y.pom";

        try {
            Path stored = new ArtifactStore(store).ensure(url, ArtifactStore.Kind.POM);
            Files.delete(stored);  // the bytes vanished; the index still points at them

            Path again = new ArtifactStore(store).ensure(url, ArtifactStore.Kind.POM);
            assertEquals(2, requests.get(),
                    "a stale index entry must not masquerade as a cache hit");
            assertNotNull(again);
            assertTrue(Files.exists(again));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aCorruptIndexEntryFallsBackToFetching() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        byte[] body = "z".getBytes();
        server.createContext("/z.pom", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        String url = "http://localhost:" + server.getAddress().getPort() + "/z.pom";

        try {
            new ArtifactStore(store).ensure(url, ArtifactStore.Kind.POM);
            // corrupt every entry: the digest shape check must reject them
            try (var files = Files.list(store.resolve("index"))) {
                for (Path entry : files.toList()) {
                    Files.writeString(entry, "not-a-digest\n");
                }
            }
            new ArtifactStore(store).ensure(url, ArtifactStore.Kind.POM);
            assertEquals(2, requests.get(),
                    "a malformed entry is treated as absent, never trusted");
        } finally {
            server.stop(0);
        }
    }
}
