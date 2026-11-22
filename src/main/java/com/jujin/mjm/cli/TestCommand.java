package com.jujin.mjm.cli;

import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.mjm.project.MjProject;
import com.jujin.mjm.resolve.ArtifactLocations;
import com.jujin.mjm.project.ProjectReader;
import com.jujin.mjm.store.ArtifactStore;
import com.jujin.mjm.store.LockFileReader;
import com.jujin.mjm.store.LockedArtifact;
import com.jujin.mjm.test.MjmTest;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@code mjm test} — compiles test sources, then forks a separate JVM to
 * execute them. The forked JVM's classpath IS the test classpath: test
 * classes, code under test, closure jars, junit toolchain, and mjm's test API
 * ({@code MjmTestFork}). No mjm process classes leak in — zero classloader
 * identity issues.
 *
 * <p>Two runners in the forked JVM ({@code MjmTestFork}):
 * <ul>
 *   <li>{@code @MjmTest} — built-in, zero-dep</li>
 *   <li>JUnit Jupiter — via the Platform Launcher</li>
 * </ul>
 */
public final class TestCommand implements Command {

    private static final String MAIN_SOURCES = "src/main/java";
    private static final String MAIN_OUTPUT = "target/classes";
    private static final String TEST_SOURCES = "src/test/java";
    private static final String TEST_OUTPUT = "target/test-classes";

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
        return "compile and run src/test/java — @MjmTest and JUnit Jupiter";
    }

    @Override
    public int execute(List<String> args) {
        if (!Files.exists(projectDir.resolve("mjm.lock"))) {
            System.err.println("mjm: no mjm.lock in " + projectDir + " — run mjm lock first");
            return 1;
        }
        Filter filter = Filter.from(projectDir);
        List<String> testSources = sources(projectDir.resolve(TEST_SOURCES), filter);
        if (testSources.isEmpty()) {
            System.out.println("no tests under " + projectDir.resolve(TEST_SOURCES));
            return 0;
        }
        // Tests compile against the project's own classes. Without them javac
        // reports a symbol error deep inside a test file, which reads as "your
        // test is wrong" instead of "build first" — the other commands that use
        // target/classes (package/bundle/publish/run) already say the latter.
        // A pure-test project has no main sources and needs no target/classes.
        if (hasMainSources() && !Files.exists(projectDir.resolve(MAIN_OUTPUT))) {
            System.err.println("mjm: " + MAIN_OUTPUT + " is missing — run mjm build first");
            return 1;
        }

        // The output tree is rewritten in place (deleted, then compiled into):
        // a second command touching target/ — another test run, a build, a
        // clean — would pull classes out from under the fork, and the lazily
        // loaded javac companions (enum switch maps) die first.
        return ProjectLock.with(projectDir, () -> runTests(testSources));
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
    private int runTests(List<String> testSources) {
        List<Path> toolchain = toolchainPaths();
        List<String> compileClasspath = classpath(toolchain, false);
        if (!compile(testSources, compileClasspath)) {
            return 1;
        }

        // fork a separate JVM — complete classloader isolation from the mjm process
        List<String> runClasspath = classpath(toolchain, true);
        Path testOutput = projectDir.resolve(TEST_OUTPUT);

        List<String> command = new ArrayList<>();
        command.add("java");
        command.add("-cp");
        command.add(String.join(File.pathSeparator, runClasspath));
        command.add("com.jujin.mjm.test.MjmTestFork");
        command.add(testOutput.toString());

        try {
            Process process = new ProcessBuilder(command).inheritIO().start();
            return process.waitFor();
        } catch (IOException e) {
            System.err.println("mjm: cannot fork test JVM: " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        }
    }

    // ------------------------------------------------------------------
    // classpath assembly
    // ------------------------------------------------------------------

    private List<String> classpath(List<Path> toolchain, boolean includeTestOutput) {
        List<String> entries = new ArrayList<>();
        Path testOutput = projectDir.resolve(TEST_OUTPUT);
        if (includeTestOutput && Files.exists(testOutput)) {
            entries.add(testOutput.toString());
        }
        entries.add(projectDir.resolve("target/classes").toString());
        // the full closure, MAIN and DEV alike: [dev-deps] exists for this
        // classpath and for nothing else
        for (LockedArtifact artifact : lock()) {
            Path jar = store.findByDigest(artifact.sha256(), ArtifactStore.Kind.JAR);
            if (jar != null) {
                entries.add(jar.toString());
            }
        }
        entries.add(runtimeLocation().toString());
        for (Path jar : toolchain) {
            entries.add(jar.toString());
        }
        return entries;
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
     * {@code [test].include} / {@code [test].exclude} patterns from mjm.toml.
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
            // error in mjm.toml surfaces here instead of silently meaning "run all".
            MjProject project;
            try {
                project = ProjectReader.parse(Files.readString(projectDir.resolve("mjm.toml")));
            } catch (java.nio.file.NoSuchFileException e) {
                // no manifest at all: build/lock already report that; test unfiltered
                return new Filter(List.of(), List.of());
            } catch (IOException e) {
                // the file is there but cannot be read — silence would run every
                // test and hide why the filters did not apply
                throw new IllegalStateException("cannot read "
                        + projectDir.resolve("mjm.toml") + " — " + e.getMessage(), e);
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

    /** {@code [java].version} from mjm.toml, or null — the test compile target. */
    private Integer projectJavaVersion() {
        try {
            return ProjectReader.parse(Files.readString(projectDir.resolve("mjm.toml")))
                    .javaVersion();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private List<LockedArtifact> lock() {
        try {
            return LockFileReader.read(Files.readString(projectDir.resolve("mjm.lock")));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read mjm.lock", e);
        }
    }

    private static Path runtimeLocation() {
        try {
            return Path.of(MjmTest.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI());
        } catch (Exception e) {
            return Path.of("");
        }
    }

    private boolean compile(List<String> sources, List<String> classpath) {
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
                System.err.println("mjm: cannot clean " + output + ": " + e.getMessage());
                return false;
            }
        }
        try {
            Files.createDirectories(output);
        } catch (IOException e) {
            System.err.println("mjm: cannot create " + output);
            return false;
        }
        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        List<String> command = new ArrayList<>();
        command.add("-d");
        command.add(output.toString());
        command.add("-cp");
        command.add(String.join(File.pathSeparator, classpath));
        // test code compiles for the same target as the code under test
        Integer javaVersion = projectJavaVersion();
        if (javaVersion != null) {
            command.add("--release");
            command.add(String.valueOf(javaVersion));
        }
        command.addAll(sources);
        int exit = compiler.run(null, null, null, command.toArray(String[]::new));
        if (exit != 0) {
            System.err.println("mjm: test compilation failed");
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
