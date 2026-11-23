package com.jujin.jeno.cli;

import com.jujin.jeno.store.ArtifactStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code mjm publish gh:} against a loopback API: a 422 is whatever GitHub says
 * it is (an empty repository is not "the release already exists"), and a 422
 * with a real release behind it still attaches instead of failing.
 */
class PublishGhTest {

    private static final String EMPTY_REPOSITORY = """
        {"message":"Validation Failed","errors":[{"code":"custom",
         "message":"Repository is empty."}],"status":"422"}
        """;

    @TempDir
    Path dir;

    private HttpServer server;
    private final AtomicInteger assetUploads = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        ProjectLock.useRoot(dir.resolve(".locks"));
        project();
        ArtifactStore store = new ArtifactStore(dir.resolve("store"));
        assertEquals(0, new BuildCommand(store, dir).execute(List.of()));
        assertEquals(0, new PackageCommand(store, dir).execute(List.of()));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        System.clearProperty("mjm.github.api");
        System.clearProperty("mjm.github.uploads");
        System.clearProperty("mjm.github.token");
        ProjectLock.useUserRoot();
    }

    @Test
    void anEmptyRepositoryReportsTheApisOwnReason() throws Exception {
        start(false);
        System.setProperty("mjm.github.token", "test-token");

        Result result = publish();

        assertEquals(1, result.exit());
        assertTrue(result.err().contains("Repository is empty."),
                "the API's own message survives: " + result.err());
        assertTrue(result.err().contains("(422)"), result.err());
        assertFalse(result.err().contains("already exists"),
                "a 422 that is not an existing release never claims one: " + result.err());
        assertEquals(0, assetUploads.get(), "nothing was uploaded");
    }

    @Test
    void a422WithAnExistingReleaseStillAttaches() throws Exception {
        start(true);
        System.setProperty("mjm.github.token", "test-token");

        Result result = publish();

        assertEquals(0, result.exit(), result.out() + result.err());
        assertTrue(result.out().contains("already exists"), result.out());
        assertTrue(result.out().contains("attaching"), result.out());
        assertEquals(3, assetUploads.get(), "jar, descriptor and checksum went up");
    }

    // ------------------------------------------------------------------

    private void project() throws IOException {
        Files.createDirectories(dir.resolve("src/main/java/demo"));
        Files.writeString(dir.resolve("mjm.toml"), """
            [project]
            name = "widget"
            group = "com.acme"
            version = "1.2.0"
            """);
        Files.writeString(dir.resolve("src/main/java/demo/App.java"),
                "package demo; public final class App {}");
        Files.writeString(dir.resolve("mjm.lock"), "");
    }

    private void start(boolean releaseExists) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            exchange.getRequestBody().readAllBytes();
            if (path.endsWith("/releases") && "POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 422, EMPTY_REPOSITORY);
                return;
            }
            if (path.contains("/releases/tags/")) {
                respond(exchange, releaseExists ? 200 : 404,
                        releaseExists ? "{\"id\":7,\"tag_name\":\"v1.2.0\"}" : "{\"message\":\"Not Found\"}");
                return;
            }
            if (path.contains("/assets")) {
                assetUploads.incrementAndGet();
                respond(exchange, 201, "{\"id\":1}");
                return;
            }
            respond(exchange, 404, "{\"message\":\"Not Found\"}");
        });
        server.start();
        String base = "http://localhost:" + server.getAddress().getPort();
        System.setProperty("mjm.github.api", base);
        System.setProperty("mjm.github.uploads", base);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private Result publish() {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            exit = new PublishCommand(dir).execute(List.of("gh:owner/repo"));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new Result(exit, out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
    }

    private record Result(int exit, String out, String err) {}
}
