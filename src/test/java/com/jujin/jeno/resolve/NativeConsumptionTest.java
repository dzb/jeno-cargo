package com.jujin.jeno.resolve;

import com.jujin.jeno.project.JenoProject;
import com.jujin.jeno.project.ProjectReader;
import com.jujin.jeno.pom.PomSource;
import com.jujin.jeno.project.DescriptorSource;
import com.jujin.jeno.store.ArtifactStore;
import com.jujin.jeno.store.LockFileReader;
import com.jujin.jeno.store.LockedArtifact;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gh: consumption chain end to end against a server that speaks the
 * GitHub release URL layout — descriptor, jar, transitive descriptor, all
 * downloaded through the store and pinned in mjm.lock. The host override
 * ({@code -Dmjm.github.host}) is what lets this run on a loopback server;
 * the URL shapes are the production shapes.
 */
class NativeConsumptionTest {

    @TempDir
    Path dir;

    @Test
    void ghChainResolvesThroughTheStoreAndLocks() throws Exception {
        byte[] mylibJar = "mylib-jar-bytes".getBytes();
        byte[] mylib2Jar = "mylib2-jar-bytes".getBytes();
        String mylibDescriptor = """
            [project]
            name = "mylib"
            version = "1.0.0"

            [deps]
            mylib2 = "gh:acme/mylib2@v0.1.0"
            """;
        String mylib2Descriptor = """
            [project]
            name = "mylib2"
            version = "0.1.0"
            """;

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        Map<String, byte[]> routes = new LinkedHashMap<>();
        routes.put("/acme/mylib/releases/download/v1.0.0/mylib-1.0.0.mjm.toml",
                mylibDescriptor.getBytes());
        routes.put("/acme/mylib/releases/download/v1.0.0/mylib-1.0.0.jar", mylibJar);
        routes.put("/acme/mylib2/releases/download/v0.1.0/mylib2-0.1.0.mjm.toml",
                mylib2Descriptor.getBytes());
        routes.put("/acme/mylib2/releases/download/v0.1.0/mylib2-0.1.0.jar", mylib2Jar);
        server.createContext("/", exchange -> {
            byte[] body = routes.get(exchange.getRequestURI().getPath());
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        String overridden = "http://localhost:" + server.getAddress().getPort();
        System.setProperty("mjm.github.host", overridden);
        try {
            JenoProject project = ProjectReader.parse("""
                [project]
                name = "demo"
                version = "0.1.0"

                [deps]
                mylib = "gh:acme/mylib@v1.0.0"
                """);

            ArtifactStore store = new ArtifactStore(dir.resolve("store"));
            PomSource pomSource = (g, a, v, base) -> {
                throw new AssertionError("a pure gh: chain must not touch Central");
            };
            ResolvedGraph graph = Resolver.resolve(project, pomSource, url -> {
                // the teeth: a hardcoded github.com still parses here (the route
                // map is keyed by path), so assert the host the request went to
                if (!url.startsWith(overridden)) {
                    throw new AssertionError("the descriptor URL must use the overridden "
                            + "host " + overridden + ", got: " + url);
                }
                return new String(routes.getOrDefault(
                        java.net.URI.create(url).getPath(), new byte[0]));
            });

            assertEquals(2, graph.artifacts().size());
            assertEquals(1, graph.direct().size());

            // the lock command's download step, applied to the closure
            for (var artifact : graph.artifacts()) {
                store.ensure(artifact.jarUrl(), ArtifactStore.Kind.JAR);
                store.ensure(artifact.metadataUrl(), ArtifactStore.Kind.DESCRIPTOR);
            }
            // the production digest source reads the session records
            String lock = LockFile.serialize(graph, store::digestOf);
            Files.writeString(dir.resolve("mjm.lock"), lock);

            // the lock-hit path: both jars are in the store under their digests
            for (LockedArtifact artifact : LockFileReader.read(
                    Files.readString(dir.resolve("mjm.lock")))) {
                Path jar = store.findByDigest(artifact.sha256(), ArtifactStore.Kind.JAR);
                assertNotNull(jar, artifact.id() + " must be in the store");
                assertEquals(artifact.sha256(), ArtifactStore.sha256(jar));
            }
            assertEquals("mylib-jar-bytes",
                    new String(Files.readAllBytes(store.findByDigest(
                            ArtifactStore.sha256(mylibJar), ArtifactStore.Kind.JAR))));
        } finally {
            server.stop(0);
            System.clearProperty("mjm.github.host");
        }
    }
}
