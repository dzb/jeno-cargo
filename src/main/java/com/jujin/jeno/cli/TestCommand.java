package com.jujin.jeno.cli;

import com.jujin.freeway.commons.json.JsonArray;
import com.jujin.freeway.commons.json.JsonObject;
import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.jeno.project.JenoProject;
import com.jujin.jeno.resolve.ArtifactLocations;
import com.jujin.jeno.project.ProjectReader;
import com.jujin.jeno.store.ArtifactStore;
import com.jujin.jeno.store.LockFileReader;
import com.jujin.jeno.store.LockedArtifact;
import com.jujin.jeno.test.JenoTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@code jeno test} — compiles test sources, then forks a separate JVM to
 * execute them. The forked JVM's classpath IS the test classpath: test
 * classes, code under test, closure jars, junit toolchain, and jeno's test API
 * ({@code JenoTestFork}). No jeno process classes leak in — zero classloader
 * identity issues.
 *
 * <p>Two runners in the forked JVM ({@code JenoTestFork}):
 * <ul>
 *   <li>{@code @JenoTest} — built-in, zero-dep</li>
 *   <li>JUnit Jupiter — via the Platform Launcher</li>
 * </ul>
 */
public final class TestCommand implements Command {

    private static final String MAIN_SOURCES = "src/main/java";
    private static final String MAIN_OUTPUT = "target/classes";
    private static final String TEST_SOURCES = "src/test/java";
    private static final String TEST_OUTPUT = "target/test-classes";
    private static final String RESULTS = "target/test-results";

    private static final List<String[]> JUNIT_TOOLCHAIN = List.of(
            new String[]{"org.junit.jupiter", "junit-jupiter-api", "6.1.3"},
            new String[]{"org.junit.jupiter", "junit-jupiter-engine", "6.1.3"},
            new String[]{"org.junit.platform", "junit-platform-launcher", "6.1.3"},
            new String[]{"org.junit.platform", "junit-platform-engine", "6.1.3"},
            new String[]{"org.junit.platform", "junit-platform-commons", "6.1.3"},
            new String[]{"org.opentest4j", "opentest4j", "1.3.0"});

    private final ArtifactStore store;
    private final Path projectDir;

    public TestCommand(ArtifactStore store, Path projectDir) {
        this.store = store;
        this.projectDir = projectDir;
    }

    @Override
    public String name() {
        return "test";
    }

    @Override
    public String summary() {
        return "compile and run src/test/java — @JenoTest and JUnit Jupiter";
    }

    @Override
    public Command.Category category() {
        return Command.Category.BUILD;
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public CommandCapabilities capabilities() {
        return CommandCapabilities.builder()
                .json()
                .network(CommandCapabilities.NetworkMode.OPTIONAL)
                .writes(CommandCapabilities.WriteScope.TARGET,
                        CommandCapabilities.WriteScope.CACHE)
                .workspace(CommandCapabilities.WorkspaceMode.AGGREGATE)
                .build();
    }

    @Override
    public int execute(List<String> args) {
        ProjectFiles.rejectLegacy(projectDir);
        boolean json = args.contains("--json");
        List<String> cleaned = args.stream().filter(arg -> !arg.equals("--json")).toList();
        if (json) {
            return JsonWorkspace.run("test", projectDir, cleaned, this::jsonModule);
        }
        return executeHuman(cleaned, false);
    }

    private JsonWorkspace.ModuleResult jsonModule(Path moduleDir, List<String> args) {
        if (!args.isEmpty()) {
            return JsonWorkspace.ModuleResult.failed(2, "USAGE",
                    "test takes no arguments — got '" + args.getFirst() + "'");
        }
        long started = System.nanoTime();
        JsonCapture.Result captured = JsonCapture.runStdout(() ->
                new TestCommand(store, moduleDir).executeHuman(args, true));
        TestSummary summary = summarize(moduleDir);
        JsonObject data = summary.json(moduleDir, started);
        if (captured.exitCode() == 0) {
            return JsonWorkspace.ModuleResult.ok(data);
        }
        if (summary.tests() > 0 || summary.files().size() > 0) {
            return JsonWorkspace.ModuleResult.completed(captured.exitCode(), data,
                    "TEST_FAILED", summary.failed() + " test(s) failed");
        }
        return JsonWorkspace.ModuleResult.failed(captured.exitCode(),
                captured.exitCode() == 2 ? "USAGE" : "TEST_FAILED",
                "test run failed before producing reports — see stderr");
    }

    private int executeHuman(List<String> args, boolean machineOutput) {
        Integer workspace = WorkspaceRun.orchestrate(projectDir, args, "modules", true,
                (dir, rest) -> new TestCommand(store, dir).execute(rest));
        if (workspace != null) {
            return workspace;
        }
        if (!args.isEmpty()) {
            System.err.println(Ui.errPrefix() + "test takes no arguments — got '" + args.getFirst() + "'");
            return 2;
        }
        if (!Files.exists(projectDir.resolve("jeno.lock"))) {
            System.err.println(Ui.errPrefix() + "no jeno.lock in " + projectDir + " — run jeno lock first");
            return 1;
        }
        Filter filter = Filter.from(projectDir);
        List<String> testSources = sources(projectDir.resolve(TEST_SOURCES), filter);

        // The output tree is rewritten in place (deleted, then compiled into):
        // a second command touching target/ — another test run, a build, a
        // clean — would pull classes out from under the fork, and the lazily
        // loaded javac companions (enum switch maps) die first.
        return ProjectLock.with(projectDir, () -> {
            // Last run's XML must not outlive this one: an empty suite or a
            // compile failure below still means "the results on disk are not
            // from this run", so clearing happens before both.
            if (!clearResults()) {
                return 1;
            }
            // Read the lock before the empty-suite fast path: an old-name
            // source must fail through the same migration path as a real run.
            lock();
            if (testSources.isEmpty()) {
                System.out.println(Ui.warn("no tests under "
                        + projectDir.resolve(TEST_SOURCES)));
                return 0;
            }
            // Tests compile against the project's own classes. Without them javac
            // reports a symbol error deep inside a test file, which reads as "your
            // test is wrong" instead of "build first" — the other commands that use
            // target/classes (package/bundle/publish/run) already say the latter.
            // A pure-test project has no main sources and needs no target/classes.
            if (hasMainSources() && !Files.exists(projectDir.resolve(MAIN_OUTPUT))) {
                System.err.println(Ui.errPrefix() + MAIN_OUTPUT + " is missing — run jeno build first");
                return 1;
            }
            return runTests(testSources, machineOutput);
        });
    }

    /** Empties {@code target/test-results}; false when it cannot be done. */
    private boolean clearResults() {
        try {
            DistOutput.clear(projectDir.resolve(RESULTS));
            return true;
        } catch (IOException e) {
            System.err.println(Ui.errPrefix() + "cannot clear " + RESULTS + " — " + e.getMessage());
            return false;
        }
    }

    /** Whether {@code src/main/java} actually holds Java sources to compile. */
    private boolean hasMainSources() {
        Path root = projectDir.resolve(MAIN_SOURCES);
        if (!Files.isDirectory(root)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.anyMatch(path -> Files.isRegularFile(path)
                    && path.toString().endsWith(".java"));
        } catch (IOException e) {
            return false;  // unreadable tree: let the compile fail with its own message
        }
    }

    /** Compile and fork; the caller holds the project's lock. */
    private int runTests(List<String> testSources, boolean machineOutput) {
        long started = System.nanoTime();
        List<Path> toolchain = toolchainPaths();
        ClasspathAssembler.Classpath compileClasspath = classpath(toolchain, false);
        if (!compile(testSources, compileClasspath.joined(), processorPath())) {
            return 1;
        }

        // fork a separate JVM — complete classloader isolation from the jeno process
        String runClasspath = classpath(toolchain, true).joined();
        Path testOutput = projectDir.resolve(TEST_OUTPUT);

        Path results = projectDir.resolve(RESULTS);
        List<String> command = new ArrayList<>();
        command.add("java");
        command.add("-cp");
        command.add(runClasspath);
        command.add("com.jujin.jeno.test.JenoTestFork");
        command.add(testOutput.toString());
        command.add(results.toAbsolutePath().toString());

        int exit;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            // A forked test JVM sees stdout as a pipe; force deterministic output
            // even if the outer invocation used JENO_COLOR=always.
            builder.environment().put("JENO_COLOR", "never");
            Process process = machineOutput
                    ? builder.redirectErrorStream(true).start()
                    : builder.inheritIO().start();
            if (machineOutput) {
                try (var childOutput = process.getInputStream()) {
                    childOutput.transferTo(System.err);
                }
            }
            exit = process.waitFor();
        } catch (IOException e) {
            System.err.println(Ui.errPrefix() + "cannot fork test JVM: " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        }
        if (Files.isDirectory(results)) {
            long files;
            try (Stream<Path> entries = Files.list(results)) {
                files = entries.filter(path -> path.toString().endsWith(".xml")).count();
            } catch (IOException e) {
                files = 0;
            }
            System.out.println(Ui.dim("results: " + RESULTS + " (" + files + " files)")
                    + Ui.duration(started));
        }
        return exit;
    }

    // ------------------------------------------------------------------
    // JSON test summary
    // ------------------------------------------------------------------

    private record TestSummary(int tests, int failed, int skipped, List<Path> files) {
        JsonObject json(Path moduleDir, long started) {
            JsonObject data = JsonUtils.object();
            data.put("reportsDir", moduleDir.resolve(RESULTS)
                    .toAbsolutePath().normalize().toString());
            data.put("reportFormat", "junit-xml");
            data.put("tests", tests);
            data.put("passed", tests - failed - skipped);
            data.put("failed", failed);
            data.put("skipped", skipped);
            data.put("noTests", tests == 0);
            data.put("durationMs", (System.nanoTime() - started) / 1_000_000L);
            JsonArray reports = JsonUtils.array();
            files.forEach(path -> reports.add(path.toAbsolutePath().normalize().toString()));
            data.put("reportFiles", reports);
            return data;
        }
    }

    private static TestSummary summarize(Path moduleDir) {
        Path reportsDir = moduleDir.resolve(RESULTS);
        if (!Files.isDirectory(reportsDir)) {
            return new TestSummary(0, 0, 0, List.of());
        }
        int tests = 0;
        int failed = 0;
        int skipped = 0;
        List<Path> files = new ArrayList<>();
        try (Stream<Path> entries = Files.list(reportsDir)) {
            for (Path file : entries.filter(path -> path.toString().endsWith(".xml"))
                    .sorted().toList()) {
                files.add(file);
                String text = Files.readString(file);
                tests += attribute(text, "tests");
                failed += attribute(text, "failures") + attribute(text, "errors");
                skipped += attribute(text, "skipped");
            }
        } catch (IOException e) {
            return new TestSummary(0, 0, 0, List.of());
        }
        return new TestSummary(tests, failed, skipped, List.copyOf(files));
    }

    private static int attribute(String xml, String name) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\b" + java.util.regex.Pattern.quote(name) + "=\"(\\d+)\"")
                .matcher(xml);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    // ------------------------------------------------------------------
    // classpath assembly
    // ------------------------------------------------------------------

    /** Processor-scope artifacts, resolved exactly as the compile classpath is. */
    private List<String> processorPath() {
        Substitutions substitutions = Substitutions.forProject(projectDir);
        List<String> paths = new ArrayList<>();
        for (LockedArtifact artifact : lock()) {
            if (artifact.scope() == com.jujin.jeno.resolve.Scope.PROCESSOR) {
                paths.add(ClasspathAssembler.artifactPath(
                        artifact, store, substitutions, false).toString());
            }
        }
        return paths;
    }

    private ClasspathAssembler.Classpath classpath(List<Path> toolchain,
                                                  boolean includeTestOutput) {
        return ClasspathAssembler.of(ClasspathAssembler.Mode.TEST, projectDir, store,
                ClasspathAssembler.Input.test(lock(), toolchain, runtimeLocation(),
                        includeTestOutput));
    }

    /**
     * The JUnit toolchain: shipped jars (lib/toolchain/) when running from a
     * distribution; store-downloaded jars in the exploded development layout.
     * Injected only when the closure does not already declare
     * junit-jupiter-api — one id, one version (SPEC.md §4.2).
     */
    public List<Path> toolchainPaths() {
        Path shipped = shippedToolchain();
        if (shipped != null) {
            List<Path> jars = new ArrayList<>();
            try (Stream<Path> stream = Files.walk(shipped)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".jar"))
                        .sorted()
                        .forEach(jars::add);
            } catch (IOException e) {
                throw new IllegalStateException("cannot read " + shipped, e);
            }
            return List.copyOf(jars);
        }
        List<Path> paths = new ArrayList<>();
        boolean declaredInClosure = false;
        for (LockedArtifact artifact : lock()) {
            if (artifact.id().equals("org.junit.jupiter:junit-jupiter-api")) {
                declaredInClosure = true;
            }
        }
        if (declaredInClosure) {
            return paths;
        }
        for (String[] coordinate : JUNIT_TOOLCHAIN) {
            String url = ArtifactLocations.mavenFile(ArtifactLocations.centralBase(),
                    coordinate[0], coordinate[1], coordinate[2], "jar");
            paths.add(store.ensure(url, ArtifactStore.Kind.JAR));
        }
        return paths;
    }

    private Path shippedToolchain() {
        Path runtime = runtimeLocation();
        if (!runtime.toString().endsWith(".jar")) {
            return null;
        }
        Path toolchain = runtime.getParent().resolve("toolchain");
        return Files.isDirectory(toolchain) ? toolchain : null;
    }

    /**
     * {@code [test].include} / {@code [test].exclude} patterns from jeno.toml.
     * Maven's surefire has the same pair; without them a project cannot skip
     * integration-style tests that need a running service.
     */
    static final class Filter {
        final List<String> include;
        final List<String> exclude;

        Filter(List<String> include, List<String> exclude) {
            this.include = include;
            this.exclude = exclude;
        }

        static Filter from(Path projectDir) {
            // ProjectReader owns [test]: patterns arrive typed and closed, so an
            // error in jeno.toml surfaces here instead of silently meaning "run all".
            JenoProject project;
            try {
                project = ProjectReader.parse(Files.readString(projectDir.resolve("jeno.toml")));
            } catch (java.nio.file.NoSuchFileException e) {
                // no manifest at all: build/lock already report that; test unfiltered
                return new Filter(List.of(), List.of());
            } catch (IOException e) {
                // the file is there but cannot be read — silence would run every
                // test and hide why the filters did not apply
                throw new IllegalStateException("cannot read "
                        + projectDir.resolve("jeno.toml") + " — " + e.getMessage(), e);
            }
            return new Filter(project.testInclude(), project.testExclude());
        }

        /** A source path is kept when it matches no exclude and, if includes exist, one include. */
        boolean keeps(String path) {
            String name = Path.of(path).getFileName().toString();
            for (String pattern : exclude) {
                if (matches(pattern, name) || matches(pattern, path)) {
                    return false;
                }
            }
            if (include.isEmpty()) {
                return true;
            }
            for (String pattern : include) {
                if (matches(pattern, name) || matches(pattern, path)) {
                    return true;
                }
            }
            return false;
        }

        /** {@code *} matches any run of characters — the shape surefire users expect. */
        private static boolean matches(String pattern, String candidate) {
            StringBuilder regex = new StringBuilder();
            for (char c : pattern.toCharArray()) {
                if (c == '*') {
                    regex.append(".*");
                } else {
                    regex.append(java.util.regex.Pattern.quote(String.valueOf(c)));
                }
            }
            return candidate.matches(regex.toString());
        }
    }

    /** {@code [java].version} from jeno.toml, or null — the test compile target. */
    private Integer projectJavaVersion() {
        try {
            return ProjectReader.parse(Files.readString(projectDir.resolve("jeno.toml")))
                    .javaVersion();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private List<LockedArtifact> lock() {
        try {
            return LockFileReader.read(Files.readString(projectDir.resolve("jeno.lock")));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read jeno.lock", e);
        }
    }

    private static Path runtimeLocation() {
        try {
            return Path.of(JenoTest.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI());
        } catch (Exception e) {
            return Path.of("");
        }
    }

    private boolean compile(List<String> sources, String classpath,
                            List<String> processorPath) {
        Path output = projectDir.resolve(TEST_OUTPUT);
        // a stale test-classes tree makes deleted tests run forever: the fork
        // scans whatever is on disk, so `NoClassDefFoundError` from a removed
        // class lingers until someone deletes target/ by hand
        if (Files.isDirectory(output)) {
            try (Stream<Path> walk = Files.walk(output)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new IllegalStateException("cannot clean " + path, e);
                    }
                });
            } catch (IOException e) {
                System.err.println(Ui.errPrefix() + "cannot clean " + output + ": " + e.getMessage());
                return false;
            }
        }
        try {
            Files.createDirectories(output);
        } catch (IOException e) {
            System.err.println(Ui.errPrefix() + "cannot create " + output);
            return false;
        }
        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        List<String> command = new ArrayList<>();
        command.add("-d");
        command.add(output.toString());
        command.add("-cp");
        command.add(classpath);
        // annotation processors ride on -processorpath, never the classpath:
        // they are build tools, not something the tests run against
        if (!processorPath.isEmpty()) {
            command.add("-processorpath");
            command.add(ClasspathAssembler.join(processorPath));
        }
        // test code compiles for the same target as the code under test
        Integer javaVersion = projectJavaVersion();
        if (javaVersion != null) {
            command.add("--release");
            command.add(String.valueOf(javaVersion));
        }
        command.addAll(sources);
        int exit = compiler.run(null, null, null, command.toArray(String[]::new));
        if (exit != 0) {
            System.err.println(Ui.errPrefix() + "test compilation failed");
            return false;
        }
        SourceResources.copy(projectDir.resolve("src/test/resources"), output);
        return true;
    }

    private List<String> sources(Path root, Filter filter) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(Path::toString)
                    .filter(filter::keeps)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("cannot walk " + root, e);
        }
    }
}
