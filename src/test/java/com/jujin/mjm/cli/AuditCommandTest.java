package com.jujin.mjm.cli;

import com.jujin.freeway.commons.json.JsonUtils;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code mjm audit} against a loopback OSV — the real database is never contacted. */
class AuditCommandTest {

    private static final String ADVISORY = "GHSA-3pxv-7cmr-fjr4";

    @TempDir
    Path dir;

    private HttpServer server;
    private final Set<String> vulnerable = new HashSet<>();
    private final List<String> queries = new ArrayList<>();
    private volatile boolean failBatch;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        System.setProperty("mjm.osv.host",
                "http://localhost:" + server.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        System.clearProperty("mjm.osv.host");
    }

    @Test
    void aVulnerableArtifactIsNamedWithSeveritySummaryAndFix() throws Exception {
        lock(central("com.example:vulnerable", "1.2.3"), central("com.example:clean", "1.0.0"));
        vulnerable.add("com.example:vulnerable");

        Result result = audit();

        assertEquals(1, result.exit());
        assertTrue(result.out().contains("com.example:vulnerable 1.2.3"), result.out());
        assertTrue(result.out().contains(ADVISORY), result.out());
        assertTrue(result.out().contains("[MODERATE]"), result.out());
        assertTrue(result.out().contains("Silent log event loss"), result.out());
        assertTrue(result.out().contains("(fixed in 2.25.4)"), result.out());
        assertFalse(result.out().contains("com.example:clean"),
                "a clean artifact is not listed: " + result.out());
    }

    @Test
    void jsonIsTheStableDocumentCiReads() throws Exception {
        lock(central("com.example:vulnerable", "1.2.3"));
        vulnerable.add("com.example:vulnerable");

        Result result = audit("--json");

        assertEquals(1, result.exit());
        var document = JsonUtils.parseObject(result.out().strip());
        assertEquals(1, document.getInt("schemaVersion"));
        assertEquals("audit", document.getString("command"));
        assertEquals("failed", document.getString("status"));
        assertEquals(1, document.getInt("exitCode"));
        Map<String, Object> root = document.toMap();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) root.get("vulnerabilities");
        assertEquals(1, entries.size());
        assertEquals("com.example:vulnerable", entries.getFirst().get("id"));
        assertEquals("1.2.3", entries.getFirst().get("version"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> advisories =
                (List<Map<String, Object>>) entries.getFirst().get("advisories");
        Map<String, Object> advisory = advisories.getFirst();
        assertEquals(ADVISORY, advisory.get("id"));
        assertEquals("MODERATE", advisory.get("severity"));
        assertTrue(String.valueOf(advisory.get("summary")).contains("Silent log event loss"));
        assertEquals("2.25.4", advisory.get("fixed"));
        assertEquals(List.of("CVE-2025-1234"), advisory.get("aliases"));
        assertTrue(((List<?>) root.get("failures")).isEmpty());
    }

    @Test
    void anMjNativeArtifactIsNotCheckedRatherThanClean() throws Exception {
        lock("""
            [[artifacts]]
            id = "owner/repo"
            version = "1.0.0"
            source = "gh:owner/repo"
            url = "https://github.com/owner/repo/releases/download/v1.0.0/repo-1.0.0.jar"
            sha256 = "%s"
            depth = 1
            """.formatted("d".repeat(64)));

        Result result = audit("--json");

        assertEquals(0, result.exit(), "not checked is not vulnerable");
        Map<String, Object> root = JsonUtils.parseObject(result.out().strip()).toMap();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> notChecked = (List<Map<String, Object>>) root.get("notChecked");
        assertEquals(1, notChecked.size());
        assertEquals("owner/repo", notChecked.getFirst().get("id"));
        assertEquals("native-release", notChecked.getFirst().get("reason"));
        assertTrue(queries.isEmpty(), "no OSV query was made for it");
    }

    @Test
    void devArtifactsAreScannedOnlyWithTheFlag() throws Exception {
        lock(central("com.example:main-lib", "1.0.0"),
                central("com.example:dev-lib", "1.0.0", true));
        vulnerable.add("com.example:dev-lib");

        Result without = audit("--json");
        assertEquals(0, without.exit(), without.out());
        assertFalse(queries.contains("com.example:dev-lib"),
                "dev is skipped by default: " + queries);

        queries.clear();
        Result with = audit("--json", "--dev");
        assertEquals(1, with.exit(), with.out());
        assertTrue(queries.contains("com.example:dev-lib"), queries.toString());
    }

    @Test
    void aFailedQueryIsNamedAndNeverCalledClean() throws Exception {
        lock(central("com.example:vulnerable", "1.2.3"));
        failBatch = true;

        Result result = audit();

        assertEquals(1, result.exit());
        assertTrue(result.out().contains("failures:"), result.out());
        assertTrue(result.out().contains("com.example:vulnerable"), result.out());
        assertTrue(result.out().contains("500"), result.out());
        assertFalse(result.out().contains("no known vulnerabilities"),
                "a failed query cannot claim the closure is clean: " + result.out());
    }

    @Test
    void offlineIsRefusedBeforeAnythingIsRead() throws Exception {
        lock(central("com.example:vulnerable", "1.2.3"));

        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        PrintStream original = System.err;
        int exit;
        try {
            System.setErr(new PrintStream(errors, true, StandardCharsets.UTF_8));
            exit = new CommandRunner(List.of(new AuditCommand(dir))).run("audit", "--offline");
        } finally {
            System.setErr(original);
        }

        assertEquals(2, exit);
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("needs the network by design"),
                errors.toString());
    }

    // ------------------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/v1/querybatch")) {
            String body = new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8);
            List<String> names = names(body);
            queries.addAll(names);
            if (failBatch) {
                respond(exchange, 500, "{\"message\":\"boom\"}");
                return;
            }
            StringBuilder results = new StringBuilder("{\"results\":[");
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    results.append(',');
                }
                results.append(vulnerable.contains(names.get(i))
                        ? "{\"vulns\":[{\"id\":\"" + ADVISORY + "\",\"modified\":\"2026-01-01\"}]}"
                        : "{}");
            }
            results.append("]}");
            respond(exchange, 200, results.toString());
            return;
        }
        if (path.startsWith("/v1/vulns/")) {
            respond(exchange, 200, """
                {"id":"%s",
                 "summary":"Apache Log4j Core: Silent log event loss",
                 "aliases":["CVE-2025-1234"],
                 "database_specific":{"severity":"MODERATE"},
                 "severity":[{"type":"CVSS_V4","score":"CVSS:4.0/AV:N"}],
                 "affected":[{"ranges":[{"type":"ECOSYSTEM",
                   "events":[{"introduced":"0"},{"fixed":"1.0.0"},{"fixed":"2.25.4"}]}]}]}
                """.formatted(ADVISORY));
            return;
        }
        respond(exchange, 404, "{\"message\":\"not found\"}");
    }

    private static List<String> names(String body) {
        List<String> names = new ArrayList<>();
        Map<String, Object> root = JsonUtils.parseObject(body).toMap();
        if (root.get("queries") instanceof List<?> list) {
            for (Object query : list) {
                if (query instanceof Map<?, ?> map && map.get("package") instanceof Map<?, ?> pkg) {
                    names.add(String.valueOf(pkg.get("name")));
                }
            }
        }
        return names;
    }

    private static void respond(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void lock(String... rows) throws IOException {
        Files.writeString(dir.resolve("mjm.lock"),
                "# generated by mjm — commit to version control, do not edit by hand\n"
                        + String.join("\n", rows));
    }

    private static String central(String id, String version) {
        return central(id, version, false);
    }

    private static String central(String id, String version, boolean dev) {
        return """
            [[artifacts]]
            id = "%s"
            version = "%s"
            source = "central"
            url = "https://repo.maven.apache.org/maven2/%s/%s/%s.jar"
            sha256 = "%s"
            depth = 1
            %s
            """.formatted(id, version, id.replace(':', '/'), version, version,
                "e".repeat(64), dev ? "scope = \"dev\"" : "");
    }

    private Result audit(String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            exit = new AuditCommand(dir).execute(List.of(args));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new Result(exit, out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
    }

    private record Result(int exit, String out, String err) {}
}
