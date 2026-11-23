package com.jujin.mjm.cli;

import com.jujin.freeway.commons.json.JsonArray;
import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.mjm.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The agent-facing command contract is complete, stable and machine-readable. */
class CommandCatalogTest {

    @TempDir
    Path dir;

    @Test
    void fullCatalogDescribesAllTwentyTwoCommandsAndTheirCapabilities() {
        Result result = run("--json", "--help");

        assertEquals(0, result.exit(), result.out() + result.err());
        Map<String, Object> root = parse(result.out());
        assertEquals(1, root.get("schemaVersion"));
        assertEquals("commandCatalog", root.get("kind"));
        assertEquals(22, root.get("commandCount"));
        assertEquals(22, asList(root.get("commands")).size());

        Map<String, Map<String, Object>> commands = byName(root);
        assertEquals(Set.of(
                "add", "audit", "build", "bundle", "cache", "classpath", "clean",
                "doctor", "info", "init", "lock", "outdated", "package", "publish",
                "release", "remove", "run", "test", "tree", "upgrade", "verify", "why"),
                commands.keySet());

        Map<String, Object> verify = commands.get("verify");
        assertEquals(true, verify.get("supportsJson"));
        assertEquals(true, commands.get("doctor").get("supportsJson"));
        assertEquals(true, commands.get("lock").get("supportsJson"));
        assertEquals(true, commands.get("why").get("supportsJson"));
        assertEquals(true, commands.get("classpath").get("supportsJson"));
        assertEquals(true, commands.get("info").get("supportsJson"));
        assertEquals(true, commands.get("cache").get("supportsJson"));
        assertEquals(true, commands.get("build").get("supportsJson"));
        assertEquals(true, commands.get("package").get("supportsJson"));
        assertEquals(true, commands.get("bundle").get("supportsJson"));
        assertEquals(true, commands.get("clean").get("supportsJson"));
        assertEquals(true, commands.get("init").get("supportsJson"));
        assertEquals(true, commands.get("add").get("supportsJson"));
        assertEquals(true, commands.get("remove").get("supportsJson"));
        assertEquals(true, commands.get("upgrade").get("supportsJson"));
        assertEquals(true, commands.get("publish").get("supportsJson"));
        assertEquals(true, commands.get("release").get("supportsJson"));
        assertEquals(true, commands.get("test").get("supportsJson"));
        assertEquals("never", verify.get("network"));
        assertEquals("aggregate", verify.get("workspace"));
        assertEquals(List.of(), asList(verify.get("writes")));

        Map<String, Object> run = commands.get("run");
        assertEquals(true, run.get("passThrough"));
        assertEquals(false, run.get("supportsJson"));

        Map<String, Object> tree = commands.get("tree");
        assertEquals(true, tree.get("supportsJson"));
        assertEquals("optional", tree.get("network"));

        Map<String, Object> cache = commands.get("cache");
        assertEquals(true, cache.get("destructive"));
        assertEquals(true, cache.get("supportsDryRun"));
        assertEquals("conditional", cache.get("confirmation"));

        for (String name : List.of("build", "package", "bundle")) {
            assertEquals("never", commands.get(name).get("network"), name);
        }
        Map<String, Object> classpath = commands.get("classpath");
        assertEquals("never", classpath.get("network"));
        assertEquals(List.of("project"), asList(classpath.get("writes")));
        Map<String, Object> runCapabilities = commands.get("run");
        assertEquals("never", runCapabilities.get("network"));
        assertEquals(List.of(), asList(runCapabilities.get("writes")));
        Map<String, Object> test = commands.get("test");
        assertEquals("optional", test.get("network"));
        assertEquals(List.of("target", "cache"), asList(test.get("writes")));
        Map<String, Object> init = commands.get("init");
        assertEquals(List.of("project", "cache"), asList(init.get("writes")));

        Map<String, Object> release = commands.get("release");
        assertEquals(true, release.get("supportsDryRun"));
        assertEquals(List.of("target", "remote"), asList(release.get("writes")));
        assertEquals("requires-signature", release.get("confirmation"));
    }

    @Test
    void globalJsonIsForwardedToACommandThatDeclaresJsonSupport() {
        AtomicBoolean sawJson = new AtomicBoolean();
        Command probe = new Command() {
            @Override
            public String name() {
                return "probe";
            }

            @Override
            public String summary() {
                return "a JSON-capable test command";
            }

            @Override
            public CommandCapabilities capabilities() {
                return CommandCapabilities.builder().json().build();
            }

            @Override
            public int execute(List<String> args) {
                sawJson.set(args.contains("--json"));
                JsonResult.start("probe").emit(0);
                return 0;
            }
        };

        Result result = run(new CommandRunner(List.of(probe)), "--json", "probe");

        assertEquals(0, result.exit(), result.out() + result.err());
        assertTrue(sawJson.get(), "the global switch reaches the command that declared it");
    }

    @Test
    void aMissingJsonResultStillBecomesOneStructuredError() {
        Command probe = jsonProbe("probe", () -> 1);

        Result result = run(new CommandRunner(List.of(probe)), "--json", "probe");

        assertEquals(1, result.exit());
        Map<String, Object> root = parse(result.out());
        assertEquals("commandResult", root.get("kind"));
        assertEquals("failed", root.get("status"));
        Map<String, Object> diagnostic = asMap(asList(root.get("diagnostics")).getFirst());
        assertEquals("COMMAND_RESULT_MISSING", diagnostic.get("code"));
        assertTrue(result.err().isEmpty(), result.err());
    }

    @Test
    void anExceptionInJsonModeBecomesOneStructuredError() {
        Command probe = jsonProbe("probe", () -> {
            throw new IllegalStateException("boom");
        });

        Result result = run(new CommandRunner(List.of(probe)), "--json", "probe");

        assertEquals(1, result.exit());
        Map<String, Object> root = parse(result.out());
        assertEquals("failed", root.get("status"));
        Map<String, Object> diagnostic = asMap(asList(root.get("diagnostics")).getFirst());
        assertEquals("COMMAND_FAILED", diagnostic.get("code"));
        assertTrue(String.valueOf(diagnostic.get("message")).contains("boom"));
        assertTrue(result.err().isEmpty(), result.err());
    }

    private static Command jsonProbe(String name, java.util.function.IntSupplier action) {
        return new Command() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String summary() {
                return "a JSON-capable test command";
            }

            @Override
            public CommandCapabilities capabilities() {
                return CommandCapabilities.builder().json().build();
            }

            @Override
            public int execute(List<String> args) {
                return action.getAsInt();
            }
        };
    }

    @Test
    void unknownCommandUnderJsonIsAlsoStructured() {
        Result result = run("--json", "no-such-command");

        assertEquals(2, result.exit());
        Map<String, Object> root = parse(result.out());
        assertEquals("UNKNOWN_COMMAND",
                asMap(asList(root.get("diagnostics")).getFirst()).get("code"));
    }

    @Test
    void aSingleCommandHelpIsAJsonDescriptor() {
        Result result = run("--json", "verify", "--help");

        assertEquals(0, result.exit(), result.out() + result.err());
        Map<String, Object> root = parse(result.out());
        assertEquals("commandDescriptor", root.get("kind"));
        @SuppressWarnings("unchecked")
        Map<String, Object> command = (Map<String, Object>) root.get("command");
        assertEquals("verify", command.get("name"));
        assertEquals("publish", command.get("category"));
        assertEquals("verify direct declarations, store bytes, and any built release artifacts",
                command.get("summary"));
        assertTrue(asArray(command.get("aliases")).isEmpty());
        assertEquals(false, command.get("interactive"));
    }

    @Test
    void requestingJsonForAnUnsupportedCommandReturnsAStructuredError() {
        Result result = run("--json", "run");

        assertEquals(2, result.exit());
        Map<String, Object> root = parse(result.out());
        assertEquals("commandResult", root.get("kind"));
        assertEquals("error", root.get("status"));
        assertEquals(2, root.get("exitCode"));
        Map<String, Object> diagnostic = asMap(asList(root.get("diagnostics")).getFirst());
        assertEquals("JSON_UNSUPPORTED", diagnostic.get("code"));
        assertTrue(String.valueOf(diagnostic.get("message")).contains("run"));
    }

    @Test
    void jsonVersionIsAStandaloneDocument() {
        Result result = run("--json", "--version");

        assertEquals(0, result.exit(), result.out() + result.err());
        Map<String, Object> root = parse(result.out());
        assertEquals("version", root.get("kind"));
        assertEquals(1, root.get("schemaVersion"));
        assertEquals(Mjm.VERSION, root.get("mjmVersion"));
        assertNotNull(root.get("javaVersion"));
    }

    @Test
    void jsonCatalogNeverContainsAnsiEvenWhenColourIsForced() {
        System.setProperty("mjm.color", "always");
        try {
            Result result = run("--json", "--help");
            assertFalse(result.out().contains("\u001B["), "catalog stdout must stay pure JSON");
        } finally {
            System.clearProperty("mjm.color");
        }
    }

    @Test
    void humanHelpListsTheFormatJsonAlias() {
        Result result = run("--help");

        assertEquals(0, result.exit(), result.err());
        assertTrue(result.out().contains("--format=json"), result.out());
    }

    @Test
    void earlyJsonErrorsCarryDryRunAndOffline() {
        resetOffline();
        try {
            Map<String, Object> missing = parse(run("--json").out());
            assertEquals(false, missing.get("dryRun"));
            assertEquals(false, missing.get("offline"));

            Map<String, Object> unknown = parse(run("--json", "nosuch").out());
            assertEquals(false, unknown.get("dryRun"));
            assertEquals(false, unknown.get("offline"));

            Map<String, Object> unsupported = parse(run("--json", "run").out());
            assertEquals(false, unsupported.get("dryRun"));
            assertEquals(false, unsupported.get("offline"));

            Map<String, Object> offline = parse(run("--json", "--offline", "outdated").out());
            assertEquals(false, offline.get("dryRun"));
            assertEquals(true, offline.get("offline"));

            Map<String, Object> runDry = parse(
                    run("--json", "run", "app", "--dry-run").out());
            assertEquals(false, runDry.get("dryRun"));
            assertEquals(false, runDry.get("offline"));

            Map<String, Object> runOffline = parse(
                    run("--json", "run", "app", "--offline").out());
            assertEquals(false, runOffline.get("dryRun"));
            assertEquals(false, runOffline.get("offline"));
        } finally {
            resetOffline();
        }
    }

    @Test
    void coldStoreClasspathAndBuildDoNotDownloadOrWriteCache() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] body = "jar".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            Path project = Files.createDirectories(dir.resolve("cold-project"));
            int port = server.getAddress().getPort();
            String url = "http://127.0.0.1:" + port + "/com/example/lib/1.0.0/lib-1.0.0.jar";
            Files.writeString(project.resolve("mjm.toml"), """
                    [project]
                    name = "cold"
                    version = "0.1.0"

                    [deps]
                    "com.example:lib" = "1.0.0"
                    """);
            Files.writeString(project.resolve("mjm.lock"), """
                    [[artifacts]]
                    id = "com.example:lib"
                    version = "1.0.0"
                    source = "central"
                    url = "%s"
                    sha256 = "0000000000000000000000000000000000000000000000000000000000000000"
                    depth = 1
                    """.formatted(url));
            Path storeDir = dir.resolve("cold-store");
            ArtifactStore store = new ArtifactStore(storeDir);

            new ClasspathCommand(store, project).execute(List.of());
            assertEquals(0, requests.get(), "classpath must not fetch a missing jar");
            assertFalse(Files.exists(storeDir), "classpath must not write the cache");

            int buildExit = new BuildCommand(store, project).execute(List.of());
            assertTrue(buildExit != 0, "a cold store makes build fail with a next action");
            assertEquals(0, requests.get(), "build must report the missing jar, not download it");
            assertFalse(Files.exists(storeDir), "build must not fill the cache");
        } finally {
            server.stop(0);
        }
    }

    // ------------------------------------------------------------------

    private List<Command> commands() {
        Path project = dir.resolve("project");
        Path storePath = dir.resolve("store");
        ArtifactStore store = new ArtifactStore(storePath);
        LockService lockService = new LockService(store, null, null);
        return List.of(
                new InitCommand(project),
                new AddCommand(lockService, store, project),
                new RemoveCommand(lockService, project),
                new LockCommand(lockService, project),
                new BuildCommand(store, project),
                new TestCommand(store, project),
                new RunCommand(store, project),
                new PackageCommand(store, project),
                new BundleCommand(store, project),
                new CleanCommand(project),
                new TreeCommand(store, null, null, project),
                new WhyCommand(store, null, null, project),
                new InfoCommand(store, project),
                new ClasspathCommand(store, project),
                new OutdatedCommand(project),
                new AuditCommand(project),
                new DoctorCommand(project),
                new VerifyCommand(store, project),
                new PublishCommand(project),
                new ReleaseCommand(project),
                new CacheCommand(),
                new UpdateCommand());
    }

    private Result run(String... args) {
        return run(new CommandRunner(commands()), args);
    }

    private Result run(CommandRunner runner, String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            exit = runner.run(args);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new Result(exit, out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> parse(String json) {
        return JsonUtils.parseObject(json.strip()).toMap();
    }

    private static List<Object> asList(Object value) {
        if (value instanceof JsonArray array) {
            return array.toList();
        }
        if (value instanceof List<?> list) {
            return new java.util.ArrayList<>(list);
        }
        throw new AssertionError("not an array: " + value);
    }

    private static List<String> asArray(Object value) {
        return asList(value).stream().map(String::valueOf).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static Map<String, Map<String, Object>> byName(Map<String, Object> root) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Object entry : asList(root.get("commands"))) {
            Map<String, Object> command = asMap(entry);
            result.put(String.valueOf(command.get("name")), command);
        }
        return result;
    }

    private static void resetOffline() {
        try {
            var field = ArtifactStore.class.getDeclaredField("offline");
            field.setAccessible(true);
            field.set(null, false);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot reset the offline flag", e);
        }
    }

    private record Result(int exit, String out, String err) {}
}
